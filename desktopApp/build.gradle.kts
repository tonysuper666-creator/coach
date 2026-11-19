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
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.ibm.icu:icu4j:78.1")
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
// 「用 Sable 账号登录」入口：默认收起（生产授权没放行，TON-1760）。候选构建时显式传
// -Pcoach.sableLogin=true 才打开；不从 local.properties 读，免得哪台机器的旧配置悄悄改了包。
val coachSableLogin = providers.gradleProperty("coach.sableLogin").orNull?.trim()?.lowercase()
require(coachSableLogin == null || coachSableLogin == "true" || coachSableLogin == "false") {
    "coach.sableLogin must be true or false"
}
compose.desktop {
    application {
        mainClass = "com.coach.desktop.MainKt"
        localConfig.getProperty("coach.friendUrl")?.takeIf(String::isNotBlank)?.let { origin ->
            require(origin.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?"))) { "Invalid private friend service origin" }
            jvmArgs("-Dcoach.friendUrl=$origin")
        }
        if (coachCourse.isNotEmpty()) jvmArgs("-Dcoach.course=$coachCourse")
        if (coachSableLogin == "true") jvmArgs("-Dcoach.sableLogin=true")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "LearningCoach"
            packageVersion = releaseVersion
            vendor = "LearningCoach"
            modules("java.sql")
            windows {
                // Same artwork as Window(icon = AppIcon.window()); the ICO carries 16..256 so the
                // installer, exe, Start menu shortcut and taskbar pick a pre-rendered size instead
                // of scaling one bitmap. See docs/design/desktop-polish.md section 2.
                iconFile.set(project.file("icons/LearningCoach.ico"))
                // Per-user so the installer needs no administrator prompt on the target PC.
                // upgradeUuid stays fixed: a newer MSI replaces program files in place and
                // leaves the user's ~/Coach data (ledger copy, token, state) untouched.
                perUserInstall = true
                shortcut = true
                menuGroup = "LearningCoach"
                upgradeUuid = "6f1b0a52-6c4d-4d3f-9a1e-2b7c5d84e910"
            }
        }
    }
}
tasks.withType<JavaExec>().configureEach {
    providers.gradleProperty("coach.autoExitSeconds").orNull?.let { systemProperty("coach.autoExitSeconds", it) }
}

tasks.test {
    if (providers.gradleProperty("coach.nativeOcr").orNull == "true") systemProperty("coach.nativeOcr", "true")
    if (providers.gradleProperty("coach.nativeFormats").orNull == "true") systemProperty("coach.nativeFormats", "true")
    if (providers.gradleProperty("coach.explainLiveWindow").orNull == "true") systemProperty("coach.explainLiveWindow", "true")
    providers.gradleProperty("coach.liveBookConnection").orNull?.let { systemProperty("coach.liveBookConnection", it) }
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

// currentOs selects Skiko from the build JVM. Ship the OCR payload for that same JVM.
if (System.getProperty("os.name").startsWith("Windows") &&
    System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) {
    sourceSets.main { resources.exclude("ocr/windows-x64.zip", "ocr/sha256.txt", "ocr/NOTICE.md") }
    tasks.processResources { from("src/windowsArm64/resources") }
    // Exercise the packaged application jar, including its selected DLLs and models.
    val verifyArm64Ocr by tasks.registering(Test::class) {
        dependsOn(tasks.testClasses, tasks.jar)
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = files(sourceSets.test.get().output, tasks.jar) + configurations.testRuntimeClasspath.get()
        filter { includeTestsMatching("*.BookOcrTest.actualBundledEngineImportsThreeScansJpegAndPdfOffline") }
        testLogging.showStandardStreams = true
    }
    tasks.matching { it.name == "packageMsi" }.configureEach { dependsOn(verifyArm64Ocr) }
}

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

// Manual external acceptance only. Keep the driver out of shipped application jars.
tasks.register<Sync>("externalFriendDriver") {
    group = "verification"
    dependsOn(tasks.testClasses, configurations.named("testRuntimeClasspath"))
    from(sourceSets.test.get().output) { into("classes") }
    val jars = sourceSets.test.get().runtimeClasspath.filter { it.extension == "jar" }.toList()
    val names = jars.mapIndexed { index, jar -> "%04d-%s".format(index, jar.name) }
    jars.forEachIndexed { index, jar ->
        from(jar) { into("lib"); rename { names[index] } }
    }
    from(sourceSets.main.get().output) { into("classes") }
    into(layout.buildDirectory.dir("external-friend-driver"))
    doLast {
        // Preserve Gradle's classpath order, including distinct jars with the same basename.
        destinationDir.resolve("classpath.txt").writeText((listOf("classes") + names.map { "lib/$it" }).joinToString("\n"))
    }
}

// Development controls are compiled separately and absent from application jars.
val smoke by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
tasks.register<JavaExec>("smokeRun") {
    group = "verification"
    description = "Run the real Windows textbook content with private loopback controls"
    dependsOn("smokeRuntime")
    classpath = smoke.runtimeClasspath
    mainClass.set("com.coach.desktop.smoke.SmokeMain")
}
val applicationJar = tasks.named<Jar>("jar")
tasks.register("verifySmokeIsolation") {
    group = "verification"
    dependsOn(applicationJar)
    doLast {
        check(zipTree(applicationJar.get().archiveFile.get().asFile).matching {
            include("com/coach/desktop/smoke/**")
        }.isEmpty) { "Development smoke controls must not enter the shipped application jar" }
    }
}

val smokeRuntime by tasks.registering(Sync::class) {
    group = "verification"
    dependsOn(smoke.classesTaskName, "verifySmokeIsolation")
    from(sourceSets.main.get().output) { into("classes") }
    from(smoke.output) { into("classes") }
    val jars = smoke.runtimeClasspath.filter { it.extension == "jar" }.toList()
    val names = jars.mapIndexed { index, jar -> "%04d-%s".format(index, jar.name) }
    jars.forEachIndexed { index, jar ->
        from(jar) { into("lib"); rename { names[index] } }
    }
    into(layout.buildDirectory.dir("outputs/smoke-runtime"))
    // A receipt describes the actual checkout even when bytecode is up to date.
    outputs.upToDateWhen { false }
    doLast {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", rootDir.absolutePath) + args)
                .redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { "Cannot identify smoke runtime source" }
            return text.trim()
        }
        destinationDir.resolve("classpath.txt").writeText(
            (listOf("classes") + names.map { "lib/$it" }).joinToString("\n"))
        destinationDir.resolve("revision.txt").writeText(git("rev-parse", "HEAD") + "\n")
        destinationDir.resolve("dirty.txt").writeText(git("status", "--porcelain") + "\n")
    }
}
// The Mac heavy caller can prepare the same Windows runtime before test starts.
tasks.test {
    if (providers.gradleProperty("coach.smoke.prepare").orNull == "true") dependsOn(smokeRuntime)
}
