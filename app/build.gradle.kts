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
        versionCode = 1
        versionName = "0.1.0"
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
    // Embedded Tailscale (tsnet), built from ../tsbridge with gomobile (see tsbridge/build.sh).
    implementation(files("libs/tsbridge.aar"))
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    testImplementation("junit:junit:4.13.2")
}
