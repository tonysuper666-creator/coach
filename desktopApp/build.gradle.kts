import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    id("coach.jvm")
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

tasks.matching { it.name.startsWith("package") || it.name == "createDistributable" }.configureEach {
    dependsOn(verifyReleaseVersion)
}
dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:client"))
    implementation(libs.sqldelight.driver)
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
    implementation(libs.material3)
    testImplementation(kotlin("test"))
    testImplementation(project(":server:app"))
    testImplementation(project(":server:core"))
    testImplementation(project(":server:ai"))
    testImplementation(libs.ktor.netty)
    testImplementation(libs.ktor.websockets)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}
val localConfig = Properties().apply {
    val file = File(System.getProperty("user.home"), "Coach/config/local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val coachCourse = providers.gradleProperty("coach.course")
    .orElse(localConfig.getProperty("coach.course", "")).get().trim()
require(coachCourse.isEmpty() || coachCourse.matches(Regex("[A-Za-z0-9_-]+"))) {
    "coach.course must contain only letters, digits, underscores or hyphens"
}
compose.desktop {
    application {
        mainClass = "com.coach.desktop.MainKt"
        if (coachCourse.isNotEmpty()) jvmArgs("-Dcoach.course=$coachCourse")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Coach"
            packageVersion = releaseVersion
            vendor = "Coach"
            modules("java.sql")
            windows {
                // Per-user so the installer needs no administrator prompt on the target PC.
                // upgradeUuid stays fixed: a newer MSI replaces program files in place and
                // leaves the user's ~/Coach data (ledger copy, token, state) untouched.
                perUserInstall = true
                shortcut = true
                menuGroup = "Coach"
                upgradeUuid = "6f1b0a52-6c4d-4d3f-9a1e-2b7c5d84e910"
            }
        }
    }
}
tasks.withType<JavaExec>().configureEach {
    providers.gradleProperty("coach.autoExitSeconds").orNull?.let { systemProperty("coach.autoExitSeconds", it) }
}

// Existing native reader host, with an explicit HTTP question owner for citation navigation.
tasks.register<JavaExec>("runReaderWindow") {
    group = "application"
    description = "Open the native reader; COACH_READER_ITEM/WEEK enable the HTTP explanation workspace"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.coach.desktop.reader.ReaderWindowHostKt")
    for (key in listOf("coach.url", "coach.course", "coach.reader.item", "coach.reader.week", "coach.reader.doc", "coach.reader.page", "coach.library.dir")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

// Ship the very same stdlib-only extraction core used by tools.notes, including in DMGs.
val notesCore by tasks.registering(Zip::class) {
    from(rootProject.file("tools")) {
        include("notes/__init__.py", "notes/exporter.py", "notes/mathml.py", "notes/desktop.py")
    }
    archiveFileName.set("notes-core.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/notes"))
}
sourceSets.main { resources.srcDir(notesCore.map { it.destinationDirectory }) }
tasks.processResources { dependsOn(notesCore) }

tasks.withType<JavaExec>().configureEach {
    for (key in listOf("coach.exportFormulas", "coach.capturePath", "coach.captures", "coach.captureDir")) providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
}

// Real pairing/HTTP/WS against an owned, disposable Ktor process; no desktop automation or AI traffic.
tasks.register<JavaExec>("pairingSmoke") {
    group = "verification"
    description = "Verify desktop first connection, private identity, restart, offline and revocation against an isolated server"
    dependsOn(tasks.testClasses, ":server:app:installDist")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coach.desktop.DesktopPairingSmoke")
    args(rootDir.absolutePath)
}

// Disposable synthetic fixture uses the same native workspace, product HTTP routes and ReaderWindow.
tasks.register<JavaExec>("citationWindowSmoke") {
    group = "verification"
    description = "Open an isolated native citation window backed by real synthetic HTTP/PDF"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coach.desktop.reader.CitationWindowSmoke")
    for (key in listOf("coach.citation.auto", "coach.citation.samples")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

// Only synthetic connection values are loaded by this replay; never read the user's pairing config.
tasks.named<JavaExec>("citationWindowSmoke") {
    providers.gradleProperty("coach.citation.environment").orNull?.let { name ->
        val connection = Properties().apply { file(name).inputStream().use { load(it) } }
        val allowed = setOf("COACH_URL", "COACH_COURSE", "COACH_DEVICE_ID", "COACH_TOKEN_FILE", "COACH_READER_ITEM", "COACH_READER_WEEK")
        require(connection.stringPropertyNames().all { it in allowed })
        require(connection.getProperty("COACH_COURSE") == "SYNTHETIC")
        connection.forEach { key, value -> environment(key.toString(), value.toString()) }
    }
}
tasks.register<JavaExec>("citationFixtureServer") {
    group = "verification"
    description = "Host the disposable synthetic HTTP/PDF fixture on Mac for a remote native client"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.coach.desktop.reader.CitationFixtureHost")
    for (key in listOf("coach.citation.control", "coach.citation.bind", "coach.pdf.bin")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
