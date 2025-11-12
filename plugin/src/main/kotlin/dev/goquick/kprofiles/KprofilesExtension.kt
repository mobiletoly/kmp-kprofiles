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

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

internal const val DEFAULT_SHARED_DIR = "src/commonMain/composeResources"
internal const val DEFAULT_PROFILE_PATTERN = "overlays/profile/%PROFILE%/composeResources"
internal const val DEFAULT_PLATFORM_PATTERN = "overlays/platform/%FAMILY%/composeResources"
internal const val DEFAULT_BUILD_TYPE_PATTERN = "overlays/buildType/%BUILD_TYPE%/composeResources"

abstract class KprofilesExtension @Inject constructor(
    objects: ObjectFactory,
    layout: ProjectLayout
) {

    /**
     * Explicit layered profile stack (left → right, last wins). CLI/env flags override this.
     */
    val defaultProfiles: ListProperty<String> = objects.listProperty()

    /**
     * Shared Compose resources directory (relative to project root).
     */
    val sharedDir: DirectoryProperty = objects.directoryProperty()

    /**
     * Overlay directory pattern (relative to project). Must contain `%PROFILE%` (e.g., overlays/profile/%PROFILE%/composeResources).
     */
    val profileDirPattern: Property<String> = objects.property()

    /**
     * Platform overlay directory pattern. Must contain `%FAMILY%`.
     */
    val platformDirPattern: Property<String> = objects.property()

    /**
     * Build-type overlay directory pattern. Must contain `%BUILD_TYPE%`.
     */
    val buildTypeDirPattern: Property<String> = objects.property()

    /**
     * Source sets that should receive overlays (currently limited to `commonMain` to avoid duplicate Compose Res.* generation).
     */
    val sourceSets: ListProperty<String> = objects.listProperty()

    /**
     * Allowed top-level directories (base names). Qualified variants like `values-es` or `drawable-xhdpi` are allowed when the base matches.
     */
    val allowedTopLevelDirs: SetProperty<String> = objects.setProperty()

    /**
     * Collision handling strategy between shared + profile resources (WARN, FAIL, or SILENT).
     */
    val collisionPolicy: Property<CollisionPolicy> = objects.property()

    /**
     * If true, invalid Android filenames should fail verification.
     */
    val strictAndroidNames: Property<Boolean> = objects.property()

    /**
     * Turns on verbose diagnostics/logging.
     */
    val logDiagnostics: Property<Boolean> = objects.property()

    val generatedConfig: KprofilesConfigExtension = objects.newInstance(KprofilesConfigExtension::class.java)

    /**
     * Back-compat alias for generatedConfig.
     */
    @Suppress("unused")
    val config: KprofilesConfigExtension
        get() = generatedConfig

    init {
        defaultProfiles.convention(emptyList())
        sharedDir.convention(layout.projectDirectory.dir(DEFAULT_SHARED_DIR))
        profileDirPattern.convention(DEFAULT_PROFILE_PATTERN).finalizeValueOnRead()
        platformDirPattern.convention(DEFAULT_PLATFORM_PATTERN).finalizeValueOnRead()
        buildTypeDirPattern.convention(DEFAULT_BUILD_TYPE_PATTERN).finalizeValueOnRead()
        sourceSets.convention(listOf("commonMain"))
        allowedTopLevelDirs.convention(setOf("drawable", "values", "font", "files"))
        collisionPolicy.convention(CollisionPolicy.WARN)
        strictAndroidNames.convention(false)
        logDiagnostics.convention(false)

        defaultProfiles.finalizeValueOnRead()
        sharedDir.finalizeValueOnRead()
        platformDirPattern.finalizeValueOnRead()
        buildTypeDirPattern.finalizeValueOnRead()
        sourceSets.finalizeValueOnRead()
        allowedTopLevelDirs.finalizeValueOnRead()
        collisionPolicy.finalizeValueOnRead()
        strictAndroidNames.finalizeValueOnRead()
        logDiagnostics.finalizeValueOnRead()
    }
}

abstract class KprofilesConfigExtension @Inject constructor(objects: ObjectFactory) {
    val enabled: Property<Boolean> = objects.property()
    val packageName: Property<String> = objects.property()
    val typeName: Property<String> = objects.property()
    val sourceSet: Property<String> = objects.property()
    val preferConstScalars: Property<Boolean> = objects.property()

    init {
        enabled.convention(false)
        typeName.convention("AppConfig")
        sourceSet.convention("commonMain")
        preferConstScalars.convention(true)
    }
}

operator fun KprofilesConfigExtension.invoke(action: KprofilesConfigExtension.() -> Unit) {
    this.action()
}

fun KprofilesExtension.generatedConfig(action: KprofilesConfigExtension.() -> Unit) {
    this.generatedConfig.action()
}

/**
 * Convenience accessor for configuring the `kprofiles` extension in build scripts.
 *
 * Example:
 *
 * ```
 * kprofiles {
 *     defaultProfiles.set(listOf("theme-blue"))
 * }
 * ```
 */
fun Project.kprofiles(action: KprofilesExtension.() -> Unit) {
    extensions.configure(KprofilesExtension::class.java, action)
}

private inline fun <reified T : Any> ObjectFactory.listProperty(): ListProperty<T> =
    listProperty(T::class.java)

private inline fun <reified T : Any> ObjectFactory.setProperty(): SetProperty<T> =
    setProperty(T::class.java)

private inline fun <reified T : Any> ObjectFactory.property(): Property<T> =
    property(T::class.java)
