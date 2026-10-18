package com.syeyoung.assistant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.syeyoung.assistant.tsbridge.Bridge
import com.syeyoung.assistant.tsbridge.Tsbridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

/**
 * The embedded Tailscale node ("assistant-android"): the app joins the tailnet by itself and talks
 * to the server through a local proxy that only this app can use (it needs a random token).
 */
class TailscaleBridge private constructor(private val context: Context) {
    private val lock = Mutex()
    @Volatile private var bridge: Bridge? = null
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    suspend fun endpoint(serverUrl: String): Endpoint {
        val b = start(serverUrl)
        return Endpoint("http://127.0.0.1:${b.port()}", mapOf("X-Bridge-Token" to token))
    }

    /** "Running", "NeedsLogin", "Starting", "NoState", "Stopped" or "error: …"; null before the first start. */
    fun state(): String? = bridge?.state()
    fun authUrl(): String? = bridge?.authURL()?.takeIf { it.isNotEmpty() }
    fun name(): String? = bridge?.name()?.takeIf { it.isNotEmpty() }

    /** Asks for a login link (see [authUrl]) when the node isn't signed in. */
    suspend fun login(serverUrl: String) = withContext(Dispatchers.IO) { start(serverUrl).login() }

    suspend fun start(serverUrl: String): Bridge = lock.withLock {
        bridge?.let { return it }
        withContext(Dispatchers.IO) {
            updateInterfaces()
            watchNetwork()
            val dir = File(context.filesDir, "tailscale").apply { mkdirs() }
            val settings = Settings(context)
            Tsbridge.start(dir.path, "assistant-android", settings.tailscaleAuthKey, serverUrl, token)
        }.also { bridge = it }
    }

    suspend fun stop() = lock.withLock {
        bridge?.stop()
        bridge = null
    }

    /** Android won't let Go list interfaces itself, so they're described from here. */
    private fun updateInterfaces() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lines = mutableListOf("lo 1 65536 up 127.0.0.1/8 ::1/128")
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val name = lp.interfaceName ?: continue
            val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: 0
            val addrs = lp.linkAddresses.joinToString(" ") { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }
            lines += "$name $index ${lp.mtu.takeIf { it > 0 } ?: 1500} up $addrs"
        }
        Tsbridge.setInterfaces(lines.joinToString("\n"))
    }

    private var watching = false
    private fun watchNetwork() {
        if (watching) return
        watching = true
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateInterfaces()
            override fun onLost(network: Network) = updateInterfaces()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = updateInterfaces()
        })
    }

    companion object {
        @Volatile private var instance: TailscaleBridge? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: TailscaleBridge(context.applicationContext).also { instance = it } }
    }
}
