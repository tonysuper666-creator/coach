plugins {
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
kotlin {
    jvm()
    android {
        namespace = "com.coach.shared.client"
        compileSdk = 37
        minSdk = 34
    }
    jvmToolchain(17)
    sourceSets.commonMain.dependencies { api(project(":shared:protocol")); implementation(libs.coroutines.core); api("app.cash.sqldelight:runtime:2.4.0"); implementation("io.ktor:ktor-client-core:3.3.3"); implementation("io.ktor:ktor-client-cio:3.3.3"); implementation("io.ktor:ktor-client-websockets:3.3.3") }
}

sqldelight { databases { create("ClientDatabase") { packageName.set("com.coach.client.db"); dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.0") } } }
