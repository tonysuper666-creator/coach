plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
    application
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":server:core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "com.coach.ai.LiveHarnessKt"
}

/**
 * 出题与批改的真实端到端子集（验收者复跑用）。
 *
 * 需要桥副本 8791 已经在跑；语料从 `~/Coach/corpus/gradegen/` 读（仓库里不放课程原文、真题与他本人作答），
 * 结果与 `ai_calls` 也写回那里。桥不在、DeepTutor 正在用订阅、或命中期中黑名单时段时整批直接退出。
 */
tasks.register<JavaExec>("liveTest") {
    environment("COACH_TRAFFIC", "test")
    group = "verification"
    description = "live 出题/批改子集：1 个 KP（生成 + 盲复核）、植入错键题、真实作答逐字校验"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.coach.ai.LiveHarnessKt")
    args("liveTest")
    environment(
        "COACH_CORPUS_DIR",
        System.getenv("COACH_CORPUS_DIR") ?: "${System.getProperty("user.home")}/Coach/corpus/gradegen",
    )
    environment("COACH_BRIDGE_URL", System.getenv("COACH_BRIDGE_URL") ?: "http://127.0.0.1:8791")
}

/** Replay the existing private TON-1041 fixtures using a local HTTP stub; no subscription CLI runs. */
tasks.register<JavaExec>("plantedReplay") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.coach.ai.PlantedReplayKt")
}

// Mark automated traffic for call accounting.
tasks.withType<Test>().configureEach { environment("COACH_TRAFFIC", "test") }
/** Synchronous operator prewarm; never starts a hidden background process. */
tasks.register<JavaExec>("refillPool") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.coach.ai.PoolCommandKt")
    args(providers.gradleProperty("poolArgs").orElse("").get().split(" ").filter { it.isNotBlank() })
}

/** Private, explicit live replay of a previously reviewed constructed fixture. */
tasks.register<JavaExec>("gradeLedgerProbe") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.coach.ai.GradeLedgerProbeKt")
    environment("COACH_TRAFFIC", "test")
}
