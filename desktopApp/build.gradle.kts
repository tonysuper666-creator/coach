import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
import java.time.LocalDate
import java.time.ZoneOffset
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
    implementation("org.apache.pdfbox:jbig2-imageio:3.0.4")
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
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
val coachFriendUrl = providers.gradleProperty("coach.friendUrl")
    .orElse(localConfig.getProperty("coach.friendUrl", "")).get().trim()
require(coachFriendUrl.isEmpty() || coachFriendUrl.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/?"))) {
    "Invalid private friend service origin"
}
// 「用 Sable 账号登录」入口：不传时 Windows 收起、Mac 照旧（TON-1760）。候选构建时显式传
// -Pcoach.sableLogin=true|false 决定；不从 local.properties 读，免得哪台机器的旧配置悄悄改了包。
val coachSableLogin = providers.gradleProperty("coach.sableLogin").orNull?.trim()?.lowercase()
require(coachSableLogin == null || coachSableLogin == "true" || coachSableLogin == "false") {
    "coach.sableLogin must be true or false"
}
compose.desktop {
    application {
        mainClass = "com.coach.desktop.MainKt"
        if (System.getProperty("os.name").startsWith("Windows") && providers.gradleProperty("coach.release").orNull == "true") {
            jvmArgs("-Dcoach.learningSync=true")
        }
        if (coachFriendUrl.isNotEmpty()) jvmArgs("-Dcoach.friendUrl=$coachFriendUrl")
        if (coachCourse.isNotEmpty()) jvmArgs("-Dcoach.course=$coachCourse")
        if (coachSableLogin != null) jvmArgs("-Dcoach.sableLogin=$coachSableLogin")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "LearningCoach"
            packageVersion = releaseVersion
            vendor = "LearningCoach"
            // The ARM installed-app smoke appends -javaagent after install and needs java.instrument
            // in the bundled runtime. Only the Windows ARM64 build without the private friend URL
            // (the smoke candidate) includes it; x64 and the private ARM64 release keep the default set.
            val smokeAgent = System.getProperty("os.arch") == "aarch64" &&
                providers.gradleProperty("coach.friendUrl").orNull.isNullOrBlank()
            if (smokeAgent) modules("java.sql", "java.instrument") else modules("java.sql")
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
    // 测试不借用这台机器上的配对：默认讲解器读 user.home 下的 Coach/client，开发机上有真实配对时，
    // 合成教材会被送去 Coach 服务分节、改写章节标题（结果随 AI 变、还花真实额度）。
    // 普通测试一律放进构建目录里的空 home；显式打开的真机 / 真服务验收保持原样。
    val liveRun = listOf(
        "coach.liveBookConnection", "coach.explainLiveWindow", "coach.startupLoginLiveWindow", "coach.ai1.soakSeconds",
        "coach.w1aTocBench", "coach.w1aSample1", "coach.w1aGroundTruth", "coach.w1aSample3Paper", "coach.w1aSample3Slides",
        "coach.w1aLiveWindow", "coach.w1aBookPath", "coach.w1aOutDir",
    ).any { System.getProperty(it) != null || providers.gradleProperty(it).isPresent } ||
        listOf("COACH_AI3_LIVE_EVAL", "COACH_AI4_LIVE_EVAL").any { !System.getenv(it).isNullOrBlank() }
    if (!liveRun) {
        val testHome = layout.buildDirectory.dir("test-home").get().asFile
        doFirst { testHome.deleteRecursively(); testHome.mkdirs() }
        systemProperty("user.home", testHome.absolutePath)
        // Skiko 的本机库仍解到原来的位置，不在空 home 里每次重解。
        systemProperty("skiko.data.path", File(System.getProperty("user.home"), ".skiko").absolutePath)
    }
    if (providers.gradleProperty("coach.nativeOcr").orNull == "true") systemProperty("coach.nativeOcr", "true")
    if (providers.gradleProperty("coach.nativeFormats").orNull == "true") systemProperty("coach.nativeFormats", "true")
    if (providers.gradleProperty("coach.explainLiveWindow").orNull == "true") systemProperty("coach.explainLiveWindow", "true")
    if (providers.gradleProperty("coach.startupLoginLiveWindow").orNull == "true") systemProperty("coach.startupLoginLiveWindow", "true")
    providers.gradleProperty("coach.liveBookConnection").orNull?.let { systemProperty("coach.liveBookConnection", it) }
    providers.gradleProperty("coach.ai1.soakSeconds").orNull?.let { systemProperty("coach.ai1.soakSeconds", it) }
    providers.gradleProperty("coach.ai1.reportOut").orNull?.let { systemProperty("coach.ai1.reportOut", it) }
    // 真书阶段读 COACH_IMPORT_PHASE。不把这次成功记成可复用，否则换阶段会被 Gradle 跳过。
    if (!System.getenv("COACH_IMPORT_PHASE").isNullOrBlank()) outputs.upToDateWhen { false }
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
val coachVersionDir = layout.buildDirectory.dir("generated/coach-version")
val buildDateUtc = LocalDate.now(ZoneOffset.UTC).toString()
val writeCoachVersion by tasks.registering {
    inputs.property("version", releaseVersion)
    inputs.property("buildDateUtc", buildDateUtc)
    outputs.dir(coachVersionDir)
    doLast {
        val dir = coachVersionDir.get().asFile
        dir.mkdirs()
        dir.resolve("coach-build-date.txt").writeText(buildDateUtc)
        dir.resolve("coach-version.txt").writeText(releaseVersion)
    }
}
sourceSets.main { resources.srcDir(coachVersionDir) }
tasks.processResources { dependsOn(notesCore, writeCoachVersion) }

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
    for (key in listOf(
        "coach.w1aTocBench", "coach.w1aSample1", "coach.w1aGroundTruth",
        "coach.w1aSample3Paper", "coach.w1aSample3Slides",
        "coach.w1aLiveWindow", "coach.w1aBookPath", "coach.w1aOutDir",
    )) {
        (System.getProperty(key) ?: providers.gradleProperty(key).orNull)?.let { systemProperty(key, it) }
    }
}

// The local textbook workspace is also compiled into the Android host.
kotlin.sourceSets.getByName("main").kotlin.srcDir("../shared/books/src/main/kotlin")
sourceSets.getByName("main").java.srcDir("../shared/books/src/main/java")

// Counts-only structure baseline. Test sources can see internal book types, and this
// task is absent from the shipped application jar.
kotlin.sourceSets.getByName("test").kotlin.srcDir(rootProject.file("tools/reader-v2/structure-audit/src"))
kotlin.sourceSets.getByName("test").kotlin.srcDir(rootProject.file("tools/reader-v2/book-map-verify/src"))
tasks.register<JavaExec>("structureAudit") {
    group = "verification"
    description = "Import local books on the Windows path and write structure counts. The report stays outside the checkout."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.coach.desktop.books.StructureAuditMain")
    jvmArgs("-Xmx4g", "-Dfile.encoding=UTF-8")
}
// Held-out coverage. Paths are properties so Windows quoting stays intact.
// The report is refused inside a git checkout and contains no question text.
tasks.register<JavaExec>("examCoverage") {
    group = "verification"
    description = "Score a course bank against held-out questions. Writes point names and reason classes only."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.coach.desktop.books.ExamCoverageMainKt")
    jvmArgs("-Dfile.encoding=UTF-8")
    val examArgs = listOf("bank", "holdout", "out", "teaching", "textbook", "identified").mapNotNull { name ->
        val value = providers.gradleProperty("exam.$name").orNull ?: return@mapNotNull null
        listOf("--$name", value)
    }.flatten()
    args(examArgs)
}

// 路线 1 公开书库导入与客户端同源请求构造（TON-1977，仅测试源码集，不进正式安装包）。
tasks.register<JavaExec>("prepareBookStanceCorpus") {
    group = "verification"
    description = "Import public-corpus books through TextbookLibrary and emit client-identical explain/ask payloads."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.coach.desktop.books.BookStanceCorpusPrep")
    jvmArgs("-Xmx4g", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true")
    val stanceArgs = listOf(
        "corpus" to "--corpus",
        "questions" to "--questions",
        "cache" to "--cache",
        "entry" to "--entry",
        "mode" to "--mode",
        "only" to "--only",
        "set" to "--set",
        "forcedConfig" to "--forced-config",
        "noSeedForced" to "--no-seed-forced",
    ).mapNotNull { (prop, flag) ->
        val value = providers.gradleProperty("stance.$prop").orNull ?: return@mapNotNull null
        listOf(flag, value)
    }.flatten()
    args(stanceArgs)
}

tasks.register<JavaExec>("bookMapVerify") {
    group = "verification"
    description = "Prepare an isolated 10-book library for TON-1889 acceptance and audit map anchor highlight hits (counts only)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.coach.desktop.books.BookMapVerifyMain")
    jvmArgs("-Xmx4g", "-Dfile.encoding=UTF-8")
}

tasks.register<JavaExec>("literatureGoldAudit") {
    group = "verification"
    description = "Check local PDFs against page offsets and hashes; report counts only"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.getByName("test").runtimeClasspath
    mainClass.set("com.coach.desktop.books.LiteratureGoldAudit")
}
