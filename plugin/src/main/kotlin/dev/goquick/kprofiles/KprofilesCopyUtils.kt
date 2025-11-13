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

import java.io.File
import java.util.Locale

internal fun matchesAllowedTopLevel(name: String, allowed: Set<String>): Boolean {
    if (name.isEmpty()) return false
    val normalizedName = name.lowercase(Locale.ROOT)
    return allowed.any { candidate ->
        val normalizedCandidate = candidate.lowercase(Locale.ROOT)
        normalizedName == normalizedCandidate || normalizedName.startsWith("$normalizedCandidate-")
    }
}

internal fun describeAllowedFolders(allowed: Set<String>): String =
    allowed.asSequence()
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .sorted()
        .joinToString(", ") { entry ->
            if (entry == "files") "files/" else "$entry/ or ${entry}-*/"
        }

internal fun relativePath(projectDir: File, dir: File): String {
    val root = projectDir.toPath().toAbsolutePath().normalize()
    val target = dir.toPath().toAbsolutePath().normalize()
    return if (target.startsWith(root)) {
        val rel = root.relativize(target).toString().replace(File.separatorChar, '/')
        rel.ifEmpty { "." }
    } else {
        "[abs]" + target.toString().replace(File.separatorChar, '/')
    }
}

internal fun relativePath(project: org.gradle.api.Project, dir: File): String =
    relativePath(project.layout.projectDirectory.asFile, dir)
