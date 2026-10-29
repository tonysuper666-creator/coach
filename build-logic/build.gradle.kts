plugins { `java-gradle-plugin` }
gradlePlugin {
    plugins {
        create("coachJvm") {
            id = "coach.jvm"
            implementationClass = "CoachJvmPlugin"
        }
    }
}
