plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    alias(libs.plugins.vanniktechMavenPublish)
}

group = "dev.goquick.kprofiles"
version = "0.1.5"

dependencies {
    implementation(gradleApi())
    implementation(libs.kotlinGradlePluginApi)
    implementation(libs.kotlinGradlePlugin)
    compileOnly(libs.composeGradlePlugin)
    implementation(libs.snakeyaml)
    implementation(libs.jacksonDatabind)

    testImplementation(libs.kotlinTest)
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
