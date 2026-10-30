plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
    application
}
dependencies {
    implementation(project(":shared:client"))
    testImplementation(kotlin("test"))
    // §14 #7：同一负载下 Netty vs CIO 实测（只跑桩桥，不调真实 CLI）
    testImplementation(libs.ktor.cio)
    testImplementation(libs.sqldelight.driver)
    testImplementation(libs.sqlite.jdbc)
    implementation(project(":server:core"))
    implementation(project(":server:ai"))
    implementation(libs.ktor.core)
    implementation(libs.ktor.netty)
    implementation(libs.ktor.websockets)
    implementation(libs.coroutines.core)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass = "com.coach.server.MainKt" }
tasks.test {
    dependsOn(tasks.installDist)
    systemProperty("coach.serverBinary", layout.buildDirectory.file("install/app/bin/app").get().asFile.absolutePath)
}
