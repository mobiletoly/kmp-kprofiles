package dev.goquick.kprofiles

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "diagnostic logging")
abstract class KprofilesPrintEffectiveTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDir: DirectoryProperty

    @get:Input
    abstract val profiles: ListProperty<String>

    @get:Input
    abstract val profileSource: Property<String>

    @get:Input
    abstract val overlayPaths: ListProperty<String>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val platformFamilies: ListProperty<String>

    @get:Input
    abstract val buildTypes: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val overlayDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val preparedDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    abstract val preparedPaths: ListProperty<String>

    @TaskAction
    fun printEffective() {
        val source = profileSource.get()
        val stack = profiles.orNull.orEmpty()
        project.logger.lifecycle("Kprofiles: shared dir = ${sharedDir.get().asFile.invariantPath()}")
        project.logger.lifecycle("Kprofiles: stack source = $source, profiles = ${stack.joinToString(", ")}")
        val families = platformFamilies.orNull.orEmpty()
        val builds = buildTypes.orNull.orEmpty()
        families.forEachIndexed { index, family ->
            val descriptor = describeContext(family, builds.getOrNull(index))
            project.logger.lifecycle(formatContextLog("sourceSet[$index]", descriptor))
        }
        overlayLabels.getOrElse(emptyList()).forEachIndexed { index, label ->
            val path = overlayPaths.get().getOrNull(index) ?: ""
            project.logger.lifecycle("Kprofiles: overlay[$index] $label -> ${File(path).invariantPath()}")
        }
        preparedPaths.get().forEach { path ->
            project.logger.lifecycle("Kprofiles: prepared dir = ${File(path).invariantPath()}")
        }
    }
}

@DisableCachingByDefault(because = "diagnostic logging")
abstract class KprofilesVerifyTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    @get:Input
    abstract val sourceSets: ListProperty<String>

    @get:Input
    abstract val profiles: ListProperty<String>

    @get:Input
    abstract val profileSource: Property<String>

    @get:Input
    abstract val overlayPaths: ListProperty<String>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val platformFamilies: ListProperty<String>

    @get:Input
    abstract val buildTypes: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDir: DirectoryProperty

    @get:Input
    abstract val allowedTopLevelDirs: SetProperty<String>

    @get:Input
    abstract val strictAndroidNames: Property<Boolean>

    @get:Input
    abstract val preparedDirPaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val overlayDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val preparedDirs: ConfigurableFileCollection = objects.fileCollection()

    @TaskAction
    fun verify() {
        val families = platformFamilies.orNull.orEmpty()
        val builds = buildTypes.orNull.orEmpty()
        project.logger.lifecycle(
            "Kprofiles: verify stack source = ${profileSource.get()}, profiles = ${profiles.get().joinToString(", ")}"
        )
        sourceSets.get().forEachIndexed { index, name ->
            val descriptor = describeContext(families.getOrNull(index), builds.getOrNull(index))
            project.logger.lifecycle(formatContextLog("sourceSet[$index:$name]", descriptor))
        }
        val allowed = allowedTopLevelDirs.get()
        val strict = strictAndroidNames.get()
        val shared = sharedDir.asFile.get()
        scanSourceRoot(shared, allowed, strict)

        val overlayPathsList = overlayPaths.get()
        val overlayLabelsList = overlayLabels.get()
        if (overlayLabelsList.size != overlayPathsList.size) {
            project.logger.warn(
                "Kprofiles: verify mismatch — overlay metadata incomplete (labels=${overlayLabelsList.size}, paths=${overlayPathsList.size})."
            )
        }
        overlayLabelsList.forEachIndexed { index, label ->
            val path = overlayPathsList.getOrNull(index) ?: return@forEachIndexed
            val dir = File(path)
            if (!dir.exists()) return@forEachIndexed
            project.logger.lifecycle("Kprofiles: inspecting overlay '$label' -> ${dir.invariantPath()}")
            scanSourceRoot(dir, allowed, strict)
        }

        val names = sourceSets.get()
        val preparedPaths = preparedDirPaths.get()
        if (names.size != preparedPaths.size) {
            project.logger.warn(
                "Kprofiles: verify mismatch — sourceSets=${names.size} preparedDirs=${preparedPaths.size}. Skipping verification."
            )
            return
        }

        names.forEachIndexed { index, name ->
            val dir = File(preparedPaths[index])
            if (!hasValuesXml(dir)) {
                project.logger.warn(
                    "Kprofiles: no 'values/*.xml' in ${dir.invariantPath()} (srcSet='$name'). 'Res.string.*' may be empty."
                )
            }
        }
    }

    private fun scanSourceRoot(root: File, allowed: Set<String>, strict: Boolean) {
        if (!root.exists()) return
        val rootPath = relativePath(project, root)
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "__MACOSX" }
            ?.forEach { top ->
                val name = top.name
                if (!matchesAllowedTopLevel(name, allowed)) {
                    project.logger.warn(
                        "Kprofiles: ignored '$name' under '$rootPath'. Allowed: ${describeAllowedFolders(allowed)}"
                    )
                } else {
                    enforceAndroidNames(top, name, strict)
                }
            }
    }

    private fun enforceAndroidNames(top: File, prefix: String, strict: Boolean) {
        top.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = top.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val relativePath = if (rel.isEmpty()) prefix else "$prefix/$rel"
            val baseName = file.name.substringBeforeLast('.', file.name)
            if (!ANDROID_NAME_REGEX.matches(baseName)) {
                val message = "Kprofiles: '$relativePath' may be invalid for Android packaging. Use lowercase, digits, underscore only."
                if (strict) {
                    throw IllegalStateException(message)
                } else {
                    project.logger.warn(message)
                }
            }
        }
    }

    private fun hasValuesXml(root: File): Boolean {
        if (!root.exists()) return false
        return root.walkTopDown().any { file ->
            file.isFile && file.parentFile?.name?.startsWith("values") == true && file.extension == "xml"
        }
    }
}

@DisableCachingByDefault(because = "diagnostic logging")
abstract class KprofilesDiagTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    @get:Input
    abstract val sourceSets: ListProperty<String>

    @get:Input
    abstract val profiles: ListProperty<String>

    @get:Input
    abstract val profileSource: Property<String>

    @get:Input
    abstract val overlayPaths: ListProperty<String>

    @get:Input
    abstract val preparedDirPaths: ListProperty<String>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val platformFamilies: ListProperty<String>

    @get:Input
    abstract val buildTypes: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val overlayDirs: ConfigurableFileCollection = objects.fileCollection()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val preparedDirs: ConfigurableFileCollection = objects.fileCollection()

    @TaskAction
    fun diag() {
        val stack = profiles.orNull.orEmpty()
        project.logger.lifecycle(
            "Kprofiles: diag stack source = ${profileSource.get()}, profiles = ${stack.joinToString(", ")}"
        )
        val families = platformFamilies.orNull.orEmpty()
        val builds = buildTypes.orNull.orEmpty()
        sourceSets.get().forEachIndexed { index, name ->
            val descriptor = describeContext(families.getOrNull(index), builds.getOrNull(index))
            project.logger.lifecycle("Kprofiles: diag context for '$name' = $descriptor")
        }
        val overlayPathsList = overlayPaths.get()
        val overlayLabelsList = overlayLabels.get()
        val overlayPairs: List<Pair<String, File?>> = overlayLabelsList.mapIndexed { index, label ->
            val file = overlayPathsList.getOrNull(index)?.let(::File)
            val rendered = file?.invariantPath() ?: "<missing>"
            project.logger.lifecycle("Kprofiles: overlay[$index] $label -> $rendered")
            label to file
        }

        val names = sourceSets.get()
        val preparedPaths = preparedDirPaths.get()
        if (names.size != preparedPaths.size) {
            project.logger.warn(
                "Kprofiles: diag mismatch — sourceSets=${names.size} preparedDirs=${preparedPaths.size}. Skipping diagnostics."
            )
            return
        }

        preparedPaths.forEachIndexed { index, path ->
            val dir = File(path)
            val sourceSet = names[index]
            if (!dir.exists()) return@forEachIndexed
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = dir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                val origin = resolveOrigin(relative, overlayPairs)
                project.logger.lifecycle(
                    "Kprofiles: $relative -> $origin -> ${file.length()}B (srcSet=$sourceSet)"
                )
            }
        }
    }

    private fun resolveOrigin(relative: String, pairs: List<Pair<String, File?>>): String {
        pairs.asReversed().forEach { (label, dir) ->
            if (dir != null && File(dir, relative).exists()) {
                return label
            }
        }
        return "shared"
    }
}

private fun File.invariantPath(): String =
    toPath().toAbsolutePath().normalize().toString().replace('\\', '/')

private val ANDROID_NAME_REGEX = "^[a-z][a-z0-9_]*$".toRegex()

private fun describeContext(family: String?, buildType: String?): String {
    val platform = if (!family.isNullOrBlank()) "platform=$family" else "platform=none"
    val build = if (!buildType.isNullOrBlank()) "buildType=$buildType" else "buildType=none"
    return "$platform, $build"
}

private fun formatContextLog(target: String, descriptor: String): String =
    "Kprofiles: $target context = $descriptor"
