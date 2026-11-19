package app.folio.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpenSourceLicensesTest {

    /** Assets merged into the APK come from :app and the library modules. */
    private val assetRoots = listOf("src/main/assets", "../core/editing/src/main/assets", "../core/tools/src/main/assets").map(::File)

    private fun asset(path: String) = assetRoots.map { File(it, path) }.firstOrNull { it.isFile }

    @Test
    fun everyLicenseTextShipsWithTheApp() {
        val paths = OpenSourceLicenses.components.flatMap { it.texts } + OpenSourceLicenses.PRIVACY_POLICY
        val missing = paths.filter { asset(it) == null }
        assertTrue("missing license texts: $missing", missing.isEmpty())
        paths.forEach { assertTrue("$it is empty", asset(it)!!.length() > 200 || it.endsWith("NOTICE.txt")) }
    }

    @Test
    fun apacheTextIsTheRealLicense() {
        val text = asset(OpenSourceLicenses.APACHE)!!.readText()
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
    }

    @Test
    fun sourceManifestRequestsNoPermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("Folio must not request permissions", manifest.contains("<uses-permission"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }

    @Test
    fun reflowJoinsOnlyHardWrappedLines() {
        val wrapped = "Licensed under the Apache License, Version 2.0 (the \"License\"); you may\nnot use this file except in compliance with the License.\n\nShort title\nNext line"
        assertEquals(
            "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in compliance with the License.\n\nShort title\nNext line",
            reflow(wrapped),
        )
        // Numbered clauses keep their own line.
        assertEquals("a".repeat(70) + "\n1. Definitions.", reflow("a".repeat(70) + "\n1. Definitions."))
    }
}
