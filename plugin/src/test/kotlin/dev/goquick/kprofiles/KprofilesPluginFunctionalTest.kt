package dev.goquick.kprofiles

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class KprofilesPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `overlay order applies last wins`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null)
        writeSharedFile("files/info.txt", "shared")
        writeOverlayFile("theme-blue", "files/info.txt", "theme-blue")
        writeOverlayFile("brand-alpha", "files/info.txt", "brand-alpha")

        val result = gradle("kprofilesOverlayForCommonMain", "-Pkprofiles.profiles=theme-blue,brand-alpha")
        assertEquals(TaskOutcome.SUCCESS, result.task(":kprofilesOverlayForCommonMain")?.outcome)

        val merged = projectDir.resolve("build/generated/kprofiles/commonMain/composeResources/files/info.txt")
        assertTrue(Files.exists(merged))
        assertEquals("brand-alpha", Files.readString(merged))
    }

    @Test
    fun `strict Android names fail verification`() {
        setupSimpleProject(strictAndroid = true, collisionPolicy = null)
        writeSharedFile("drawable/Bad-Name.png", "bad")

        val result = gradleFail("kprofilesVerify")
        assertTrue(result.output.contains("may be invalid for Android packaging"))
    }

    @Test
    fun `collision policy fail stops build`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = CollisionPolicy.FAIL)
        writeSharedFile("files/info.txt", "shared")
        writeOverlayFile("theme-blue", "files/info.txt", "theme-blue")
        writeOverlayFile("brand-alpha", "files/info.txt", "brand-alpha")

        val result = gradleFail("kprofilesOverlayForCommonMain", "-Pkprofiles.profiles=theme-blue,brand-alpha")
        assertTrue(result.output.contains("collision on 'info.txt'"))
    }

    @Test
    fun `missing overlay logs info`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null)
        writeSharedFile("files/info.txt", "shared")

        val result = gradle("kprofilesOverlayForCommonMain", "-Pkprofiles.profiles=brand-alpha", "--info")
        assertTrue(result.output.contains("overlay 'profile:brand-alpha' not found"))
    }

    @Test
    fun `unsupported folder is ignored`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null)
        writeSharedFile("files/info.txt", "shared")
        writeFile("overlays/profile/theme-blue/composeResources/sounds/clip.txt", "hello")

        gradle("kprofilesOverlayForCommonMain", "-Pkprofiles.profiles=theme-blue")

        val generated = projectDir.resolve("build/generated/kprofiles/commonMain/composeResources/sounds/clip.txt")
        assertTrue(Files.notExists(generated))
    }

    @Test
    fun `diagnostics report file origins`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null)
        writeSharedFile("files/info.txt", "shared")
        writeOverlayFile("theme-blue", "files/info.txt", "theme-blue")

        val result = gradle("kprofilesDiag", "-Pkprofiles.profiles=theme-blue")
        assertTrue(result.output.contains("files/info.txt -> profile:theme-blue"))
    }

    @Test
    fun `platform overlays apply before profiles for jvm`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null
        )
        writeSharedFile("values/strings.xml", """<?xml version=\"1.0\"?><resources><string name=\"marker\">shared</string></resources>""")
        writePlatformOverlay("jvm", "values/strings.xml", """<?xml version=\"1.0\"?><resources><string name=\"marker\">desktop-layer</string></resources>""")
        writeOverlayFile("theme-blue", "values/strings.xml", """<?xml version=\"1.0\"?><resources><string name=\"marker\">profile-layer</string></resources>""")

        // Without profiles, platform layer wins
        val noProfile = gradle("kprofilesOverlayForCommonMain")
        assertEquals(TaskOutcome.SUCCESS, noProfile.task(":kprofilesOverlayForCommonMain")?.outcome)
        var generated = projectDir.resolve("build/generated/kprofiles/commonMain/composeResources/values/strings.xml")
        val firstPass = Files.readString(generated)
        assertTrue(firstPass.contains("desktop-layer"))

        // With profiles, profile overlay wins last
        gradle("kprofilesOverlayForCommonMain", "-Pkprofiles.profiles=theme-blue")
        generated = projectDir.resolve("build/generated/kprofiles/commonMain/composeResources/values/strings.xml")
        val secondPass = Files.readString(generated)
        assertTrue(secondPass.contains("profile-layer"))
    }

    @Test
    fun `print task lists context vector`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null
        )
        writeSharedFile("files/info.txt", "shared")
        writePlatformOverlay("jvm", "files/info.txt", "platform")

        val result = gradle("kprofilesPrintEffective", "--info")
        assertTrue(result.output.contains("platform=jvm"))
    }

    @Test
    fun `buildType layer participates in overlay order`() {
        val project = ProjectBuilder.builder().build()
        val overlayTask = project.tasks.create("overlayTest", KprofilesOverlayTask::class.java)
        val preparedDir = project.layout.buildDirectory.dir("testPrepared").get()
        val platformDir = Files.createTempDirectory("platformLayer").toFile()
        val buildTypeDir = Files.createTempDirectory("buildLayer").toFile()
        val profileDir = Files.createTempDirectory("profileLayer").toFile()

        fun writeLayer(dir: File, value: String) {
            val valuesDir = File(dir, "values").apply { mkdirs() }
            File(valuesDir, "strings.xml").writeText(
                """<?xml version=\"1.0\"?><resources><string name=\"marker\">$value</string></resources>"""
            )
        }

        writeLayer(platformDir, "platform")
        writeLayer(buildTypeDir, "buildType")
        writeLayer(profileDir, "profile")

        val sharedDir = project.layout.projectDirectory.dir("shared").also { it.asFile.mkdirs() }
        overlayTask.preparedDirectory.set(preparedDir)
        overlayTask.sharedInputDirectory.set(sharedDir)
        overlayTask.profiles.set(listOf("theme-blue"))
        overlayTask.platformFamily.set("jvm")
        overlayTask.buildType.set("debug")
        overlayTask.allowedTopLevelDirs.set(setOf("values"))
        overlayTask.collisionPolicy.set(project.objects.property(CollisionPolicy::class.java).apply {
            set(CollisionPolicy.WARN)
        })
        overlayTask.logDiagnostics.set(false)
        overlayTask.overlayPathStrings.set(listOf(platformDir.absolutePath, buildTypeDir.absolutePath, profileDir.absolutePath))
        overlayTask.overlayLabels.set(listOf("platform:jvm", "buildType:debug", "profile:theme-blue"))
        overlayTask.overlayDirs.from(project.files(platformDir, buildTypeDir, profileDir))

        overlayTask.overlay()

        val merged = preparedDir.asFile.resolve("values/strings.xml")
        assertTrue(merged.exists())
        assertTrue(merged.readText().contains("profile"), "profile layer should win last")
    }

    private fun setupSimpleProject(
        strictAndroid: Boolean,
        collisionPolicy: CollisionPolicy?,
        extraProfilesBlock: String = "",
        kotlinBlock: String? = null,
        addDefaultFamily: Boolean = true
    ) {
        writeFile("settings.gradle.kts", "rootProject.name = \"functional\"")
        val configLines = mutableListOf<String>()
        if (strictAndroid) configLines += "    strictAndroidNames.set(true)"
        collisionPolicy?.let { configLines += "    collisionPolicy.set(CollisionPolicy.${it.name})" }
        extraProfilesBlock
            .takeIf { it.isNotBlank() }
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { configLines += "    $it" }
        val configBody = configLines.joinToString("\n")

        val kotlinSection = kotlinBlock ?: """
            kotlin {
                jvm()
            }
        """.trimIndent()

        writeFile(
            "build.gradle.kts",
            """
            import dev.goquick.kprofiles.CollisionPolicy

            plugins {
                kotlin("multiplatform") version "2.2.21"
                id("dev.goquick.kprofiles")
            }

            repositories {
                mavenCentral()
            }

            $kotlinSection

            kprofiles {
${if (configBody.isBlank()) "" else configBody + "\n"}
            }
            """.trimIndent()
        )

        if (addDefaultFamily) {
            val props = projectDir.resolve("gradle.properties")
            Files.writeString(props, "kprofiles.family=jvm\n")
        }
    }

    @Test
    fun `cli stack suppresses default log`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null, extraProfilesBlock = "defaultProfiles.set(listOf(\"theme-green\"))")
        writeSharedFile("files/info.txt", "shared")

        val result = gradle(
            args = arrayOf("kprofilesPrintEffective", "-Pkprofiles.profiles=theme-blue", "--info"),
            environment = emptyMap()
        )
        assertTrue(result.output.contains("stack source = CLI"))
        assertTrue("defaultProfiles" !in result.output)
    }

    @Test
    fun `env stack suppresses default log`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null, extraProfilesBlock = "defaultProfiles.set(listOf(\"theme-green\"))")
        writeSharedFile("files/info.txt", "shared")

        val result = gradle(
            args = arrayOf("kprofilesPrintEffective", "--info"),
            environment = mapOf("KPROFILES_PROFILES" to "brand-alpha")
        )
        assertTrue(result.output.contains("stack source = ENV"))
        assertTrue("defaultProfiles" !in result.output)
    }

    @Test
    fun `default profiles emit info log`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null, extraProfilesBlock = "defaultProfiles.set(listOf(\"theme-blue\"))")
        writeSharedFile("files/info.txt", "shared")

        val result = gradle("kprofilesPrintEffective", "--info")
        assertTrue(result.output.contains("stack source = DEFAULT"))
        assertTrue(result.output.contains("using defaultProfiles=[theme-blue]"))
    }

    @Test
    fun `shared only emits info log`() {
        setupSimpleProject(strictAndroid = false, collisionPolicy = null)
        writeSharedFile("files/info.txt", "shared")

        val result = gradle("kprofilesPrintEffective", "--info")
        assertTrue(result.output.contains("stack source = NONE"))
        assertTrue(result.output.contains("Using shared resources only."))
    }

    @Test
    fun `fails when multiple families without selector`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null,
            kotlinBlock = """
                kotlin {
                    jvm()
                    iosArm64()
                }
            """.trimIndent(),
            addDefaultFamily = false
        )
        writeSharedFile("files/info.txt", "shared")

        val failure = gradleFail("kprofilesOverlayForCommonMain")
        val output = failure.output
        assertTrue(
            output.contains("multiple platform families") ||
                output.contains("unable to infer platform family"),
            "Expected platform-family failure but got: $output"
        )
    }

    @Test
    fun `family can be provided via env`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null,
            kotlinBlock = """
                kotlin {
                    jvm()
                    iosArm64()
                }
            """.trimIndent(),
            addDefaultFamily = false
        )
        writeSharedFile("files/info.txt", "shared")
        writePlatformOverlay("jvm", "files/info.txt", "platform-jvm")
        writePlatformOverlay("ios", "files/info.txt", "platform-ios")

        val result = gradle(
            args = arrayOf("kprofilesOverlayForCommonMain"),
            environment = mapOf("KPROFILES_FAMILY" to "jvm")
        )
        val merged = projectDir.resolve("build/generated/kprofiles/commonMain/composeResources/files/info.txt")
        assertTrue(Files.exists(merged))
        assertTrue(Files.readString(merged).contains("platform-jvm"))
    }

    @Test
    fun `config generation merges overlays`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null,
            extraProfilesBlock = """
                config.enabled.set(true)
                config.packageName.set("dev.test.config")
                config.typeName.set("AppConfig")
            """.trimIndent()
        )
        writeConfig("src/commonMain/config/app.yaml", "apiBaseUrl: \"https://prod\"\nretryCount: 3\n")
        writeConfig("overlays/profile/theme-blue/config/app.yaml", "apiBaseUrl: \"https://blue\"\nfeatureX: true\n")

        gradle("kprofilesGenerateConfigForCommonMain", "-Pkprofiles.profiles=theme-blue")

        val generated = projectDir.resolve("build/generated/kprofiles/config/commonMain/src/AppConfig.kt")
        assertTrue(Files.exists(generated))
        val content = Files.readString(generated)
        assertTrue(content.contains("https://blue"))
        assertTrue(content.contains("featureX"))
    }

    @Test
    fun `config print shows merged values`() {
        setupSimpleProject(
            strictAndroid = false,
            collisionPolicy = null,
            extraProfilesBlock = """
                config.enabled.set(true)
            """.trimIndent()
        )
        writeConfig("src/commonMain/config/app.yaml", "flag: false\n")
        writeConfig("overlays/profile/theme-blue/config/app.yaml", "flag: true\n")

        val result = gradle("kprofilesConfigPrint", "-Pkprofiles.profiles=theme-blue")
        assertTrue(result.output.contains("config flag = true"))
    }

    private fun writeSharedFile(relative: String, content: String) {
        writeFile("src/commonMain/composeResources/$relative", content)
    }

    private fun writeOverlayFile(profile: String, relative: String, content: String) {
        writeFile("overlays/profile/$profile/composeResources/$relative", content)
    }

    private fun writePlatformOverlay(layer: String, relative: String, content: String) {
        writeFile("overlays/platform/$layer/composeResources/$relative", content)
    }

    private fun writeBuildTypeOverlay(layer: String, relative: String, content: String) {
        writeFile("overlays/buildType/$layer/composeResources/$relative", content)
    }

    private fun writeFile(relative: String, content: String) {
        val path = projectDir.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun writeConfig(relative: String, content: String) = writeFile(relative, content)

    private fun gradle(vararg args: String): org.gradle.testkit.runner.BuildResult =
        gradle(args = args, environment = emptyMap())

    private fun gradle(
        args: Array<out String>,
        environment: Map<String, String>
    ): org.gradle.testkit.runner.BuildResult {
        var runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(*withStacktrace(args))
            .withPluginClasspath()
            .forwardOutput()
        if (environment.isNotEmpty()) {
            runner = runner.withEnvironment(environment)
        }
        return try {
            runner.build()
        } catch (failure: UnexpectedBuildFailure) {
            System.err.println("--- build output begin ---")
            System.err.println(failure.buildResult.output)
            System.err.println("--- build output end ---")
            throw failure
        }
    }

    private fun gradleFail(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withArguments(*withStacktrace(args))
        .withPluginClasspath()
        .forwardOutput()
        .buildAndFail()

    private fun withStacktrace(args: Array<out String>): Array<String> =
        (args.toList() + "--stacktrace").toTypedArray()
}
