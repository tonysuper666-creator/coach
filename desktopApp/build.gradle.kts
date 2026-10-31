import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
}
compose.desktop {
    application {
        mainClass = "com.coach.desktop.MainKt"
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

tasks.withType<JavaExec>().configureEach {
    for (key in listOf("coach.exportFormulas", "coach.capturePath")) providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
}
