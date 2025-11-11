package dev.goquick.kprofiles

import org.gradle.api.Project

internal class ProfileResolver(
    private val project: Project,
    private val extension: KprofilesExtension
) {

    fun resolveProfiles(): ProfileSelection {
        val cliStackRaw = project.providers.gradleProperty(GRADLE_PROP_NEW).orNull?.trim()
        val envStackRaw = project.providers.environmentVariable(ENV_VAR_NEW).orNull?.trim()
        if (!cliStackRaw.isNullOrBlank() && !envStackRaw.isNullOrBlank() && cliStackRaw != envStackRaw) {
            project.logger.info("Kprofiles: -Pkprofiles.profiles overrides $ENV_VAR_NEW.")
        }
        return resolveProfiles(cliStackRaw, envStackRaw)
    }

    internal fun resolveProfiles(
        cliStackRaw: String?,
        envStackRaw: String?
    ): ProfileSelection {
        val (source, stack) = when {
            !cliStackRaw.isNullOrBlank() -> ProfileSource.CLI to parseStack(cliStackRaw)
            !envStackRaw.isNullOrBlank() -> ProfileSource.ENV to parseStack(envStackRaw)
            extension.defaultProfiles.orNull?.isNotEmpty() == true ->
                ProfileSource.DEFAULT to extension.defaultProfiles.get().also { it.forEach(::validateName) }
            else -> ProfileSource.NONE to emptyList()
        }
        return ProfileSelection(source = source, profiles = stack)
    }

    fun overlayDir(profile: String) =
        extension.profileDirPattern
            .map { it.replace("%PROFILE%", profile) }

    private fun parseStack(raw: String): List<String> {
        val items = raw.split(',')
            .map { it.trim().stripOuterQuotes() }
            .filter { it.isNotEmpty() }
        items.forEach(::validateName)
        val seen = linkedMapOf<String, Int>()
        items.forEachIndexed { index, profile -> seen[profile] = index }
        return seen.entries.sortedBy { it.value }.map { it.key }
    }

    private fun validateName(name: String) {
        check(NAME_REGEX.matches(name)) {
            "Kprofiles: invalid profile name '$name'. Use only letters, digits, '.', '_' or '-'. Example: 'brand-alpha'."
        }
    }

    private fun String.stripOuterQuotes(): String {
        if (length < 2) return this
        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, length - 1)
        } else {
            this
        }
    }

    internal data class ProfileSelection(
        val source: ProfileSource,
        val profiles: List<String>
    )

    internal enum class ProfileSource { CLI, ENV, DEFAULT, NONE }

    private companion object {
        private const val GRADLE_PROP_NEW = "kprofiles.profiles"
        private const val ENV_VAR_NEW = "KPROFILES_PROFILES"
        private val NAME_REGEX = "^[a-zA-Z0-9._-]+$".toRegex()
    }
}
