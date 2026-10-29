plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
    application
}
dependencies {
    implementation(project(":server:core"))
    implementation(libs.ktor.core)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.websockets)
    implementation(libs.coroutines.core)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass = "com.coach.server.MainKt" }
