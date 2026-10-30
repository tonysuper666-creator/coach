plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
}

dependencies { api(project(":server:store")); implementation(libs.coroutines.core); testImplementation(kotlin("test")) }

/** Explicit opt-in to local private corpus; never packages or prints course content. */
tasks.register<JavaExec>("replay") {
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.coach.core.TutorReplayKt")
}
