plugins {
    alias(libs.plugins.kotlin.jvm)
    id("coach.jvm")
    application
    alias(libs.plugins.sqldelight)
}
dependencies {
    implementation(libs.sqlite.jdbc)
    implementation(libs.sqldelight.driver) { exclude(group = "org.xerial", module = "sqlite-jdbc") }
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass = "com.coach.store.FtsSmokeKt" }
sqldelight {
    databases {
        create("FtsProbe") {
            packageName.set("com.coach.store.probe")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.4.0")
        }
    }
}
