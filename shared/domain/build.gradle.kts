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
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.jvmTest.dependencies { implementation(libs.serialization.json) }
}

tasks.withType<Test>().configureEach { systemProperty("coach.repo", rootDir.absolutePath) }
