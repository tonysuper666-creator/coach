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
    exclude("**/LiveChatTest*", "**/TutorLiveTest*")
    dependsOn(tasks.installDist)
    systemProperty("coach.serverBinary", layout.buildDirectory.file("install/app/bin/app").get().asFile.absolutePath)
}

// Opt-in: real CLI calls and local course material, never part of test/check.
tasks.register<Test>("liveTest") {
    description = "Real 8791 bridge → Ktor → WS → shared client: first word, resume, cancel, interrupted stream"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/LiveChatTest*")
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    workingDir(rootProject.projectDir)
    testLogging { showStandardStreams = true }
}


// Explicit two-call real-CLI tutor state-machine walk; local private inputs and isolated ledger.
tasks.register<Test>("tutorLive") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/TutorLiveTest*")
    outputs.upToDateWhen { false }
    maxParallelForks = 1
    workingDir(rootProject.projectDir)
    testLogging { showStandardStreams = true }
}

// Explicit private progress-utterance replay; requires separately inspected local labels.
tasks.register<JavaExec>("progressReplay") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.coach.server.ProgressReplayKt")
}
