plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.openapi.generator")
}

// The API client comes from the API's contract (api/openapi.json, copied from the API repo).
openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("api/openapi.json").path)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.path)
    packageName.set("com.syeyoung.assistant.api")
    library.set("jvm-ktor")
    configOptions.set(mapOf("serializationLibrary" to "kotlinx_serialization", "dateLibrary" to "string", "enumUnknownDefaultCase" to "true"))
}

android.sourceSets.getByName("main").kotlin.srcDir(file("build/generated/openapi/src/main/kotlin"))
tasks.named("preBuild") { dependsOn("openApiGenerate") }

android {
    namespace = "com.syeyoung.assistant"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.syeyoung.assistant"
        minSdk = 29
        targetSdk = 36
        // Releases pass the commit count (release.sh), so each one is newer than the last.
        val build = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionCode = build
        versionName = "0.2.$build"
    }

    // The release key lives only on the Mac that publishes releases (assistant/secrets), never in
    // CI: an update has to be signed by it, so control of GitHub alone can't ship one to the devices.
    val keystore = rootProject.file("../secrets/android-release.jks")
    signingConfigs {
        if (keystore.exists()) create("release") {
            val password = rootProject.file("../secrets/android-release.password").readText().trim()
            storeFile = keystore
            storePassword = password
            keyAlias = "assistant"
            keyPassword = password
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            // The phone, the tablet and the Quest are all arm64; leaving out the other ABIs of the
            // embedded Tailscale keeps updates small.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")
    // Writing on PDFs: Jetpack Ink for the pen (low-latency), PdfBox to keep the writing in the PDF.
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-geometry:1.0.0")
    implementation("androidx.ink:ink-storage:1.0.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Embedded Tailscale (tsnet), built from ../tsbridge with gomobile (see tsbridge/build.sh).
    implementation(files("libs/tsbridge.aar"))
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    testImplementation("junit:junit:4.13.2")
}
