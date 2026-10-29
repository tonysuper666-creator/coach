import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val localConfig = Properties().apply {
    val file = File(System.getProperty("user.home"), "Coach/config/local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val coachTailHost = localConfig.getProperty("coach.tailHost", "")
require(coachTailHost.isEmpty() || coachTailHost.matches(Regex("[0-9]+(\\.[0-9]+){3}"))) {
    "coach.tailHost must be an IPv4 literal in ~/Coach/config/local.properties"
}

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val host: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        require(host.get().isNotEmpty()) { "Set coach.tailHost in ~/Coach/config/local.properties" }
        val output = outputDirectory.file("xml/network_security_config.xml").get().asFile
        output.parentFile.mkdirs()
        output.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="false">${host.get()}</domain>
                </domain-config>
            </network-security-config>
        """.trimIndent())
    }
}
val generateNetworkSecurityConfig = tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
    host.set(coachTailHost)
    outputDirectory.set(layout.buildDirectory.dir("generated/coachNetworkRes"))
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateNetworkSecurityConfig, GenerateNetworkSecurityConfig::outputDirectory)
}

android {
    namespace = "com.coach.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.coach.android"
        buildConfigField("String", "COACH_TAIL_HOST", "\"$coachTailHost\"")
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared:ui"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.window:window:1.5.1")
    implementation(libs.coroutines.core)
    implementation("androidx.metrics:metrics-performance:1.0.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}
