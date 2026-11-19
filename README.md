# Folio — offline PDF editor for Android

A private PDF editor that runs entirely on the device. It has no internet permission, accounts, ads or analytics.

v1 scope is **PDF and photos (JPG / PNG / HEIC)** only: Merge & organize, and Fill & sign. Word conversion and Compress were removed on 2026-11-18. See [`docs/V1_PLAN.md`](docs/V1_PLAN.md) for the plan and decisions, and [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md) for libraries, licensing and history.

## Build

Requirements: a **64-bit** JDK 17+ (pinned in `gradle.properties` via `org.gradle.java.home`) and an Android SDK with platform 36 (path set in `local.properties`).

```powershell
.\gradlew.bat assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat testDebugUnitTest           # JVM unit tests
.\gradlew.bat connectedDebugAndroidTest   # device/emulator round trips and stress tests
```

## Modules

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, navigation, ViewModels, design system |
| `:core:files` | Storage Access Framework, recent documents, thumbnails, copy/rename/share, save-to-folder |
| `:core:pdf` | On-demand page rendering (`PdfRenderer`), region rendering for colour sampling, byte-bounded bitmap cache |
| `:core:editing` | PDFBox editing engine: text analysis, glyph removal, whiteout redaction, flattening, writer, fonts, signatures, drafts |
| `:core:tools` | Merge/organize/split (`DocumentAssembler`, `SplitPlanner`) and photo pages |

## What works

**Viewing:** open from the picker, "Open with" or the share sheet; recents with thumbnails and last position; virtualized pages with pinch and double-tap zoom; thumbnails, go-to-page, share, save a copy, rename.

**Fill & sign** (in the viewer):
- **Add text**, and tap inside a form box to have the text line up and size itself to fit. Dropping text into a field snaps it in place.
- **Edit existing text:** the original glyphs are removed, not covered.
- **Sign** with saved signatures, and **Stamp**: ✓, ✗, today's date, or saved initials. Stamps centre themselves in form boxes.
- **Whiteout:** drag a box. While it's selected, it turns see-through and outlines exactly which characters will be deleted, including any that stick out past the box. The fill colour matches the paper automatically (adaptive background sampling), or you can choose white, a swatch, or the eyedropper.
  - **On save, everything under the box is removed:** glyphs (including inside shared embedded objects and invisible OCR layers), image pixels, and drawings. Then the box is filled.
  - The result is checked before the file is written. If content can't be removed in place (for example, an image format the device can't decode), that page is converted to an image instead, and you're told.
- **Every save flattens** form fields and comments into the page. Links stay clickable. The save dialog says so whenever it applies.

**Merge & organize:**
- Combine any mix of PDFs and photos. Pages are colour-coded by source file.
- Tap to select. Press and hold to drag a page to a new position.
- Rotate, duplicate, extract or delete pages, with undo/redo throughout.
- Photo pages can be A4, Letter (the default follows your region) or fit-to-photo, with optional margins. HEIC needs Android 9+.
- Pages are copied, not re-rendered, so text stays sharp. Links and form fields that would pull in pages you left out are dropped, so nothing leaks into the output.
- **Split** by selected pages, by ranges (`1-3, 5, 8-` with live validation), every N pages, or one file per page. All the files are saved to a folder you pick.

Every tool writes to a temporary file, reopens and checks it, and only then offers Save / Share / Open. Originals are never changed.

**About** (home → ⓘ): privacy promises, the in-app privacy policy, plain-language notes on signatures, whiteout and flattening, and open-source licenses with verbatim texts. The pre-release checklist (Play Console Data safety answers, privacy policy hosting and the rest) is in [`docs/PLAY_COMPLIANCE.md`](docs/PLAY_COMPLIANCE.md).

## Tests

- **61 JVM unit tests:** background sampler, redaction rules, split ranges, photo layout, text grouping, and more.
- **30 instrumented tests** on an emulator:
  - Whiteout really removes text, form text, OCR text and image pixels, including CMYK documents and owner-protected PDFs.
  - Edit text works after the `'` and `"` text operators, and falls back to a bundled font when the original lacks widths.
  - Forms flatten and links are kept.
  - Merge, reorder, rotation, duplicates, link remapping, no leaking of left-out pages, password errors.
  - A 400-page reorder and a merge of twelve 12-megapixel photos, under the real heap limit.
- **Real-world corpus:** 25 public PDFs run through every feature. See [`testdata/corpus/README.md`](testdata/corpus/README.md); run with `powershell -ExecutionPolicy Bypass -File testdata\run-corpus.ps1`. Three tests (the corpus and two local-file checks) only run when files have been copied to the device.
