package dev.goquick.kprofiles

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileResolverTest {

    @Test
    fun `cli property wins over extension`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        extension.defaultProfiles.set(listOf("theme-green"))
        val resolver = ProfileResolver(project, extension)

        val selection = resolver.resolveProfiles(cliStackRaw = "theme-blue,brand-alpha", envStackRaw = null)
        assertEquals(ProfileResolver.ProfileSource.CLI, selection.source)
        assertEquals(listOf("theme-blue", "brand-alpha"), selection.profiles)
    }

    @Test
    fun `environment variable used when cli missing`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        val resolver = ProfileResolver(project, extension)

        val selection = resolver.resolveProfiles(cliStackRaw = null, envStackRaw = "theme-green,brand-bravo")
        assertEquals(ProfileResolver.ProfileSource.ENV, selection.source)
        assertEquals(listOf("theme-green", "brand-bravo"), selection.profiles)
    }

    @Test
    fun `active profiles fallback when no cli or env`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        extension.defaultProfiles.set(listOf("brand-alpha"))

        val resolver = ProfileResolver(project, extension)
        val selection = resolver.resolveProfiles(cliStackRaw = null, envStackRaw = null)

        assertEquals(ProfileResolver.ProfileSource.DEFAULT, selection.source)
        assertEquals(listOf("brand-alpha"), selection.profiles)
    }

    @Test
    fun `invalid profile name throws`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        val resolver = ProfileResolver(project, extension)

        assertFailsWith<IllegalStateException> {
            resolver.resolveProfiles(cliStackRaw = "bad/name", envStackRaw = null)
        }
    }

    @Test
    fun `overlay dir expands placeholder`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        extension.profileDirPattern.set("overlays/%PROFILE%")

        val resolver = ProfileResolver(project, extension)
        assertEquals("overlays/theme-blue", resolver.overlayDir("theme-blue").get())
    }

    @Test
    fun `none set yields shared only`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create("kprofiles", KprofilesExtension::class.java)
        val resolver = ProfileResolver(project, extension)

        val selection = resolver.resolveProfiles(cliStackRaw = null, envStackRaw = null)
        assertEquals(ProfileResolver.ProfileSource.NONE, selection.source)
        assertEquals(emptyList(), selection.profiles)
    }
}
