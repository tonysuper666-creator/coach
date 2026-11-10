import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Keep this release contract in sync in both application build scripts. Values live
// only in gradle.properties; debug development builds need no published-version bump.
val releaseVersion = providers.gradleProperty("coach.release.version").get()
val releaseVersionCode = providers.gradleProperty("coach.release.versionCode").get().toInt()
fun msiVersion(value: String): List<Int> {
    require(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
        "Release version must be canonical major.minor.patch: $value"
    }
    val parts = value.split('.').map { it.toLong() }
    require(parts[0] in 1..255 && parts[1] in 0..255 && parts[2] in 0..65535) {
        "Release version exceeds desktop/MSI limits (1..255, 0..255, 0..65535): $value"
    }
    return parts.map { it.toInt() }
}
fun newerVersion(candidate: List<Int>, previous: List<Int>): Boolean =
    candidate.zip(previous).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
msiVersion(releaseVersion)
require(releaseVersionCode in 1..2100000000) { "Android versionCode must be in 1..2100000000" }

val verifyReleaseVersion = tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Reject a release that does not advance the published desktop and Android versions"
    // Publishing tags and baseline changes must be checked on every packaging invocation.
    doLast {
        val candidate = msiVersion(releaseVersion)
        val published = providers.gradleProperty("coach.release.publishedVersion").get()
        val publishedCode = providers.gradleProperty("coach.release.publishedVersionCode").get().toInt()
        require(newerVersion(candidate, msiVersion(published)) && releaseVersionCode > publishedCode) {
            "Release must advance published $published ($publishedCode); requested $releaseVersion ($releaseVersionCode)"
        }
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", rootDir.absolutePath) + args)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Cannot read release tags: $output" }
            return output
        }
        // Tags are the durable release record. The baseline covers the untagged 1.0.0 freeze.
        git("fetch", "--tags", "origin")
        for (tag in git("tag", "--list", "v*").lineSequence().map { it.trim() }) {
            if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) continue
            require(newerVersion(candidate, msiVersion(tag.removePrefix("v")))) {
                "Release $releaseVersion must be newer than published tag $tag"
            }
            val taggedProperties = Properties().apply {
                git("show", "$tag:gradle.properties").reader().use { load(it) }
            }
            val taggedCode = taggedProperties.getProperty("coach.release.versionCode", "1").toInt()
            require(releaseVersionCode > taggedCode) {
                "Android versionCode $releaseVersionCode must exceed $tag ($taggedCode)"
            }
        }
        logger.lifecycle("Release version verified: $releaseVersion ($releaseVersionCode), baseline $published ($publishedCode)")
    }
}

tasks.matching { it.name == "preReleaseBuild" ||
    (it.name == "preDebugBuild" && providers.gradleProperty("coach.release").orNull == "true")
}.configureEach {
    dependsOn(verifyReleaseVersion)
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
        // Separate package for concurrent, isolated acceptance on the shared OPPO.
        applicationId = providers.gradleProperty("coachApplicationId").orElse("com.coach.android").get()
        buildConfigField("String", "COACH_TAIL_HOST", "\"$coachTailHost\"")
        // `-Pcoach.port=NNNN` builds against a non-production test server (real-device measurements must never pair with 18791).
        // The port is also written to the manifest (meta-data `coach.port`) so a script can read it back from an installed package.
        val coachPort = (providers.gradleProperty("coach.port").orNull ?: localConfig.getProperty("coach.port", "18791")).also { require(it.matches(Regex("[0-9]{2,5}"))) }
        buildConfigField("int", "COACH_PORT", coachPort)
        manifestPlaceholders["coachPort"] = coachPort
        // Isolated synthetic acceptance must not change the shared production configuration.
        val coachCourse = providers.gradleProperty("coach.course").orElse(localConfig.getProperty("coach.course", "")).get()
        buildConfigField("String", "COACH_COURSE", "\"$coachCourse\"")
        minSdk = 34
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("androidTest").kotlin.srcDir("../shared/domain/src/benchmark/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:client"))
    implementation(libs.material3)
    implementation("app.cash.sqldelight:android-driver:2.4.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.window:window:1.5.1")
    implementation(libs.coroutines.core)
    implementation("androidx.metrics:metrics-performance:1.0.0")
    testImplementation(project(":shared:domain"))
    testImplementation(project(":shared:protocol"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.78.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.78.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")
    androidTestImplementation(project(":shared:domain"))
    androidTestImplementation(project(":shared:client"))
    androidTestImplementation("app.cash.sqldelight:android-driver:2.4.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Direct JVM screenshot invocation; no device, emulator or plugin-managed recording state.
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.test.record").getOrElse("false"))
    systemProperty("coach.v7.output", layout.buildDirectory.dir("screens/v7").get().asFile.absolutePath)
    maxParallelForks = 1
}
