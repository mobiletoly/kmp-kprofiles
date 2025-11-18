plugins {
    kotlin("jvm") version "2.2.21"
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "dev.goquick.kprofiles"
version = "0.1.4"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.21")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
    compileOnly("org.jetbrains.compose:compose-gradle-plugin:1.9.3")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("kprofilesPlugin") {
            id = "dev.goquick.kprofiles"
            displayName = "KProfiles Resource Overlay Plugin"
            description = "Kprofiles plugin to overlay Compose resources"
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

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "plugin", version.toString())

    pom {
        name = "KMP KProfiles Plugin"
        description = "KMP KProfiles Plugin to overlay Compose resources"
        inceptionYear = "2025"
        url = "https://github.com/mobiletoly/kmp-kprofiles/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "mobiletoly"
                name = "Toly Pochkin"
                url = "https://github.com/mobiletoly"
            }
        }
        scm {
            url = "https://github.com/mobiletoly/kmp-kprofiles"
            connection = "scm:git:git://github.com/mobiletoly/kmp-kprofiles.git"
            developerConnection = "scm:git:git://github.com/mobiletoly/kmp-kprofiles.git"
        }
    }
}
