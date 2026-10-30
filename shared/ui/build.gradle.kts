plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
kotlin {
    jvm()
    android {
        namespace = "com.coach.shared.ui"
        compileSdk = 37
        minSdk = 34
    }
    jvmToolchain(17)
    sourceSets.commonMain.dependencies {
        implementation(project(":shared:domain"))
        implementation("org.jetbrains:markdown:0.7.3")
        implementation(libs.coroutines.core)
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(libs.material3)
        implementation(libs.lifecycle.compose)
        implementation(libs.serialization.json)
        implementation(libs.navigationevent)
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
