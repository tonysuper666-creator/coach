plugins {
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
    sourceSets.commonMain.dependencies { api(project(":shared:protocol")); implementation(libs.coroutines.core) }
}
