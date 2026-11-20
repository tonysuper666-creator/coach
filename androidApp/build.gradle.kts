import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile

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

// The packager supplies the same private UTF-8 channel file the Windows MSI carries. Without it the
// app never checks for updates; the URL never becomes a command-line argument or a repository file.
val updateChannel = providers.environmentVariable("COACH_UPDATE_CHANNEL_FILE").orNull?.let { path ->
    val file = File(path)
    require(file.isFile) { "Missing update channel file" }
    file.readText(Charsets.UTF_8).trim().also { value ->
        val uri = runCatching { URI(value) }.getOrNull()
        // Plain HTTP is an acceptance-only convenience for a local server; release builds refuse it at runtime.
        val localTest = uri?.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        require(uri != null && (uri.scheme == "https" || localTest) && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.endsWith("/latest.json")) { "Invalid Android update channel" }
    }
}.orEmpty()

abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val host: Property<String>
    @get:Input abstract val extraHosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        require(host.get().isNotEmpty()) { "Set coach.tailHost in ~/Coach/config/local.properties" }
        val output = outputDirectory.file("xml/network_security_config.xml").get().asFile
        output.parentFile.mkdirs()
        val domains = (listOf(host.get()) + extraHosts.get()).joinToString("\n") {
            """        <domain includeSubdomains="false">$it</domain>"""
        }
        output.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<network-security-config>\n" +
                "    <base-config cleartextTrafficPermitted=\"false\" />\n" +
                "    <domain-config cleartextTrafficPermitted=\"true\">\n" +
                domains + "\n" +
                "    </domain-config>\n" +
                "</network-security-config>\n",
        )
    }
}
val generateNetworkSecurityConfig = tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
    host.set(coachTailHost)
    // A local update-channel acceptance build serves latest.json over plain HTTP from the build host.
    extraHosts.set(if (updateChannel.startsWith("http://")) listOf("10.0.2.2", "127.0.0.1") else emptyList())
    outputDirectory.set(layout.buildDirectory.dir("generated/coachNetworkRes"))
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateNetworkSecurityConfig, GenerateNetworkSecurityConfig::outputDirectory)
}

val androidBookSqlite by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

abstract class GenerateBookSqliteLibraries : DefaultTask() {
    @get:InputFiles abstract val nativeJar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        ZipFile(nativeJar.singleFile).use { zip ->
            val architecture = mapOf("aarch64" to "arm64-v8a", "arm" to "armeabi-v7a",
                "x86" to "x86", "x86_64" to "x86_64")
            for ((archiveAbi, androidAbi) in architecture) {
                val entry = checkNotNull(zip.getEntry("org/sqlite/native/Linux-Android/$archiveAbi/libsqlitejdbc.so")) {
                    "SQLite Android native library is missing for $androidAbi"
                }
                val library = outputDirectory.file("$androidAbi/libsqlitejdbc.so").get().asFile
                library.parentFile.mkdirs()
                zip.getInputStream(entry).use { source -> library.outputStream().use { source.copyTo(it) } }
            }
        }
    }
}

val generateBookSqliteLibraries = tasks.register<GenerateBookSqliteLibraries>("generateBookSqliteLibraries") {
    nativeJar.from(androidBookSqlite)
    outputDirectory.set(layout.buildDirectory.dir("generated/bookSqlite/jniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(generateBookSqliteLibraries, GenerateBookSqliteLibraries::outputDirectory)
}

android {
    sourceSets.getByName("main").kotlin.directories.add("../shared/books/src/main/kotlin")
    sourceSets.getByName("main").java.directories.add("../shared/books/src/main/java")
    packaging.resources.excludes.addAll(setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md"))
    namespace = "com.coach.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // Separate package for concurrent, isolated acceptance on the shared OPPO.
        val appId = providers.gradleProperty("coachApplicationId").orElse("com.coach.android").get()
        applicationId = appId
        // FileProvider authority follows the package name so isolated acceptance builds never collide.
        manifestPlaceholders["updateAuthority"] = "$appId.updates"
        buildConfigField("String", "COACH_UPDATE_CHANNEL", "\"${updateChannel.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        val friendUrl = (
            providers.gradleProperty("coach.friendUrl").orNull
                ?: System.getenv("COACH_FRIEND_URL")
                ?: localConfig.getProperty("coach.friendUrl", "")
            ).trim().trimEnd('/')
        require(friendUrl.isEmpty() || friendUrl.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?"))) { "Invalid private friend service origin" }
        buildConfigField("String", "COACH_FRIEND_URL", "\"$friendUrl\"")
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    androidBookSqlite("org.xerial:sqlite-jdbc:${libs.versions.sqlite.get()}:natives-android")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("com.ibm.icu:icu4j:78.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.caverock:androidsvg-aar:1.4")
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
    testImplementation("com.squareup.okhttp3:mockwebserver:5.2.1")
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
    androidTestImplementation("com.squareup.okhttp3:okhttp:5.2.1")
}

// Direct JVM screenshot invocation; no device, emulator or plugin-managed recording state.
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.test.record").getOrElse("false"))
    systemProperty("coach.v7.output", layout.buildDirectory.dir("screens/v7").get().asFile.absolutePath)
    maxParallelForks = 1
    // Robolectric's native graphics buffers must be released between test classes.
    forkEvery = 1
}
