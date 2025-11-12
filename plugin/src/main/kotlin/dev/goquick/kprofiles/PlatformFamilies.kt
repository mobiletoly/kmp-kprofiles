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
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import java.util.Locale

internal object PlatformFamilies {

    fun availableFamilies(kotlin: KotlinMultiplatformExtension): Set<String> {
        return kotlin.targets.mapNotNull { target -> resolveFamily(target.platformType, target) }
            .toSet()
    }

    fun resolveActiveFamily(project: Project, kotlin: KotlinMultiplatformExtension): String {
        val cliFamily = project.providers.gradleProperty(FAMILY_PROPERTY).orNull
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
        val envFamily = project.providers.environmentVariable(FAMILY_ENV).orNull
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }

        val explicit = cliFamily ?: envFamily

        val families = availableFamilies(kotlin)

        explicit?.let { requested ->
            val normalized = normalize(requested)
            if (normalized == null) {
                error(
                    "Kprofiles: unknown platform family '$requested'. " +
                        "Use one of ${availableFamilyExamples().joinToString()} (aliases: desktop→jvm, osx→macos, win/mingw→windows, node/browser→js)."
                )
            }
            if (families.isNotEmpty() && normalized !in families) {
                error(
                    "Kprofiles: requested family '$requested' (normalized to '$normalized') is not present in this project. " +
                        "Detected families: ${families.sorted()}."
                )
            }
            return normalized
        }

        return when {
            families.isEmpty() -> error(
                "Kprofiles: unable to infer platform family. " +
                    "Specify one via -Pkprofiles.family=<android|ios|jvm|linux|wasm|js|windows|macos> " +
                    "(or set kprofiles.family=… in gradle.properties / KPROFILES_FAMILY env)."
            )
            families.size == 1 -> families.single()
            else -> error(
                "Kprofiles: multiple platform families detected ${families.sorted()}. " +
                    "Specify the desired one via -Pkprofiles.family=<${families.sorted().joinToString("|")}>."
            )
        }
    }

    fun normalize(value: String): String? {
        return when (value.lowercase(Locale.ROOT)) {
            "android" -> "android"
            "jvm", "desktop" -> "jvm"
            "ios" -> "ios"
            "macos", "osx" -> "macos"
            "tvos" -> "tvos"
            "watchos" -> "watchos"
            "linux" -> "linux"
            "wasm", "wasm-js", "wasm-wasi" -> "wasm"
            "js", "node", "browser" -> "js"
            "windows", "win", "mingw" -> "windows"
            else -> null
        }
    }

    private fun availableFamilyExamples(): List<String> =
        listOf("android", "ios", "tvos", "watchos", "jvm", "macos", "linux", "wasm", "js", "windows")

    private fun resolveFamily(platformType: KotlinPlatformType, target: KotlinTarget): String? {
        return when (platformType) {
            KotlinPlatformType.common -> null
            KotlinPlatformType.jvm -> "jvm"
            KotlinPlatformType.androidJvm -> "android"
            KotlinPlatformType.js -> "js"
            KotlinPlatformType.wasm -> "wasm"
            KotlinPlatformType.native -> (target as? KotlinNativeTarget)
                ?.konanTarget
                ?.family
                ?.toFamilyString()
            else -> null
        }
    }

    private fun Family.toFamilyString(): String = when (this) {
        Family.IOS -> "ios"
        Family.OSX -> "macos"
        Family.TVOS -> "tvos"
        Family.WATCHOS -> "watchos"
        Family.LINUX -> "linux"
        Family.ANDROID -> "android" // kept for completeness; KMP usually reports androidJvm instead.
        Family.MINGW -> "windows"
        else -> name.lowercase(Locale.ROOT)
    }

    private const val FAMILY_PROPERTY = "kprofiles.family"
    private const val FAMILY_ENV = "KPROFILES_FAMILY"
}

internal fun resolveActiveBuildType(project: Project): String? {
    val cli = project.providers.gradleProperty(BUILD_TYPE_PROPERTY).orNull?.trim()
    val env = project.providers.environmentVariable(BUILD_TYPE_ENV).orNull?.trim()
    return (cli ?: env)?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
}

private const val BUILD_TYPE_PROPERTY = "kprofiles.buildType"
private const val BUILD_TYPE_ENV = "KPROFILES_BUILD_TYPE"
