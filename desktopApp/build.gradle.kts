import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    id("coach.jvm")
}
dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:client"))
    implementation(libs.sqldelight.driver)
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
    implementation(libs.material3)
    testImplementation(kotlin("test"))
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
            targetFormats(TargetFormat.Dmg)
            packageName = "Coach"
            packageVersion = "1.0.0"
            modules("java.sql")
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
