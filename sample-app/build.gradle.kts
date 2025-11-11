plugins {
    base
}

description = "Aggregator project for the Compose Multiplatform sample app"

evaluationDependsOn(":sample-app:composeApp")

tasks.named("build") {
    dependsOn(":sample-app:composeApp:build")
}

tasks.named("clean") {
    dependsOn(":sample-app:composeApp:clean")
}
