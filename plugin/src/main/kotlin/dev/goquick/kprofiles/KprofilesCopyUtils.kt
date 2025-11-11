package dev.goquick.kprofiles

import org.gradle.api.Project
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

internal fun relativePath(project: Project, dir: File): String {
    val root = project.layout.projectDirectory.asFile.toPath().toAbsolutePath().normalize()
    val target = dir.toPath().toAbsolutePath().normalize()
    return if (target.startsWith(root)) {
        val rel = root.relativize(target).toString().replace(File.separatorChar, '/')
        rel.ifEmpty { "." }
    } else {
        "[abs]" + target.toString().replace(File.separatorChar, '/')
    }
}
