plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
}

dependencies { api(project(":server:store")); implementation(libs.coroutines.core) }
