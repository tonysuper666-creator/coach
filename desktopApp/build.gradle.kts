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
    testImplementation(project(":tools:migrate-deeptutor"))
    implementation(project(":shared:ui"))
    implementation(project(":shared:client"))
    implementation(libs.sqldelight.driver)
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("net.java.dev.jna:jna:5.19.1")
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

// Explicit, independently approved offline inputs only. No PATH/key discovery or
// test-key fallback. Revalidate every invocation, including changes in-place.
val windowsRuntimeInput = providers.gradleProperty("coach.windowsBookRuntimeDir")
val windowsRuntimePin = providers.gradleProperty("coach.windowsBookRuntimeKeySha256")
val windowsRuntimeOutput = layout.buildDirectory.dir("generated/windows-book-runtime")
val prepareWindowsBookRuntime = tasks.register("prepareWindowsBookRuntime") {
    group = "build"
    description = "Reverify explicit Windows book runtime inputs, or remove the previous generated runtime"
    outputs.dir(windowsRuntimeOutput)
    outputs.upToDateWhen { false }
    doLast {
        val output = windowsRuntimeOutput.get().asFile
        // Owned generated files only. Invalidate before checking new inputs so
        // failed verification cannot leave a previously trusted staging tree.
        delete(output)
        // Compose's Sync is NO-SOURCE after withdrawal and may retain its old
        // destination. Revoke the reserved runtime there before that skip too.
        delete(tasks.named<Sync>("prepareAppResources").get().destinationDir.resolve("windows-book-runtime"))
        output.mkdirs()
        val input = windowsRuntimeInput.orNull
        val pin = windowsRuntimePin.orNull
        require((input == null) == (pin == null)) { "Runtime directory and independently approved key fingerprint are required together" }
        if (input == null) {
            logger.lifecycle("Windows book runtime: absent; ordinary entry remains Unavailable")
        } else {
            val source = rootProject.file(input)
            val runtime = source.resolve("resources/windows-book-runtime")
            val result = providers.exec {
                isIgnoreExitValue = true // retain the precise public-metadata refusal reason
                commandLine(providers.gradleProperty("coach.runtimePython").getOrElse("python3"),
                    rootProject.file("tools/windows-book-runtime/assemble-runtime.py"),
                    "--public-key", source.resolve("classpath/windows-book-runtime-trust.der"),
                    "--approved-key-sha256", pin!!,
                    "--manifest", runtime.resolve("manifest.json"), "--signature", runtime.resolve("manifest.sig"),
                    "--codex", runtime.resolve("codex.exe"), "--isolation", runtime.resolve("coach-iso.exe"),
                    "--auth-helper", runtime.resolve("coach-auth.exe"),
                    "--openssl", providers.gradleProperty("coach.runtimeOpenSsl").getOrElse("openssl"),
                    "--stage", output.resolve("verified"))
            }
            result.standardOutput.asText.get().trim().takeIf(String::isNotEmpty)?.let(logger::lifecycle)
            result.standardError.asText.get().trim().takeIf(String::isNotEmpty)?.let(logger::error)
            // A refusal aborts before either application resource set can be consumed.
            result.result.get().assertNormalExitValue()
            copy {
                from(output.resolve("verified/resources"))
                into(output.resolve("compose/windows"))
            }
        }
        // Keep the declared Compose source directory present even without inputs.
        output.resolve("compose/windows").mkdirs()
    }
}
sourceSets.main {
    resources.srcDir(prepareWindowsBookRuntime.map { windowsRuntimeOutput.get().dir("verified/classpath") })
}
tasks.processResources {
    dependsOn(prepareWindowsBookRuntime)
    outputs.upToDateWhen { false }
    // Copy normally retains removed sources. Revoke this reserved resource too
    // when approval is withdrawn, without cleaning unrelated application data.
    doFirst { delete(destinationDir.resolve("windows-book-runtime-trust.der")) }
}
compose.desktop {
    application {
        mainClass = "com.coach.desktop.MainKt"
        if (coachCourse.isNotEmpty()) jvmArgs("-Dcoach.course=$coachCourse")
        nativeDistributions {
            appResourcesRootDir.set(prepareWindowsBookRuntime.map { windowsRuntimeOutput.get().dir("compose") })
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
