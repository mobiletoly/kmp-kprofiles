package dev.goquick.kprofiles

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@CacheableTask
abstract class KprofilesOverlayTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {

    @get:OutputDirectory
    abstract val preparedDirectory: DirectoryProperty

    @get:Internal
    abstract val profiles: ListProperty<String>

    @get:Input
    abstract val platformFamily: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val allowedTopLevelDirs: SetProperty<String>

    @get:Input
    abstract val collisionPolicy: Property<CollisionPolicy>

    @get:Internal
    abstract val logDiagnostics: Property<Boolean>

    @get:Input
    abstract val overlayLabels: ListProperty<String>

    @get:Input
    abstract val overlayPathStrings: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedInputDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val overlayDirs: ConfigurableFileCollection = objects.fileCollection()

    @TaskAction
    fun overlay() {
        val allowed = allowedTopLevelDirs.get()
        val policy = collisionPolicy.get()
        val diagnosticsEnabled = logDiagnostics.getOrElse(true)
        val overlayPaths = overlayPathStrings.get()
        val labels = overlayLabels.get()
        val overlayRoots = overlayPaths.map { project.layout.projectDirectory.file(it).asFile }
        val sharedRoot = sharedInputDirectory.asFile.get()
        val prepared = preparedDirectory.asFile.get()

        project.delete(prepared)
        Files.createDirectories(prepared.toPath())
        if (sharedRoot.exists()) {
            project.copy { spec ->
                spec.from(sharedRoot)
                spec.into(prepared)
            }
        } else {
            project.logger.info(
                "Kprofiles: shared snapshot '${relativePath(project, sharedRoot)}' not found. Proceeding with overlays only."
            )
        }

        if (labels.size != overlayPaths.size) {
            project.logger.warn(
                "Kprofiles: overlay metadata mismatch (labels=${labels.size}, paths=${overlayPaths.size}). " +
                    "labels=${labels.joinToString()} paths=${overlayPaths.joinToString()}"
            )
        }
        if (overlayPaths.size > labels.size) {
            val extras = overlayPaths.drop(labels.size)
            project.logger.warn(
                "Kprofiles: ${extras.size} overlay path(s) lack labels and will be ignored: ${extras.joinToString()}"
            )
        }

        val declaredInputDirs = overlayDirs.files.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
        val missingInCollection = overlayRoots.filter { root ->
            root.exists() && runCatching { root.canonicalFile }.getOrNull() !in declaredInputDirs
        }
        if (missingInCollection.isNotEmpty()) {
            val rendered = missingInCollection.joinToString { relativePath(project, it) }
            project.logger.info("Kprofiles: overlay directories not tracked in overlayDirs inputs: $rendered")
        }

        labels.forEachIndexed { index, label ->
            val root = overlayRoots.getOrNull(index)
            if (root == null || !root.exists()) {
                val rendered = root?.let { relativePath(project, it) } ?: "<missing>"
                project.logger.info(
                    "Kprofiles: overlay '$label' not found at '$rendered'. Proceeding with shared resources only."
                )
                return@forEachIndexed
            }
            applyOverlay(label, root, prepared, allowed, policy, diagnosticsEnabled)
        }
    }

    private fun applyOverlay(
        label: String,
        root: File,
        prepared: File,
        allowed: Set<String>,
        policy: CollisionPolicy,
        diagnosticsEnabled: Boolean
    ) {
        val topEntries = root.listFiles() ?: return
        if (topEntries.isEmpty()) return
        for (top in topEntries) {
            if (!top.isDirectory) continue
            val name = top.name
            if (!matchesAllowedTopLevel(name, allowed)) {
                if (diagnosticsEnabled) {
                    project.logger.warn(
                        "Kprofiles: ignored '$name' under '${relativePath(project, root)}'. Allowed: ${describeAllowedFolders(allowed)}"
                    )
                }
                continue
            }
            val targetRoot = File(prepared, name)
            copyRecursively(label, top, targetRoot, policy)
        }
    }

    private fun copyRecursively(
        label: String,
        source: File,
        targetRoot: File,
        policy: CollisionPolicy
    ) {
        Files.walk(source.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                val relativePath = source.toPath().relativize(path)
                if (relativePath.iterator().asSequence().any { it.toString().startsWith(".") || it.toString() == "__MACOSX" }) {
                    return@forEach
                }
                val relative = relativePath.toString().replace('\\', '/')
                val targetPath = targetRoot.toPath().resolve(relative)
                Files.createDirectories(targetPath.parent)
                if (Files.exists(targetPath)) {
                    when (policy) {
                        CollisionPolicy.FAIL -> error(
                            "Kprofiles: collision on '$relative' (layer='$label'). Set collisionPolicy=WARN or rename."
                        )
                        CollisionPolicy.WARN -> project.logger.info(
                            "Kprofiles: override '$relative' from layer '$label' replaced previous content."
                        )
                        CollisionPolicy.SILENT -> {}
                    }
                }
                try {
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING)
                } catch (ex: IOException) {
                    throw IOException(
                        "Kprofiles: failed to copy '$relative' for layer '$label' from '${source.path}'",
                        ex
                    )
                }
            }
        }
    }
}
