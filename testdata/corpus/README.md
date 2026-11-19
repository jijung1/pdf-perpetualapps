# Public PDF test corpus

25 real PDFs used by `core/tools/src/androidTest/.../CorpusTest.kt`. They are not bundled into the app or its test APKs; `testdata/run-corpus.ps1` copies them into the test app's private storage on a device or emulator, runs every feature against each file, and prints a results table.

| File | What it exercises | Source |
|---|---|---|
| gov-irs-w9-form.pdf | Fillable government form (AcroForm) | irs.gov/pub/irs-pdf/fw9.pdf |
| gov-irs-1040-form.pdf | Dense fillable form | irs.gov/pub/irs-pdf/f1040.pdf |
| gov-irs-pub17-large.pdf | Large document (~300 pages), links and bookmarks | irs.gov/pub/irs-pdf/p17.pdf |
| gov-uscis-i9-form.pdf | Government form (often owner-protected) | uscis.gov … /forms/i-9.pdf |
| brochure-nps-2011-guide.pdf | Print brochure (CMYK, large images, vector map) | npshistory.com/brochures/nps-2011.pdf |
| scan-declaration-archive-org.pdf | Scanned book from Internet Archive (image-only) | archive.org/download/declarationofind00unit_4 |
| paper-arxiv-attention.pdf | LaTeX paper (Type 1 fonts, math) | arxiv.org/pdf/1706.03762 |
| paper-tracemonkey.pdf | Academic paper | pdf.js test suite |
| paper-tamreview.pdf | Journal article | pdf.js test suite |
| cmyk-jpeg.pdf | CMYK JPEG image | pdf.js test suite |
| scan-jbig2.pdf | JBIG2-compressed scan | pdf.js test suite |
| scan-ccitt-fax.pdf | CCITT fax-compressed scan | pdf.js test suite |
| image-jpeg2000.pdf | JPEG 2000 images | pdf.js test suite |
| form-xfa.pdf | XFA (Adobe LiveCycle) form | pdf.js test suite |
| form-text-widgets.pdf | Text-field widgets | pdf.js test suite |
| form-checkbox-no-appearance.pdf | Checkbox without an appearance stream | pdf.js test suite |
| annot-highlights.pdf | Highlight annotations | pdf.js test suite |
| annot-freetext.pdf | Free-text annotations | pdf.js test suite |
| font-type3.pdf | Type 3 font | pdf.js test suite |
| text-vertical-cjk.pdf | Vertical CJK text | pdf.js test suite |
| text-arabic-cid.pdf | Arabic, CID TrueType font | pdf.js test suite |
| page-rotation.pdf | Rotated pages | pdf.js test suite |
| transparency-alpha.pdf | Transparency | pdf.js test suite |
| structure-many-forms.pdf | Many nested form XObjects | pdf.js test suite |
| encrypted-user-password.pdf | Needs a password to open (should be refused cleanly) | pdf.js test suite |

pdf.js files come from https://github.com/mozilla/pdf.js/tree/master/test/pdfs. US government works are public domain; the arXiv paper is the authors' public preprint. These files are for local testing only and are not redistributed with the app.
