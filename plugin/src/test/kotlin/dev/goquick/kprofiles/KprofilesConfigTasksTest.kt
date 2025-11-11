package dev.goquick.kprofiles

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
                "timeoutMs" to 5000L,
                "pi" to 3.1415
            )
        )
        assertTrue(source.contains("const val apiBaseUrl: String = \"https://api.example.com\""))
        assertTrue(source.contains("const val featureX: Boolean = true"))
        assertTrue(source.contains("const val retryCount: Int = 3"))
        assertTrue(source.contains("const val timeoutMs: Long = 5000L"))
        assertTrue(source.contains("const val pi: Double = 3.1415"))
    }

    @Test
    fun `non finite doubles remain val`() {
        val source = generateConfigSource(
            projectName = "nonFinite",
            data = mapOf(
                "nanValue" to Double.NaN,
                "posInf" to Double.POSITIVE_INFINITY,
                "negInf" to Double.NEGATIVE_INFINITY
            )
        )
        assertTrue(source.contains("val nanValue: Double = Double.NaN"))
        assertTrue(source.contains("val posInf: Double = Double.POSITIVE_INFINITY"))
        assertTrue(source.contains("val negInf: Double = Double.NEGATIVE_INFINITY"))
        assertFalse(source.contains("const val nanValue"))
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

    private fun write(project: org.gradle.api.Project, relative: String, content: String): File {
        val file = File(project.projectDir, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun generateConfigSource(
        projectName: String,
        data: Map<String, Any?>,
        preferConst: Boolean = true
    ): String {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempDir.resolve(projectName))
            .build()
        val merged = File(project.projectDir, "merged.json").apply {
            parentFile.mkdirs()
            mapper.writeValue(this, data)
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
}
