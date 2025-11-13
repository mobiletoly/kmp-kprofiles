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

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import javax.inject.Inject

@CacheableTask
abstract class KprofilesPrepareSharedTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDirectory: DirectoryProperty

    @get:Input
    abstract val allowedTopLevelDirs: SetProperty<String>

    @get:Internal
    abstract val logDiagnostics: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val copyShared: Property<Boolean>

    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    init {
        projectDirectory.convention(project.layout.projectDirectory)
    }

    @TaskAction
    fun prepare() {
        val output = outputDirectory.asFile.get()
        val rootDir = projectDirectory.asFile.get()

        if (copyShared.getOrElse(true)) {
            val shared = sharedDirectory.asFile.get()
            if (!shared.exists()) {
                fileSystemOperations.delete { spec -> spec.delete(output) }
                logger.info("Kprofiles: shared directory '${relativePath(rootDir, shared)}' not found. Nothing to prepare.")
                return
            }

            val allowed = allowedTopLevelDirs.get()
            val diagnosticsEnabled = logDiagnostics.getOrElse(true)

            if (diagnosticsEnabled) {
                shared.listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { top ->
                        val name = top.name
                        if (!matchesAllowedTopLevel(name, allowed)) {
                            logger.warn("Kprofiles: ignored '$name' under '${relativePath(rootDir, shared)}'. Allowed: ${describeAllowedFolders(allowed)}")
                        }
                    }
            }

            val includeGlobs = includeGlobsForAllowed(allowed)

            fileSystemOperations.delete { spec -> spec.delete(output) }
            fileSystemOperations.sync { spec ->
                spec.into(output)
                spec.from(shared) { fs ->
                    includeGlobs.forEach(fs::include)
                    fs.exclude("**/.DS_Store", "**/Thumbs.db", "**/__MACOSX/**", "**/.*", "**/.*/**")
                    fs.includeEmptyDirs = false
                    fs.exclude { details -> Files.isSymbolicLink(details.file.toPath()) }
                }
            }
        } else {
            fileSystemOperations.delete { spec -> spec.delete(output) }
        }
    }

    private fun includeGlobsForAllowed(allowed: Set<String>): List<String> {
        return allowed.flatMap { root ->
            when (root) {
                "values", "drawable" -> listOf("$root/**", "$root-*/**")
                "files", "font" -> listOf("$root/**")
                else -> listOf("$root/**", "$root-*/**")
            }
        }
    }
}
