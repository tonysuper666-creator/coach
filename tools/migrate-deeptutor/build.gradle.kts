plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
    application
}
dependencies {
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test-junit"))
}
application { mainClass = "com.coach.migrate.MainKt" }
