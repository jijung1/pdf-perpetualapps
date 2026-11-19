package app.folio.ui.about

/**
 * Everything third-party that ships inside the APK, with the license texts that must accompany it.
 * Texts live in assets (verbatim copies). When you add a dependency, add it here as well;
 * `OpenSourceLicensesTest` checks that every referenced text exists.
 *
 * Not listed: Android's own PdfRenderer (PDFium) and fonts, which are part of the OS, not the app.
 */
data class OssComponent(
    val name: String,
    val owner: String,
    val license: String,
    val url: String,
    /** What it does in Folio, in one line. */
    val usage: String,
    /** Asset paths of the texts to show, in order (notice first, then license). */
    val texts: List<String>,
)

object OpenSourceLicenses {

    const val APACHE = "licenses/apache-2.0.txt"
    const val PRIVACY_POLICY = "legal/privacy-policy.txt"

    val components = listOf(
        OssComponent(
            name = "PdfBox-Android (Apache PDFBox)",
            owner = "Tom Roush; The Apache Software Foundation",
            license = "Apache License 2.0",
            url = "https://github.com/TomRoush/PdfBox-Android",
            usage = "Reading, editing, merging and splitting PDF files",
            texts = listOf("licenses/pdfbox-android-NOTICE.txt", APACHE),
        ),
        OssComponent(
            name = "Adobe Glyph List and ITC Zapf Dingbats Glyph List",
            owner = "Adobe Systems Incorporated",
            license = "BSD 3-Clause",
            url = "https://github.com/adobe-type-tools/agl-aglfn",
            usage = "Character names in PDF fonts (included in PdfBox-Android)",
            texts = listOf("licenses/adobe-glyph-list-BSD.txt"),
        ),
        OssComponent(
            name = "Unicode Character Database",
            owner = "Unicode, Inc.",
            license = "Unicode License v3",
            url = "https://www.unicode.org/ucd/",
            usage = "Script and mirroring data for text (included in PdfBox-Android)",
            texts = listOf("licenses/unicode-license-v3.txt"),
        ),
        OssComponent(
            name = "Liberation Sans",
            owner = "Google Corporation; Red Hat, Inc.",
            license = "SIL Open Font License 1.1",
            url = "https://github.com/liberationfonts/liberation-fonts",
            usage = "Fallback font for PDFs whose fonts are missing (included in PdfBox-Android)",
            texts = listOf("licenses/liberation-sans-OFL.txt"),
        ),
        OssComponent(
            name = "Noto Sans and Noto Serif",
            owner = "The Noto Project Authors",
            license = "SIL Open Font License 1.1",
            url = "https://github.com/notofonts/latin-greek-cyrillic",
            usage = "Fonts for text you add to documents",
            texts = listOf("fonts/OFL.txt"),
        ),
        OssComponent(
            name = "Reorderable",
            owner = "Calvin Liang",
            license = "Apache License 2.0",
            url = "https://github.com/Calvin-LL/Reorderable",
            usage = "Dragging pages into a new order",
            texts = listOf(APACHE),
        ),
        OssComponent(
            name = "AndroidX and Jetpack Compose",
            owner = "The Android Open Source Project",
            license = "Apache License 2.0",
            url = "https://developer.android.com/jetpack/androidx",
            usage = "App framework, screens and navigation",
            texts = listOf(APACHE),
        ),
        OssComponent(
            name = "Kotlin, kotlinx.coroutines, kotlinx.serialization, atomicfu and Compose Multiplatform runtime",
            owner = "JetBrains s.r.o. and Kotlin Programming Language contributors",
            license = "Apache License 2.0",
            url = "https://kotlinlang.org",
            usage = "Programming language and libraries",
            texts = listOf(APACHE),
        ),
        OssComponent(
            name = "JetBrains Java Annotations",
            owner = "JetBrains s.r.o.",
            license = "Apache License 2.0",
            url = "https://github.com/JetBrains/java-annotations",
            usage = "Code annotations",
            texts = listOf(APACHE),
        ),
        OssComponent(
            name = "Guava ListenableFuture",
            owner = "The Guava Authors",
            license = "Apache License 2.0",
            url = "https://github.com/google/guava",
            usage = "Used internally by AndroidX",
            texts = listOf(APACHE),
        ),
        OssComponent(
            name = "JSpecify",
            owner = "The JSpecify Authors",
            license = "Apache License 2.0",
            url = "https://jspecify.dev",
            usage = "Code annotations used by AndroidX",
            texts = listOf(APACHE),
        ),
    )
}
