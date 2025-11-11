plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
}

group = "dev.goquick.kprofiles"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    runtimeOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:1.9.3")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("kprofilesPlugin") {
            id = "dev.goquick.kprofiles"
            implementationClass = "dev.goquick.kprofiles.KprofilesPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
