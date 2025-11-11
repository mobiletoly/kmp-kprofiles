package dev.goquick.kprofiles

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilesCopyUtilsTest {

    @Test
    fun `matches allowed top level exact`() {
        assertTrue(matchesAllowedTopLevel("values", setOf("values", "drawable")))
        assertFalse(matchesAllowedTopLevel("fonts", setOf("values")))
    }

    @Test
    fun `matches qualifier variants`() {
        val allowed = setOf("values", "drawable")
        assertTrue(matchesAllowedTopLevel("values-es", allowed))
        assertTrue(matchesAllowedTopLevel("drawable-anydpi-v26", allowed))
        assertFalse(matchesAllowedTopLevel("font-es", allowed))
        assertFalse(matchesAllowedTopLevel("valueses", allowed))
    }

    @Test
    fun `matches ignores case`() {
        val allowed = setOf("Values", "DRAWABLE")
        assertTrue(matchesAllowedTopLevel("values", allowed))
        assertTrue(matchesAllowedTopLevel("Drawable-xxhdpi", allowed))
    }

    @Test
    fun `describe allowed folders spells variants`() {
        val description = describeAllowedFolders(setOf("files", "values"))
        assertEquals("files/, values/ or values-*/", description)
    }

    @Test
    fun `describe allowed folders sorts entries`() {
        val description = describeAllowedFolders(setOf("drawable", "values"))
        assertEquals("drawable/ or drawable-*/, values/ or values-*/", description)
    }

    @Test
    fun `relative path normalizes output`() {
        val project = ProjectBuilder.builder().withProjectDir(java.io.File("/tmp/example"))
            .build()
        val nested = java.io.File(project.projectDir, "nested/dir")
        nested.mkdirs()
        val relative = relativePath(project, nested)
        assertEquals("nested/dir", relative)
    }

    @Test
    fun `relative path marks absolute outside root`() {
        val project = ProjectBuilder.builder().withProjectDir(java.io.File("/tmp/example"))
            .build()
        val outside = java.io.File("/var")
        val relative = relativePath(project, outside)
        assertTrue(relative.startsWith("[abs]"))
    }
}
