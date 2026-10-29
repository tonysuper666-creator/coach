plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp)
}
kotlin {
    jvm()
    android {
        namespace = "com.coach.shared.domain"
        compileSdk = 37
        minSdk = 34
    }
    jvmToolchain(17)
    sourceSets.getByName("jvmTest").dependencies {
        implementation(kotlin("test"))
    }
}
