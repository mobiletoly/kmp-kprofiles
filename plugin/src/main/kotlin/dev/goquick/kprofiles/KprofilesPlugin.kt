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

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import java.util.Locale
import java.util.Properties

class KprofilesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configure(project, extension)
        }
    }

    private fun configure(project: Project, extension: KprofilesExtension) {
        val kotlinExt = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?: error("Kprofiles requires the Kotlin Multiplatform plugin")

        val profileResolver = ProfileResolver(project, extension)
        val profileSelectionProvider = project.providers.provider {
            profileResolver.resolveProfiles()
        }
        val profileStackProvider = profileSelectionProvider.map { it.profiles }

        extension.generatedConfig.packageName.convention(project.provider {
            val group = project.group.toString().takeIf { it.isNotBlank() }
            group?.let { "$it.config" } ?: DEFAULT_CONFIG_PACKAGE
        })

        val requestedSourceSet = extension.sourceSets.get().firstOrNull() ?: "commonMain"
        if (requestedSourceSet != "commonMain") {
            project.logger.warn(
                "Kprofiles: resource overlays currently support only 'commonMain' to avoid duplicate Res.* generation. Using 'commonMain' regardless of configuration."
            )
        }
        val ownerSourceSet = OWNER_SOURCE_SET
        val ownerCap = ownerSourceSet.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        val profilePattern = extension.profileDirPattern.get()
        val platformPattern = extension.platformDirPattern.get()
        val buildTypePattern = extension.buildTypeDirPattern.get()
        validatePattern(profilePattern, "%PROFILE%", "kprofiles.profileDirPattern")
        validatePattern(platformPattern, "%FAMILY%", "kprofiles.platformDirPattern")
        validatePattern(buildTypePattern, "%BUILD_TYPE%", "kprofiles.buildTypeDirPattern")

        project.afterEvaluate {
            configureEnvResolver(project, extension)
            val profileSelection = profileSelectionProvider.get()
            when (profileSelection.source) {
                ProfileResolver.ProfileSource.DEFAULT -> project.logger.info(
                    "Kprofiles: using defaultProfiles=${profileSelection.profiles}. Override via -Pkprofiles.profiles or KPROFILES_PROFILES."
                )
                ProfileResolver.ProfileSource.NONE -> project.logger.info(
                    "Kprofiles: no active profiles supplied. Using shared resources only."
                )
                else -> Unit
            }
            val stackDescription = buildList {
                add("shared")
                addAll(profileSelection.profiles)
            }.joinToString(prefix = "[", postfix = "]")
            project.logger.info("Kprofiles: active stack = $stackDescription")
        }

        val activeFamily = PlatformFamilies.resolveActiveFamily(project, kotlinExt)
        val activeBuildType = resolveActiveBuildType(project)
        val sharedDirProvider = extension.sharedDir

        val sharedSnapshotDir = project.layout.buildDirectory.dir("generated/kprofiles/$ownerSourceSet/shared")
        val preparedDir = project.layout.buildDirectory.dir("generated/kprofiles/$ownerSourceSet/composeResources")

        val prepareTask = project.tasks.register("kprofilesPrepareSharedFor$ownerCap", KprofilesPrepareSharedTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Prepare shared Compose resources for $ownerSourceSet."
            task.sharedDirectory.set(sharedDirProvider)
            task.allowedTopLevelDirs.set(extension.allowedTopLevelDirs)
            task.logDiagnostics.set(extension.logDiagnostics)
            task.outputDirectory.set(sharedSnapshotDir)
            task.copyShared.set(true)
            task.projectDirectory.set(project.layout.projectDirectory)
        }

        val overlaySpecsProvider = project.providers.provider {
            val selection = profileSelectionProvider.get()
            computeOverlaySpecs(
                project = project,
                ownerSourceSet = ownerSourceSet,
                family = activeFamily,
                buildType = activeBuildType,
                profileStack = selection.profiles,
                profileDirPattern = profilePattern,
                platformPattern = platformPattern,
                buildTypePattern = buildTypePattern
            )
        }

        val overlayTask = project.tasks.register("kprofilesOverlayFor$ownerCap", KprofilesOverlayTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Overlay profile resources for $ownerSourceSet."
            task.dependsOn(prepareTask)
            task.preparedDirectory.set(preparedDir)
            task.sharedInputDirectory.set(prepareTask.flatMap { it.outputDirectory })
            task.profiles.set(profileStackProvider)
            task.platformFamily.set(activeFamily)
            task.buildType.set(activeBuildType ?: "")
            task.allowedTopLevelDirs.set(extension.allowedTopLevelDirs)
            task.collisionPolicy.set(extension.collisionPolicy)
            task.logDiagnostics.set(extension.logDiagnostics)
            task.overlayLabels.set(overlaySpecsProvider.map { specs -> specs.map { it.label } })
            task.overlayPathStrings.set(overlaySpecsProvider.map { specs -> specs.map { it.path.asFile.absolutePath } })
            task.overlayDirs.from(overlaySpecsProvider.map { specs -> specs.map { it.path } })
            task.projectDirectory.set(project.layout.projectDirectory)
        }
        val preparedDirFromOverlay = overlayTask.flatMap { it.preparedDirectory }

        configureComposeTaskDependencies(project, ownerCap, overlayTask, preparedDirFromOverlay)
        registerComposeCustomDirectory(project, ownerSourceSet, preparedDirFromOverlay)

        registerDiagnostics(
            project = project,
            extension = extension,
            profileSelectionProvider = profileSelectionProvider,
            overlaySpecsProvider = overlaySpecsProvider,
            ownerSourceSet = ownerSourceSet,
            family = activeFamily,
            buildType = activeBuildType,
            sharedDir = sharedDirProvider,
            preparedDirProvider = preparedDirFromOverlay,
            overlayTask = overlayTask
        )

        val configGenerateTask = registerConfigGeneration(
            project = project,
            extension = extension,
            profileSelectionProvider = profileSelectionProvider,
            family = activeFamily,
            buildType = activeBuildType
        )

        project.tasks.register("generateKprofiles") { task ->
            task.group = "kprofiles"
            task.description = "Regenerates merged resources and generated config outputs for this module."
            task.dependsOn(overlayTask)
            task.dependsOn(configGenerateTask)
        }
    }

    private fun configureComposeTaskDependencies(
        project: Project,
        sourceSetCap: String,
        overlayTask: TaskProvider<KprofilesOverlayTask>,
        preparedDir: Provider<Directory>
    ) {
        val dependencyCandidates = listOf(
            "convertXmlValueResourcesFor$sourceSetCap",
            "copyNonXmlValueResourcesFor$sourceSetCap",
            "prepareComposeResourcesTaskFor$sourceSetCap",
            "generateResourceAccessorsFor$sourceSetCap",
            "syncComposeResourcesFor$sourceSetCap"
        ) + listOf(
            // iOS compose sync task is not source-set scoped; include explicitly.
            "syncComposeResourcesForIos"
        )
        dependencyCandidates.forEach { candidate ->
            try {
                project.tasks.named(candidate).configure {
                    it.dependsOn(overlayTask)
                    updateOriginalResourcesDirIfPossible(it, preparedDir)
                }
            } catch (_: UnknownTaskException) {
                project.tasks.whenTaskAdded { added ->
                    if (added.name == candidate) {
                        added.dependsOn(overlayTask)
                        updateOriginalResourcesDirIfPossible(added, preparedDir)
                    }
                }
            }
        }
    }

    private fun registerComposeCustomDirectory(
        project: Project,
        sourceSetName: String,
        directory: Provider<Directory>
    ) {
        val composeExt = project.extensions.findByName("compose") as? ExtensionAware ?: return
        val resourcesExt = composeExt.extensions.findByName("resources") ?: return
        val customDirectoryMethod = resourcesExt.javaClass.methods.firstOrNull { method ->
            method.name == "customDirectory" &&
                method.parameterCount == 2 &&
                method.parameterTypes.firstOrNull() == String::class.java &&
                org.gradle.api.provider.Provider::class.java.isAssignableFrom(method.parameterTypes[1])
        } ?: resourcesExt.javaClass.methods.firstOrNull { method ->
            method.name == "customDirectory" && method.parameterCount == 2
        }
        if (customDirectoryMethod == null) {
            project.logger.debug("Kprofiles: compose.resources.customDirectory not found; skipping custom directory registration.")
            return
        }
        try {
            customDirectoryMethod.isAccessible = true
            customDirectoryMethod.invoke(resourcesExt, sourceSetName, directory)
        } catch (t: Throwable) {
            project.logger.debug("Kprofiles: failed to invoke compose.resources.customDirectory for '$sourceSetName': ${t.message}")
        }
    }

    private fun updateOriginalResourcesDirIfPossible(task: Task, dir: Provider<Directory>) {
        val getter = task.javaClass.methods.firstOrNull { it.name == "getOriginalResourcesDir" && it.parameterCount == 0 }
        val value = getter?.invoke(task) ?: runCatching {
            val field = task.javaClass.getDeclaredField("originalResourcesDir").apply { isAccessible = true }
            field.get(task)
        }.getOrNull()
        val dirProperty = value as? org.gradle.api.file.DirectoryProperty
        if (dirProperty != null) {
            dirProperty.set(dir)
            return
        }
        val setter = task.javaClass.methods.firstOrNull { it.name == "setOriginalResourcesDir" && it.parameterCount == 1 }
        if (setter != null) {
            setter.invoke(task, dir)
            return
        }
        task.project.logger.debug("Kprofiles: task '${task.name}' does not expose originalResourcesDir; skipping override.")
    }

    private fun registerConfigGeneration(
        project: Project,
        extension: KprofilesExtension,
        profileSelectionProvider: Provider<ProfileResolver.ProfileSelection>,
        family: String,
        buildType: String?
    ): TaskProvider<KprofilesGenerateConfigTask> {
        val targetSourceSet = extension.generatedConfig.sourceSet.orNull ?: "commonMain"
        val cap = targetSourceSet.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val mergedConfigFile = project.layout.buildDirectory.file("generated/kprofiles/config/$targetSourceSet/merged-config.json")
        val generatedSrcDir = project.layout.buildDirectory.dir("generated/kprofiles/config/$targetSourceSet/src")
        val enabledProvider = extension.generatedConfig.enabled.orElse(project.provider { false })

        val configSpecsProvider = project.providers.provider {
            val selection = profileSelectionProvider.get()
            computeConfigOverlaySpecs(
                project = project,
                family = family,
                buildType = buildType,
                profileSelection = selection
            )
        }
        val orderedPathsProvider = configSpecsProvider.map { specs ->
            specs.map { project.relativePath(it.file.asFile) }
        }
        val configFilesProvider = configSpecsProvider.map { specs -> specs.map { it.file.asFile } }
        val configLabelsProvider = configSpecsProvider.map { specs -> specs.map { it.label } }

        val mergeTask = project.tasks.register("kprofilesMergeConfigFor$cap", KprofilesMergeConfigTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Merge profile-aware configuration for $targetSourceSet."
            task.profileStack.set(profileSelectionProvider.map { it.profiles.joinToString(",") })
            task.platformFamily.set(family)
            task.buildType.set(buildType ?: "")
            task.overlayLabels.set(configLabelsProvider)
            task.orderedInputPaths.set(orderedPathsProvider)
            task.inputFiles.setFrom(configFilesProvider)
            task.outputFile.set(mergedConfigFile)
            task.onlyIf { enabledProvider.get() }
            task.projectDirectory.set(project.layout.projectDirectory)
        }

        val generateTask = project.tasks.register("kprofilesGenerateConfigFor$cap", KprofilesGenerateConfigTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Generate Kotlin config for $targetSourceSet."
            task.dependsOn(mergeTask)
            task.mergedConfigFile.set(mergeTask.flatMap { it.outputFile })
            task.packageName.set(extension.generatedConfig.packageName)
            task.typeName.set(extension.generatedConfig.typeName)
            task.outputDir.set(generatedSrcDir)
            task.preferConstScalars.set(extension.generatedConfig.preferConstScalars)
            task.onlyIf { enabledProvider.get() }
        }
        project.afterEvaluate {
            if (enabledProvider.get()) {
                val derivedDefaultPackage = project.group.toString().takeIf { it.isNotBlank() }?.let { "$it.config" } ?: DEFAULT_CONFIG_PACKAGE
                val packageExplicit = extension.generatedConfig.packageName.isPresent
                val packageNameValue = extension.generatedConfig.packageName.orNull ?: derivedDefaultPackage
                if (!packageExplicit && packageNameValue == DEFAULT_CONFIG_PACKAGE) {
                    error(
                        "Kprofiles: when kprofiles.generatedConfig.enabled=true you must set kprofiles { generatedConfig { packageName.set(\"com.example.config\") } }. " +
                            "The fallback '$DEFAULT_CONFIG_PACKAGE' is reserved."
                    )
                }
            }
        }

        val printTaskName = "kprofilesConfigPrintFor$cap"
        project.tasks.register(printTaskName, KprofilesConfigPrintTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Print merged profile-aware configuration."
            task.dependsOn(mergeTask)
            task.profileSource.set(profileSelectionProvider.map { it.source.name })
            task.profileStack.set(profileSelectionProvider.map { it.profiles.joinToString(",") })
            task.overlayLabels.set(configLabelsProvider)
            task.platformFamily.set(family)
            task.buildType.set(buildType ?: "")
            task.mergedConfigFile.set(mergeTask.flatMap { it.outputFile })
            task.onlyIf { enabledProvider.get() }
        }

        project.extensions.findByName("kotlin")?.let { kotlinExtension ->
            val sourceSetsContainer = kotlinExtension.javaClass.methods.firstOrNull { it.name == "getSourceSets" && it.parameterCount == 0 }
                ?.invoke(kotlinExtension)
            if (sourceSetsContainer is NamedDomainObjectContainer<*>) {
                val sourceSetObj = sourceSetsContainer.findByName(targetSourceSet)
                if (sourceSetObj != null) {
                    val kotlinAccessor = sourceSetObj.javaClass.methods.firstOrNull { it.name == "getKotlin" && it.parameterCount == 0 }
                    val kotlinDirs = kotlinAccessor?.invoke(sourceSetObj)
                    if (kotlinDirs is SourceDirectorySet) {
                        kotlinDirs.srcDir(generatedSrcDir.map { it.asFile })
                    } else {
                        project.logger.debug("Kprofiles: unable to attach generated config sources to '$targetSourceSet' (unexpected Kotlin API shape).")
                    }
                }
            }
        }

        val metadataTaskName = "compile${cap}KotlinMetadata"
        project.tasks.matching { it.name == metadataTaskName }.configureEach {
            it.dependsOn(generateTask)
        }
        project.tasks.whenTaskAdded { added ->
            if (added.name == metadataTaskName) {
                added.dependsOn(generateTask)
            }
        }

        val compileTaskClasses = listOf(
            "org.jetbrains.kotlin.gradle.tasks.KotlinCompile",
            "org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile",
            "org.jetbrains.kotlin.gradle.tasks.KotlinJsCompile",
            "org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile"
        )
        compileTaskClasses.forEach { taskClass ->
            dependOnTasksNamed(project, taskClass, generateTask)
        }

        return generateTask
    }

    private fun registerDiagnostics(
        project: Project,
        extension: KprofilesExtension,
        profileSelectionProvider: Provider<ProfileResolver.ProfileSelection>,
        overlaySpecsProvider: Provider<List<OverlaySpec>>,
        ownerSourceSet: String,
        family: String,
        buildType: String?,
        sharedDir: Provider<Directory>,
        preparedDirProvider: Provider<Directory>,
        overlayTask: TaskProvider<KprofilesOverlayTask>
    ) {
        val overlayLabelsProvider = overlaySpecsProvider.map { specs -> specs.map { it.label } }
        val overlayPathsProvider = overlaySpecsProvider.map { specs -> specs.map { it.path.asFile.absolutePath } }
        val overlayDirFilesProvider = overlaySpecsProvider.map { specs -> specs.map { it.path.asFile } }
        val preparedDirPathsProvider = preparedDirProvider.map { listOf(it.asFile.absolutePath) }

        val platformFamiliesProvider = project.providers.provider { listOf(family) }
        val buildTypesProvider = project.providers.provider { listOf(buildType ?: "") }

        project.tasks.register("kprofilesPrintEffective", KprofilesPrintEffectiveTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Print the effective profiles configuration."
            task.dependsOn(overlayTask)
            task.sharedDir.set(sharedDir)
            task.profiles.set(profileSelectionProvider.map { it.profiles })
            task.profileSource.set(profileSelectionProvider.map { it.source.name })
            task.platformFamilies.set(platformFamiliesProvider)
            task.buildTypes.set(buildTypesProvider)
            task.overlayLabels.set(overlayLabelsProvider)
            task.overlayPaths.set(overlayPathsProvider)
            task.overlayDirs.from(overlayDirFilesProvider)
            task.preparedPaths.set(preparedDirPathsProvider)
            task.preparedDirs.from(preparedDirProvider.map { it.asFile })
        }

        project.tasks.register("kprofilesVerify", KprofilesVerifyTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Verify profile resource structure."
            task.dependsOn(overlayTask)
            task.sourceSets.set(listOf(ownerSourceSet))
            task.profiles.set(profileSelectionProvider.map { it.profiles })
            task.profileSource.set(profileSelectionProvider.map { it.source.name })
            task.platformFamilies.set(platformFamiliesProvider)
            task.buildTypes.set(buildTypesProvider)
            task.overlayLabels.set(overlayLabelsProvider)
            task.overlayPaths.set(overlayPathsProvider)
            task.sharedDir.set(sharedDir)
            task.allowedTopLevelDirs.set(extension.allowedTopLevelDirs)
            task.strictAndroidNames.set(extension.strictAndroidNames)
            task.preparedDirPaths.set(preparedDirPathsProvider)
            task.overlayDirs.from(overlayDirFilesProvider)
            task.preparedDirs.from(preparedDirProvider.map { it.asFile })
        }

        project.tasks.register("kprofilesDiag", KprofilesDiagTask::class.java) { task ->
            task.group = "kprofiles"
            task.description = "Print diagnostics about prepared resources."
            task.dependsOn(overlayTask)
            task.sourceSets.set(listOf(ownerSourceSet))
            task.profiles.set(profileSelectionProvider.map { it.profiles })
            task.profileSource.set(profileSelectionProvider.map { it.source.name })
            task.platformFamilies.set(platformFamiliesProvider)
            task.buildTypes.set(buildTypesProvider)
            task.overlayLabels.set(overlayLabelsProvider)
            task.overlayPaths.set(overlayPathsProvider)
            task.preparedDirPaths.set(preparedDirPathsProvider)
            task.overlayDirs.from(overlayDirFilesProvider)
            task.preparedDirs.from(preparedDirProvider.map { it.asFile })
        }
    }
}

private fun configureEnvResolver(project: Project, extension: KprofilesExtension) {
    val fallbackEnabled = extension.envLocalFallback.getOrElse(true)
    if (!fallbackEnabled) {
        KprofilesEnv.resolver = { key -> System.getenv(key) }
        return
    }
    val envFile = project.rootProject.layout.projectDirectory.file(".env.local").asFile
    val values = loadEnvLocal(envFile, project.logger)
    if (values.isNotEmpty()) {
        project.logger.info("Kprofiles: loaded ${values.size} entries from .env.local")
    }
    KprofilesEnv.resolver = { key -> System.getenv(key) ?: values[key] }
}

private fun loadEnvLocal(file: File, logger: org.gradle.api.logging.Logger): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val result = linkedMapOf<String, String>()
    file.useLines { sequence ->
        sequence.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cleaned = if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
            val equalsIndex = cleaned.indexOf('=')
            if (equalsIndex <= 0) {
                logger.warn("Kprofiles: ignoring malformed line ${index + 1} in .env.local")
                return@forEachIndexed
            }
            val key = cleaned.substring(0, equalsIndex).trim()
            if (key.isEmpty()) {
                logger.warn("Kprofiles: ignoring blank key on line ${index + 1} in .env.local")
                return@forEachIndexed
            }
            var value = cleaned.substring(equalsIndex + 1).trim()
            if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
                value = value.substring(1, value.length - 1)
            }
            result[key] = value
        }
    }
    return result
}

private data class OverlaySpec(
    val sourceSetName: String,
    val label: String,
    val path: Directory
)

private fun validatePattern(pattern: String, token: String, label: String) {
    require(pattern.contains(token)) {
        "Kprofiles: $label must contain '$token'. Current value: '$pattern'"
    }
    val sample = pattern.replace(token, "sample")
    require(!File(sample).isAbsolute) {
        "Kprofiles: $label must be relative to the project. Current value: '$pattern'"
    }
    require(!sample.split('/', '\\').any { it == ".." }) {
        "Kprofiles: $label must not traverse outside the project (no '..' segments). Current value: '$pattern'"
    }
}

private const val OWNER_SOURCE_SET = "commonMain"
private const val DEFAULT_CONFIG_PACKAGE = "dev.goquick.kprofiles.config"

@Suppress("UNCHECKED_CAST")
private fun dependOnTasksNamed(project: Project, className: String, dependency: Any) {
    val taskClass = runCatching { Class.forName(className) }
        .getOrNull()
        ?.takeIf { Task::class.java.isAssignableFrom(it) }
        as? Class<out Task>
        ?: return
    project.tasks.withType(taskClass).configureEach { it.dependsOn(dependency) }
}

private fun computeOverlaySpecs(
    project: Project,
    ownerSourceSet: String,
    family: String,
    buildType: String?,
    profileStack: List<String>,
    profileDirPattern: String,
    platformPattern: String,
    buildTypePattern: String
): List<OverlaySpec> {
    val specs = mutableListOf<OverlaySpec>()
    fun add(label: String, relativePath: String) {
        val dir = project.layout.projectDirectory.dir(relativePath)
        if (dir.asFile.exists()) {
            specs += OverlaySpec(ownerSourceSet, label, dir)
        } else {
            project.logger.info(
                "Kprofiles: overlay '$label' not found at '$relativePath'. Proceeding without it."
            )
        }
    }

    add("platform:$family", platformPattern.replace("%FAMILY%", family))
    buildType?.takeIf { it.isNotBlank() }?.let { type ->
        add("buildType:$type", buildTypePattern.replace("%BUILD_TYPE%", type))
    }
    profileStack.forEach { profile ->
        add("profile:$profile", profileDirPattern.replace("%PROFILE%", profile))
    }
    return specs
}

private data class ConfigOverlaySpec(
    val label: String,
    val file: RegularFile
)

private fun computeConfigOverlaySpecs(
    project: Project,
    family: String,
    buildType: String?,
    profileSelection: ProfileResolver.ProfileSelection
): List<ConfigOverlaySpec> {
    val specs = mutableListOf<ConfigOverlaySpec>()
    fun add(label: String, relative: String) {
        val file = project.layout.projectDirectory.file(relative)
        if (file.asFile.exists()) {
            specs += ConfigOverlaySpec("commonMain:$label", file)
        }
    }

    add("shared", "src/commonMain/config/app.yaml")
    add("platform:$family", "overlays/platform/$family/config/app.yaml")
    buildType?.takeIf { it.isNotBlank() }?.let { type ->
        add("buildType:$type", "overlays/buildType/$type/config/app.yaml")
    }
    profileSelection.profiles.forEach { profile ->
        add("profile:$profile", "overlays/profile/$profile/config/app.yaml")
    }
    return specs
}
