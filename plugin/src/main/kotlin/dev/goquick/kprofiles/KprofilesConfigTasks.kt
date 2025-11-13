/*
 * Copyright 2025 Toly Pochkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.goquick.kprofiles

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@CacheableTask
abstract class KprofilesMergeConfigTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val inputFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    abstract val orderedInputPaths: ListProperty<String>

    @get:Input
    abstract val profileStack: Property<String>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val platformFamily: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    init {
        projectDirectory.convention(project.layout.projectDirectory)
    }

    @TaskAction
    fun merge() {
        val yaml = createYaml()
        val mapper = createObjectMapper()
        val rootDir = projectDirectory.asFile.get()
        val filesByPath = inputFiles.files.associateBy { relativePath(rootDir, it) }
        val merged = linkedMapOf<String, Any>()
        orderedInputPaths.get().forEach { relPath ->
            val file = filesByPath[relPath]
            if (file == null) {
                logger.info("Kprofiles: ordered input '$relPath' not found; skipping.")
                return@forEach
            }
            if (!file.exists()) return@forEach
            val parsed = parseYaml(file, yaml, rootDir)
            merged.putAll(parsed)
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writer(StandardCharsets.UTF_8).use { writer ->
                mapper.writerWithDefaultPrettyPrinter().writeValue(writer, merged)
            }
        }
    }

    private fun parseYaml(file: File, yaml: Yaml, rootDir: File): Map<String, Any> {
        val loaded = file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            yaml.load<Any?>(reader)
        } ?: return emptyMap()
        if (loaded !is Map<*, *>) {
            error("Kprofiles: config file '${relativePath(rootDir, file)}' must be a YAML map of key/value pairs.")
        }
        val result = linkedMapOf<String, Any>()
        loaded.forEach { (k, v) ->
            val key = k?.toString() ?: error("Kprofiles: config key in '${relativePath(rootDir, file)}' is null.")
            result[key] = convertValue(file, key, v, rootDir)
        }
        return result
    }

    private fun convertValue(file: File, key: String, value: Any?, rootDir: File): Any = when (value) {
        null -> failType(file, key, "null", rootDir)
        is String -> value
        is Boolean -> value
        is Int -> value
        is Long -> value
        is Short, is Byte -> (value as Number).toInt()
        is Double -> value
        is Float -> value.toDouble()
        is BigInteger -> {
            when {
                value.bitLength() <= 31 -> value.toInt()
                value.bitLength() <= 63 -> value.toLong()
                else -> failType(file, key, "integer(out-of-range)", rootDir)
            }
        }
        is BigDecimal -> value.toDouble()
        is Number -> value.toLong()
        else -> failType(file, key, value::class.java.simpleName ?: value::class.java.name, rootDir)
    }

    private fun failType(file: File, key: String, type: String, rootDir: File): Nothing {
        error(
            "Kprofiles: config key '$key' in '${relativePath(rootDir, file)}' uses unsupported type '$type'. " +
                "Allowed types: string, boolean, int, long, double."
        )
    }
}

@CacheableTask
abstract class KprofilesGenerateConfigTask @Inject constructor() : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedConfigFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val typeName: Property<String>

    @get:Input
    abstract val preferConstScalars: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val stackDescription: Property<String>

    @get:Input
    abstract val configSources: ListProperty<String>

    @TaskAction
    fun generate() {
        val mapper = createObjectMapper()
        val data: Map<String, Any?> = mapper.readValue(
            mergedConfigFile.get().asFile,
            object : TypeReference<Map<String, Any?>>() {}
        )
        val outputDirectory = outputDir.get().asFile.apply { mkdirs() }
        val file = File(outputDirectory, "${typeName.get()}.kt")
        file.parentFile.mkdirs()
        file.writer(StandardCharsets.UTF_8).use { writer ->
            writer.write(renderFile(data))
        }
    }

    private fun renderFile(values: Map<String, Any?>): String {
        val pkg = packageName.get()
        val type = typeName.get()
        val entries = values.entries.sortedBy { it.key }
        val preferConst = preferConstScalars.getOrElse(true)
        val stack = stackDescription.getOrElse("shared")
        val sources = configSources.orNull.orEmpty()
        val body = buildString {
            entries.forEach { (key, value) ->
                val propertyName = sanitizeIdentifier(key)
                val analyzed = analyzeValue(value)
                val keyword = if (preferConst && canBeConst(analyzed)) "const val" else "val"
                val typeSegment = if (analyzed.includeType) ": ${analyzed.kotlinType}" else ""
                append("    $keyword $propertyName$typeSegment = ${analyzed.literal}\n")
            }
        }
        return buildString {
            appendLine("// AUTO-GENERATED BY Kprofiles. DO NOT EDIT.")
            appendLine("package $pkg")
            appendLine()
            appendLine("// Stack: $stack")
            appendLine("// Sources:")
            if (sources.isEmpty()) {
                appendLine("//   (shared only)")
            } else {
                sources.forEach { source ->
                    appendLine("//   $source")
                }
            }
            appendLine("object $type {")
            append(body)
            appendLine("}")
        }
    }

    private fun sanitizeIdentifier(raw: String): String {
        var result = raw.replace("[^A-Za-z0-9_]".toRegex(), "_")
        if (result.isEmpty()) result = "value"
        if (result.first().isDigit()) result = "_${result}"
        if (result in KOTLIN_KEYWORDS) result = "_${result}"
        return result
    }

    private fun analyzeValue(value: Any?): AnalyzedValue = when (value) {
        is String -> analyzeStringValue(value)
        is Boolean -> AnalyzedValue("Boolean", value.toString(), ScalarKind.BOOLEAN, value)
        is Int -> AnalyzedValue("Int", value.toString(), ScalarKind.INT, value)
        is Long -> AnalyzedValue("Long", "${value}L", ScalarKind.LONG, value)
        is Double -> analyzeDouble(value)
        is Float -> analyzeDouble(value.toDouble())
        else -> AnalyzedValue("String", "\"${value?.toString()?.escapeForKotlin() ?: ""}\"", null, value)
    }

    private fun analyzeDouble(value: Double): AnalyzedValue {
        val literal = when {
            value.isNaN() -> "Double.NaN"
            value == Double.POSITIVE_INFINITY -> "Double.POSITIVE_INFINITY"
            value == Double.NEGATIVE_INFINITY -> "Double.NEGATIVE_INFINITY"
            else -> formatFiniteDouble(value)
        }
        return AnalyzedValue(
            kotlinType = "Double",
            literal = literal,
            scalarKind = ScalarKind.DOUBLE,
            numericValue = value
        )
    }

    private fun formatFiniteDouble(value: Double): String {
        val text = value.toString()
        return if (!text.contains('.') && !text.contains('E') && !text.contains('e')) {
            "$text.0"
        } else {
            text
        }
    }

    private fun canBeConst(analyzed: AnalyzedValue): Boolean = when (analyzed.scalarKind) {
        null -> false
        ScalarKind.STRING,
        ScalarKind.BOOLEAN,
        ScalarKind.INT,
        ScalarKind.LONG -> true
        ScalarKind.DOUBLE -> {
            val numeric = analyzed.numericValue as? Double ?: return false
            numeric.isFinite()
        }
    }

    private fun analyzeStringValue(raw: String): AnalyzedValue {
        if (raw.startsWith(VAL_PREFIX)) {
            val expression = raw.removePrefix(VAL_PREFIX).trimStart()
            require(expression.isNotBlank()) {
                "Kprofiles: '$VAL_PREFIX' requires a non-empty Kotlin expression."
            }
            return AnalyzedValue(
                kotlinType = "",
                literal = expression,
                scalarKind = null,
                numericValue = null,
                includeType = false
            )
        }
        parseEnvExpression(raw)?.let { return it }
        parsePropertyExpression(raw)?.let { return it }
        return AnalyzedValue("String", "\"${raw.escapeForKotlin()}\"", ScalarKind.STRING, raw)
    }

    private fun parseEnvExpression(raw: String): AnalyzedValue? {
        val optional = raw.startsWith(ENV_OPTIONAL_PREFIX)
        if (!optional && !raw.startsWith(ENV_PREFIX)) return null
        val prefix = if (optional) ENV_OPTIONAL_PREFIX else ENV_PREFIX
        val name = raw.removePrefix(prefix).trim()
        require(name.isNotBlank()) {
            "Kprofiles: '$prefix' requires an environment variable name."
        }
        val value = KprofilesEnv.get(name)
        if (value == null) {
            if (optional) {
                return AnalyzedValue("String", "\"\"", ScalarKind.STRING, "")
            } else {
                error("Kprofiles: environment variable '$name' is not defined but was requested via '$ENV_PREFIX'.")
            }
        }
        return AnalyzedValue("String", "\"${value.escapeForKotlin()}\"", ScalarKind.STRING, value)
    }

    private fun parsePropertyExpression(raw: String): AnalyzedValue? {
        val optional = raw.startsWith(PROP_OPTIONAL_PREFIX)
        if (!optional && !raw.startsWith(PROP_PREFIX)) return null
        val prefix = if (optional) PROP_OPTIONAL_PREFIX else PROP_PREFIX
        val name = raw.removePrefix(prefix).trim()
        require(name.isNotBlank()) {
            "Kprofiles: '$prefix' requires a Gradle property name."
        }
        val value = project.findProperty(name)?.toString()
        if (value == null) {
            if (optional) {
                return AnalyzedValue("String", "\"\"", ScalarKind.STRING, "")
            } else {
                error("Kprofiles: Gradle property '$name' is not defined but was requested via '$PROP_PREFIX'.")
            }
        }
        return AnalyzedValue("String", "\"${value.escapeForKotlin()}\"", ScalarKind.STRING, value)
    }

    private data class AnalyzedValue(
        val kotlinType: String,
        val literal: String,
        val scalarKind: ScalarKind?,
        val numericValue: Any?,
        val includeType: Boolean = true
    )

    private enum class ScalarKind { STRING, BOOLEAN, INT, LONG, DOUBLE }

    private fun String.escapeForKotlin(): String = buildString(length) {
        for (ch in this@escapeForKotlin) {
            append(
                when (ch) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> ch
                }
            )
        }
    }
}

@DisableCachingByDefault(because = "diagnostic logging")
abstract class KprofilesConfigPrintTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedConfigFile: RegularFileProperty

    @get:Input
    abstract val profileSource: Property<String>

    @get:Input
    abstract val profileStack: Property<String>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val platformFamily: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @TaskAction
    fun printConfig() {
        val mapper = createObjectMapper()
        val data: Map<String, Any?> = mapper.readValue(
            mergedConfigFile.get().asFile,
            object : TypeReference<Map<String, Any?>>() {}
        )
        val platformDescriptor = buildPlatformDescriptor(
            platformFamily.getOrElse(""),
            buildType.getOrElse("")
        )
        project.logger.lifecycle("Kprofiles: config stack source = ${profileSource.get()}, profiles = ${profileStack.get()}, $platformDescriptor")
        if (overlayLabels.orNull?.isNotEmpty() == true) {
            project.logger.lifecycle("Kprofiles: config overlays = ${overlayLabels.get().joinToString(", ")}")
        }
        if (data.isEmpty()) {
            project.logger.lifecycle("Kprofiles: merged config is empty.")
        } else {
            data.entries.sortedBy { it.key }.forEach { (key, value) ->
                project.logger.lifecycle("Kprofiles: config $key = $value")
            }
        }
    }
}

private fun buildPlatformDescriptor(family: String, buildType: String): String {
    val familyPart = if (family.isNotBlank()) "platform=$family" else "platform=none"
    val buildPart = if (buildType.isNotBlank()) "buildType=$buildType" else "buildType=none"
    return "$familyPart, $buildPart"
}

private const val VAL_PREFIX = "[=val]"
private const val ENV_PREFIX = "[=env]"
private const val ENV_OPTIONAL_PREFIX = "[=env?]"
private const val PROP_PREFIX = "[=prop]"
private const val PROP_OPTIONAL_PREFIX = "[=prop?]"

private fun createObjectMapper(): ObjectMapper =
    ObjectMapper().apply {
        enable(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS)
    }

private fun createYaml(): Yaml {
    val options = LoaderOptions().apply {
        isAllowDuplicateKeys = false
    }
    return Yaml(SafeConstructor(options))
}

internal object KprofilesEnv {
    @Volatile
    var resolver: (String) -> String? = { System.getenv(it) }

    fun get(name: String): String? = resolver(name)
}

private val KOTLIN_KEYWORDS = setOf(
    "package", "as", "typealias", "class", "this", "super", "val", "var", "fun", "for",
    "null", "true", "false", "is", "in", "throw", "return", "break", "continue", "object",
    "if", "try", "else", "while", "do", "when", "interface", "typeof"
)
