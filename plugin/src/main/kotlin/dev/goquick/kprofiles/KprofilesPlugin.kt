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

private const val COMMON_MAIN = "commonMain"

/**
 * Kprofiles Gradle plugin
 *
 * # What this plugin does (high level)
 * 1) Detects the active platform **family** (e.g., ios, android), active **buildType** (e.g., debug, release),
 *    and the selected **profile stack** (from CLI, env, or defaults).
 * 2) Prepares and overlays **profile-aware resources** into a generated directory that the
 *    Compose Multiplatform resources pipeline consumes.
 * 3) Optionally merges layered **YAML config** (shared → platform → buildType → profiles) and generates a **typed Kotlin API**.
 * 4) Exposes **diagnostics tasks** (print, verify, diag) to inspect effective inputs/outputs.
 * 5) On iOS: can auto-disable `embedAndSignAppleFrameworkForXcode*` tasks when **all K/N frameworks are static**.
 *
 * # Implementation notes
 * - Configuration-cache friendly: no `afterEvaluate`; heavy use of Providers and lazy wiring.
 * - Compose Resources integration is via reflective call to `compose.resources.customDirectory(...)`
 *   so we don’t pin to one plugin version/signature.
 * - File system access goes through Gradle Layout/Provider APIs for CC/parallel safety.
 */
class KprofilesPlugin : Plugin<Project> {

    /**
     * Entrypoint: creates the `kprofiles` extension, wires KMP once the KMP plugin is present,
     * and configures iOS embed toggling. All decisions are deferred with Providers.
     */
    override fun apply(project: Project) {
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        // Expose `kprofiles { ... }` DSL early; all defaults resolve lazily via Providers.
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configure(project, extension)
        }

        // --- iOS embed toggling (configuration-cache friendly)
        // If *all* Kotlin/Native frameworks are **static**, Xcode’s "embed and sign" steps are unnecessary.
        // Guarded by property: -Pkprofiles.autoDisableXcodeEmbedIfAllStatic=true (default).
        // Set it to false if you need to keep embedding even for static frameworks.
        val autoDisableProvider = project.providers
            .gradleProperty("kprofiles.autoDisableXcodeEmbedIfAllStatic")
            .map { it.equals("true", ignoreCase = true) }
            .orElse(true)

        // Precompute iOS frameworks linkage at configuration time to stay config-cache friendly.
        val autoDisableAtConfig = autoDisableProvider.get()
        val kmpExtForIos = project.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
        val frameworksAtConfig = kmpExtForIos?.targets
            ?.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java)
            ?.flatMap { tt -> tt.binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.Framework::class.java) }
            ?.toList()
            ?: emptyList()
        val anyDynamicAtConfig = frameworksAtConfig.any { !it.isStatic }
        val frameworksSummaryAtConfig = frameworksAtConfig.map { fw ->
            "${fw.baseName} ${fw.target.konanTarget} isStatic=${fw.isStatic}"
        }

        project.tasks.register("kprofilesWhyEmbedForXcode") { t ->
            t.group = "kprofiles"
            t.description = "Explain whether embedAndSignAppleFrameworkForXcode will be disabled."
            t.doLast {
                t.logger.lifecycle("[kprofiles] autoDisable=$autoDisableAtConfig, frameworks=${frameworksAtConfig.size}, anyDynamic=$anyDynamicAtConfig")
                frameworksSummaryAtConfig.forEach { info ->
                    t.logger.lifecycle("[kprofiles] - $info")
                }
            }
        }

        val embedTaskPrefixProvider = project.providers
            .gradleProperty("kprofiles.xcodeEmbedTaskPrefix")
            .orElse("embedAndSignAppleFrameworkForXcode")

        val embedTaskPrefix = embedTaskPrefixProvider.get()
        project.tasks
            .matching { it.name.startsWith(embedTaskPrefix) }
            .configureEach { task ->
                // Evaluate once at configuration and capture constants; onlyIf will not touch Project at execution.
                val shouldRun = !(autoDisableAtConfig && !anyDynamicAtConfig)
                task.onlyIf {
                    if (!shouldRun) {
                        task.logger.lifecycle("[kprofiles] Skipping ${task.name}: all Kotlin/Native frameworks are static and autoDisable is ON; no embedding needed.")
                    } else {
                        task.logger.debug("[kprofiles] Running ${task.name} (autoDisable=$autoDisableAtConfig, anyDynamic=$anyDynamicAtConfig).")
                    }
                    shouldRun
                }
            }
    }

    /**
     * Core KMP wiring:
     * - Loads `.env.local` (optional) and sets the env resolver.
     * - Resolves profile selection (source + ordered stack).
     * - Validates overlay path patterns and derives active family/buildType.
     * - Prepares shared resources and overlays (Compose Resources integration).
     * - Registers diagnostics tasks.
     * - Registers YAML merge + Kotlin config code generation (optional).
     * - Adds a convenience aggregate task `generateKprofiles`.
     */
    private fun configure(project: Project, extension: KprofilesExtension) {
        val kotlinExt = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?: error("Kprofiles requires the Kotlin Multiplatform plugin")

        // Load .env.local (if enabled) early; no need to wait for afterEvaluate
        configureEnvResolver(project, extension)

        val profileResolver = ProfileResolver(project, extension)
        // Lazily resolve the profile stack (CLI/env/default) so this remains CC-friendly
        val profileSelectionProvider = project.providers.provider {
            profileResolver.resolveProfiles()
        }
        val profileStackProvider = profileSelectionProvider.map { it.profiles }


        val requestedSourceSet = extension.sourceSets.get().firstOrNull() ?: COMMON_MAIN
        if (requestedSourceSet != "commonMain") {
            project.logger.warn(
                "Kprofiles: resource overlays currently support only 'commonMain' to avoid duplicate Res.* generation. Using 'commonMain' regardless of configuration."
            )
        }
        val ownerSourceSet = COMMON_MAIN
        val ownerCap =
            ownerSourceSet.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        val profilePattern = extension.profileDirPattern.get()
        val platformPattern = extension.platformDirPattern.get()
        val buildTypePattern = extension.buildTypeDirPattern.get()
        // Ensure user-supplied directory patterns are safe and contain the required tokens.
        validatePattern(profilePattern, "%PROFILE%", "kprofiles.profileDirPattern")
        validatePattern(platformPattern, "%FAMILY%", "kprofiles.platformDirPattern")
        validatePattern(buildTypePattern, "%BUILD_TYPE%", "kprofiles.buildTypeDirPattern")

        val activeFamily = PlatformFamilies.resolveActiveFamily(project, kotlinExt)
        val activeBuildType = resolveActiveBuildType(project)
        val sharedDirProvider = extension.sharedDir

        val sharedSnapshotDir =
            project.layout.buildDirectory.dir("generated/kprofiles/$ownerSourceSet/shared")
        val preparedDir =
            project.layout.buildDirectory.dir("generated/kprofiles/$ownerSourceSet/composeResources")

        // Snapshot the "shared" resource tree into buildDir; this is the immutable base for overlays.
        val prepareTask = project.tasks.register(
            "kprofilesPrepareSharedFor$ownerCap",
            KprofilesPrepareSharedTask::class.java
        ) { task ->
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
                family = activeFamily,
                buildType = activeBuildType,
                profileStack = selection.profiles,
                profileDirPattern = profilePattern,
                platformPattern = platformPattern,
                buildTypePattern = buildTypePattern
            )
        }

        project.logger.debug("Kprofiles: iOS frameworks are left as configured (no auto dynamic switch). You can flip isStatic in your own build logic.")

        // Merge overlays into a prepared directory consumed by Compose Resources tasks.
        val overlayTask = project.tasks.register(
            "kprofilesOverlayFor$ownerCap",
            KprofilesOverlayTask::class.java
        ) { task ->
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
            task.description =
                "Regenerates merged resources and generated config outputs for this module."
            task.dependsOn(overlayTask)
            task.dependsOn(configGenerateTask)
        }
    }

    /**
     * Ensure our overlay task runs before Compose Resources tasks for the given source set.
     * Some tasks are registered conditionally by the Compose plugin, so we wire both:
     *  - `tasks.named(...).configure { dependsOn(...) }` if present
     *  - `whenTaskAdded` to catch late registrations
     */
    private fun configureComposeTaskDependencies(
        project: Project,
        sourceSetCap: String,
        overlayTask: TaskProvider<KprofilesOverlayTask>,
        preparedDir: Provider<Directory>
    ) {
        val resourceTasks = listOf(
            "convertXmlValueResourcesFor$sourceSetCap",
            "copyNonXmlValueResourcesFor$sourceSetCap",
            "prepareComposeResourcesTaskFor$sourceSetCap",
            "generateResourceAccessorsFor$sourceSetCap"
        )

        val configureTask: (String) -> Unit = { name ->
            try {
                project.tasks.named(name).configure { it.dependsOn(overlayTask) }
            } catch (_: UnknownTaskException) {
                project.tasks.whenTaskAdded { added ->
                    if (added.name == name) {
                        added.dependsOn(overlayTask)
                    }
                }
            }
        }

        resourceTasks.forEach(configureTask)
    }

    /**
     * Registers a custom resources directory via reflection to avoid a hard dependency
     * on a specific Compose Resources plugin version/signature. If absent or mismatched, we log and skip.
     */
    private fun registerComposeCustomDirectory(
        project: Project,
        sourceSetName: String,
        directory: Provider<Directory>
    ) {
        val composeExt = project.extensions.findByName("compose") as? ExtensionAware ?: return
        val resourcesExt = composeExt.extensions.findByName("resources") ?: return

        // Try to find `customDirectory(String, Provider<Directory>)` first; fallback to
        // any 2-arg variant if signature differs.
        val customDirectoryMethod = resourcesExt.javaClass.methods.firstOrNull { method ->
            method.name == "customDirectory" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes.firstOrNull() == String::class.java &&
                    org.gradle.api.provider.Provider::class.java.isAssignableFrom(method.parameterTypes[1])
        } ?: resourcesExt.javaClass.methods.firstOrNull { method ->
            method.name == "customDirectory" && method.parameterCount == 2
        }
        if (customDirectoryMethod == null) {
            project.logger.debug(
                "Kprofiles: compose.resources.customDirectory not found; " +
                        "skipping custom directory registration."
            )
            return
        }
        try {
            customDirectoryMethod.isAccessible = true
            customDirectoryMethod.invoke(resourcesExt, sourceSetName, directory)
        } catch (t: Throwable) {
            project.logger.debug(
                "Kprofiles: failed to invoke compose.resources.customDirectory " +
                        "for '$sourceSetName': ${t.message}"
            )
        }
    }

    /**
     * Wires the optional YAML merge + Kotlin codegen path.
     *
     * Flow:
     * 1) `kprofilesMergeConfigFor<SourceSet>` merges YAML overlays into deterministic JSON.
     * 2) `kprofilesGenerateConfigFor<SourceSet>` generates Kotlin types/values from that JSON.
     * 3) Generated sources are attached to the source set; compile tasks depend on generation.
     *
     * Gated by `kprofiles.generatedConfig.enabled`. We validate `packageName` at execution to
     * fail fast with a clear message.
     */
    private fun registerConfigGeneration(
        project: Project,
        extension: KprofilesExtension,
        profileSelectionProvider: Provider<ProfileResolver.ProfileSelection>,
        family: String,
        buildType: String?
    ): TaskProvider<KprofilesGenerateConfigTask> {
        val targetSourceSet = extension.generatedConfig.sourceSet.orNull ?: COMMON_MAIN
        val cap =
            targetSourceSet.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val mergedConfigFile =
            project.layout.buildDirectory.file("generated/kprofiles/config/$targetSourceSet/merged-config.json")
        val generatedSrcDir =
            project.layout.buildDirectory.dir("generated/kprofiles/config/$targetSourceSet/src")
        val enabledProvider = extension.generatedConfig.enabled.orElse(project.provider { false })

        // Build ordered YAML inputs (shared → platform → buildType → profiles) + labels for diagnostics.
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

        val mergeTask = project.tasks.register(
            "kprofilesMergeConfigFor$cap",
            KprofilesMergeConfigTask::class.java
        ) { task ->
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

        val stackDescriptionProvider = profileSelectionProvider.map { selection ->
            buildStackComment(selection.profiles, family, buildType)
        }

        // Generate strongly-typed Kotlin config from the merged JSON snapshot.
        val generateTask = project.tasks.register(
            "kprofilesGenerateConfigFor$cap",
            KprofilesGenerateConfigTask::class.java
        ) { task ->
            task.group = "kprofiles"
            task.description = "Generate Kotlin config for $targetSourceSet."
            task.dependsOn(mergeTask)
            task.mergedConfigFile.set(mergeTask.flatMap { it.outputFile })
            task.packageName.set(extension.generatedConfig.packageName)
            task.typeName.set(extension.generatedConfig.typeName)
            task.outputDir.set(generatedSrcDir)
            task.preferConstScalars.set(extension.generatedConfig.preferConstScalars)
            task.stackDescription.set(stackDescriptionProvider)
            task.configSources.set(configLabelsProvider)
            task.onlyIf { enabledProvider.get() }
        }
        generateTask.configure { t ->
            t.doFirst {
                if (enabledProvider.get()) {
                    if (!extension.generatedConfig.packageName.isPresent) {
                        throw org.gradle.api.GradleException(
                            "Kprofiles: when kprofiles.generatedConfig.enabled=true you must set kprofiles { generatedConfig { packageName.set(\"com.example.config\") } } — no default is provided."
                        )
                    }
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

        // Attach the generated sources directory to the chosen source set
        // (if the Kotlin API shape matches).
        project.extensions.findByName("kotlin")?.let { kotlinExtension ->
            val sourceSetsContainer =
                kotlinExtension.javaClass.methods.firstOrNull { it.name == "getSourceSets" && it.parameterCount == 0 }
                    ?.invoke(kotlinExtension)
            if (sourceSetsContainer is NamedDomainObjectContainer<*>) {
                val sourceSetObj = sourceSetsContainer.findByName(targetSourceSet)
                if (sourceSetObj != null) {
                    val kotlinAccessor =
                        sourceSetObj.javaClass.methods.firstOrNull { it.name == "getKotlin" && it.parameterCount == 0 }
                    val kotlinDirs = kotlinAccessor?.invoke(sourceSetObj)
                    if (kotlinDirs is SourceDirectorySet) {
                        kotlinDirs.srcDir(generatedSrcDir.map { it.asFile })
                    } else {
                        project.logger.debug("Kprofiles: unable to attach generated config sources to '$targetSourceSet' (unexpected Kotlin API shape).")
                    }
                }
            }
        }

        // Make metadata/compile tasks depend on codegen so IDE/compilation sees generated types.
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

    /**
     * Diagnostics and verification tasks:
     * - `kprofilesPrintEffective`: prints the effective selection and paths.
     * - `kprofilesVerify`: validates structure and allowed top-level dirs.
     * - `kprofilesDiag`: dumps prepared directory diagnostics.
     */
    private fun registerDiagnostics(
        project: Project,
        extension: KprofilesExtension,
        profileSelectionProvider: Provider<ProfileResolver.ProfileSelection>,
        overlaySpecsProvider: Provider<List<OverlaySpec>>,
        family: String,
        buildType: String?,
        sharedDir: Provider<Directory>,
        preparedDirProvider: Provider<Directory>,
        overlayTask: TaskProvider<KprofilesOverlayTask>
    ) {
        val overlayLabelsProvider = overlaySpecsProvider.map { specs -> specs.map { it.label } }
        val overlayPathsProvider =
            overlaySpecsProvider.map { specs -> specs.map { it.path.asFile.absolutePath } }
        val overlayDirFilesProvider =
            overlaySpecsProvider.map { specs -> specs.map { it.path.asFile } }
        val preparedDirPathsProvider = preparedDirProvider.map { listOf(it.asFile.absolutePath) }

        val platformFamiliesProvider = project.providers.provider { listOf(family) }
        val buildTypesProvider = project.providers.provider { listOf(buildType ?: "") }

        project.tasks.register(
            "kprofilesPrintEffective",
            KprofilesPrintEffectiveTask::class.java
        ) { task ->
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
            task.sourceSets.set(listOf(COMMON_MAIN))
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
            task.sourceSets.set(listOf(COMMON_MAIN))
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

/**
 * Configures environment resolution. If `.env.local` fallback is enabled (default),
 * values from that file augment `System.getenv`, enabling repo-local overrides.
 */
private fun configureEnvResolver(project: Project, extension: KprofilesExtension) {
    val envFile = project.rootProject.layout.projectDirectory.file(".env.local").asFile
    val lazyValues = lazy {
        val values = loadEnvLocal(envFile, project.logger)
        if (values.isNotEmpty()) {
            project.logger.info("Kprofiles: loaded ${values.size} entries from .env.local")
        }
        values
    }
    KprofilesEnv.resolver = { key ->
        if (!extension.envLocalFallback.getOrElse(true)) {
            System.getenv(key)
        } else {
            val overrides = lazyValues.value
            System.getenv(key) ?: overrides[key]
        }
    }
}

/**
 * Minimal `.env.local` parser: supports optional `export`, quoted values, ignores comments/blanks,
 * and warns but continues on malformed lines.
 */
private fun loadEnvLocal(file: File, logger: org.gradle.api.logging.Logger): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val result = linkedMapOf<String, String>()
    file.useLines { sequence ->
        sequence.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cleaned =
                if (line.startsWith("export ")) line.removePrefix("export ").trim() else line
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
            if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith(
                    '\''
                ))
            ) {
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

/**
 * Validates overlay path patterns: must contain token, be project-relative, and avoid `..`.
 */
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

@Suppress("UNCHECKED_CAST")
private fun dependOnTasksNamed(project: Project, className: String, dependency: Any) {
    val taskClass = runCatching { Class.forName(className) }
        .getOrNull()
        ?.takeIf { Task::class.java.isAssignableFrom(it) }
            as? Class<out Task>
        ?: return
    project.tasks.withType(taskClass).configureEach { it.dependsOn(dependency) }
}

private fun buildStackComment(profiles: List<String>, family: String, buildType: String?): String {
    val segments = mutableListOf("shared", "platform:$family")
    if (!buildType.isNullOrBlank()) {
        segments += "buildType:$buildType"
    }
    if (profiles.isNotEmpty()) {
        segments += "profiles:${profiles.joinToString(",")}"
    }
    return segments.joinToString(" -> ")
}

private fun computeOverlaySpecs(
    project: Project,
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
        if (!dir.asFile.exists()) {
            project.logger.debug(
                "Kprofiles: overlay '$label' not found at '$relativePath'. Proceeding without it."
            )
        }
        specs += OverlaySpec(COMMON_MAIN, label, dir)
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
            specs += ConfigOverlaySpec(label, file)
        }
    }

    add("shared", "src/$COMMON_MAIN/config/app.yaml")
    add("platform:$family", "overlays/platform/$family/config/app.yaml")
    buildType?.takeIf { it.isNotBlank() }?.let { type ->
        add("buildType:$type", "overlays/buildType/$type/config/app.yaml")
    }
    profileSelection.profiles.forEach { profile ->
        add("profile:$profile", "overlays/profile/$profile/config/app.yaml")
    }
    return specs
}
