package com.syeyoung.assistant.data

import android.content.Context

/**
 * How the app reaches the server. Directly when the tailnet is reachable (Tailscale VPN on), or
 * through the embedded Tailscale node, which lets the app join the tailnet by itself.
 */
object Connection {
    suspend fun endpoint(context: Context, settings: Settings): Endpoint =
        if (settings.useEmbeddedTailscale) TailscaleBridge.get(context).endpoint(settings.serverUrl)
        else Endpoint(settings.serverUrl)
}
