plugins {
    alias(libs.plugins.kotlin.serialization)
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
    sourceSets.commonMain.dependencies { api(libs.serialization.json) }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.jvmTest { kotlin.srcDir("src/benchmark/kotlin") }
    jvmToolchain(17)
    sourceSets.getByName("jvmTest").dependencies {
        implementation(kotlin("test"))
    }
}
