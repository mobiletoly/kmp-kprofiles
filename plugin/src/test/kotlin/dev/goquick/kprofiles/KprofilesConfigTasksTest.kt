package dev.goquick.kprofiles

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KprofilesConfigTasksTest {

    @field:TempDir
    lateinit var tempDir: File

    private val mapper = ObjectMapper().apply {
        enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS)
    }

    @Test
    fun `merge handles big integers and decimals`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempDir.resolve("bigNumbers"))
            .build()

        val shared = write(project, "src/commonMain/config/app.yaml", """
            |small: 42
            |bigWithinInt: 2147483647
            |pi: 3.14159
        """.trimMargin())

        val profile = write(project, "overlays/profile/theme-blue/config/app.yaml", """
            |small: 7
            |feature: true
        """.trimMargin())

        val output = project.layout.buildDirectory.file("kprofiles/test/merged.json")

        val task = project.tasks.create("mergeConfig", KprofilesMergeConfigTask::class.java)
        task.inputFiles.from(shared, profile)
        task.orderedInputPaths.set(listOf(project.relativePath(shared), project.relativePath(profile)))
        task.profileStack.set("theme-blue")
        task.overlayLabels.set(listOf("shared", "profile:theme-blue"))
        task.platformFamily.set("jvm")
        task.buildType.set("")
        task.outputFile.set(output)

        task.merge()

        val tree = mapper.readTree(output.get().asFile)
        assertEquals(7, tree["small"].intValue())
        assertEquals(2147483647, tree["bigWithinInt"].intValue())
        assertEquals(3.14159, tree["pi"].doubleValue(), 0.0)
        assertEquals(true, tree["feature"].booleanValue())
    }

    @Test
    fun `duplicate keys inside single yaml fail`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempDir.resolve("duplicates"))
            .build()

        val badFile = write(project, "src/commonMain/config/app.yaml", """
            |value: 1
            |value: 2
        """.trimMargin())

        val task = project.tasks.create("mergeConfigDup", KprofilesMergeConfigTask::class.java)
        task.inputFiles.from(badFile)
        task.orderedInputPaths.set(listOf(project.relativePath(badFile)))
        task.profileStack.set("")
        task.overlayLabels.set(emptyList())
        task.platformFamily.set("ios")
        task.buildType.set("")
        task.outputFile.set(project.layout.buildDirectory.file("dup/merged.json"))

        assertThrows(org.yaml.snakeyaml.constructor.DuplicateKeyException::class.java) {
            task.merge()
        }
    }

    @Test
    fun `generated config uses const for scalars when enabled`() {
        val source = generateConfigSource(
            projectName = "constScalars",
            data = mapOf(
                "apiBaseUrl" to "https://api.example.com",
                "featureX" to true,
                "retryCount" to 3,
                "timeoutMs" to 5_000_000_000L,
                "pi" to 3.1415
            )
        )
        assertTrue(source.contains("const val apiBaseUrl: String = \"https://api.example.com\""))
        assertTrue(source.contains("const val featureX: Boolean = true"))
        assertTrue(source.contains("const val retryCount: Int = 3"))
        assertTrue(source.contains("const val timeoutMs: Long = 5000000000L"))
        assertTrue(source.contains("const val pi: Double = 3.1415"))
    }

    @Test
    fun `const scalars can be disabled`() {
        val source = generateConfigSource(
            projectName = "constDisabled",
            data = mapOf(
                "apiBaseUrl" to "https://api.example.com",
                "retryCount" to 7
            ),
            preferConst = false
        )
        assertFalse(source.contains("const val"))
        assertTrue(source.contains("val apiBaseUrl: String = \"https://api.example.com\""))
        assertTrue(source.contains("val retryCount: Int = 7"))
    }

    @Test
    fun `assign prefix renders raw expression`() {
        val source = generateConfigSource(
            projectName = "assignExpr",
            data = mapOf(
                "mainColor" to "[=val] androidx.compose.ui.graphics.Color(0xFFAABB)"
            )
        )
        assertTrue(source.contains("val mainColor = androidx.compose.ui.graphics.Color(0xFFAABB)"))
        assertFalse(source.contains("const val mainColor"))
        assertFalse(source.contains("mainColor:"))
    }

    @Test
    fun `env prefix injects environment variable`() {
        withEnvOverride("APP_SECRET", "shhh") {
            val source = generateConfigSource(
                projectName = "envExpr",
                data = mapOf("secret" to "[=env] APP_SECRET")
            )
            assertTrue(source.contains("const val secret: String = \"shhh\""))
        }
    }

    @Test
    fun `env optional prefix falls back to empty`() {
        withEnvOverride("OPTIONAL_VALUE", null) {
            val source = generateConfigSource(
                projectName = "envOptional",
                data = mapOf("optional" to "[=env?] OPTIONAL_VALUE")
            )
            assertTrue(source.contains("const val optional: String = \"\""))
        }
    }

    @Test
    fun `env prefix missing fails`() {
        withEnvOverride("MISSING_VALUE", null) {
            assertThrows(IllegalStateException::class.java) {
                generateConfigSource(
                    projectName = "envMissing",
                    data = mapOf("required" to "[=env] MISSING_VALUE")
                )
                Unit
            }
        }
    }

    @Test
    fun `prop prefix injects gradle property`() {
        val source = generateConfigSource(
            projectName = "propExpr",
            data = mapOf("apiBaseUrl" to "[=prop] API_URL"),
            gradleProps = mapOf("API_URL" to "https://from-prop")
        )
        assertTrue(source.contains("const val apiBaseUrl: String = \"https://from-prop\""))
    }

    @Test
    fun `prop optional prefix falls back to empty`() {
        val source = generateConfigSource(
            projectName = "propOptional",
            data = mapOf("optional" to "[=prop?] OPTIONAL_PROP")
        )
        assertTrue(source.contains("const val optional: String = \"\""))
    }

    @Test
    fun `prop prefix missing fails`() {
        assertThrows(IllegalStateException::class.java) {
            generateConfigSource(
                projectName = "propMissing",
                data = mapOf("required" to "[=prop] UNDEFINED_PROP")
            )
            Unit
        }
    }

    private fun write(project: org.gradle.api.Project, relative: String, content: String): File {
        val file = File(project.projectDir, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun generateConfigSource(
        projectName: String,
        data: Map<String, Any?>,
        preferConst: Boolean = true,
        gradleProps: Map<String, Any?> = emptyMap()
    ): String {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempDir.resolve(projectName))
            .build()
        val merged = File(project.projectDir, "merged.json").apply {
            parentFile.mkdirs()
            mapper.writeValue(this, data)
        }
        gradleProps.forEach { (k, v) ->
            project.extensions.extraProperties[k] = v
        }

        val task = project.tasks.create("generateConfig$projectName", KprofilesGenerateConfigTask::class.java)
        task.mergedConfigFile.set(project.layout.projectDirectory.file(project.relativePath(merged)))
        task.packageName.set("dev.test.config")
        task.typeName.set("GeneratedConfig")
        task.outputDir.set(project.layout.buildDirectory.dir("generated/config"))
        task.preferConstScalars.set(preferConst)

        task.generate()

        val outputFile = task.outputDir.get().asFile.resolve("GeneratedConfig.kt")
        return outputFile.readText()
    }

    private fun withEnvOverride(name: String, value: String?, block: () -> Unit) {
        val previous = KprofilesEnv.resolver
        KprofilesEnv.resolver = { key ->
            if (key == name) value else previous(key)
        }
        try {
            block()
        } finally {
            KprofilesEnv.resolver = previous
        }
    }
}
