# Offline PDF Editor — Technical Plan

Status: Phases 1 and 2 are implemented and verified on an emulator (API 37). **Phase 4 (Word conversion) was removed on 2026-11-18.** The v1 scope and its phases are in [`V1_PLAN.md`](V1_PLAN.md), which replaces §6 below. Sections that talk about DOCX are kept only as history. The removed code is archived in `D:\VIBECODE\pdf-editor-backup-2026-11-18.zip`.

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-11-18 | Removed Compress (screen, viewer menu item, home card, `Compressor` engine and tests). | Testing on 25 real PDFs showed it rarely shrank anything: pdfbox-android can't write object streams, so rewriting often made files larger, and JPEG 2000 / CMYK images can't be decoded safely. See `V1_PLAN.md` → Decisions. Archived in `D:\VIBECODE\pdf-editor-backup-2026-11-18-before-compress-removal.zip`. |
| 2026-11-18 | Removed Word ↔ PDF conversion (`:core:convert`, the convert UI, and the .docx "Open with" filter). | The output was buggy. v1 sticks to formats with deterministic, native rendering: PDF and images. |
| 2026-11-18 | Every save flattens form fields and annotations into page content. There is no separate form-filling UI; users add text on top. | One simple model. What users see is exactly what everyone else sees. Trade-off: outputs are no longer fillable (see V1_PLAN §2c). |
| 2026-11-18 | Whiteout removes what's underneath (glyphs, image pixels, vector paths), not just covers it. | A whiteout that leaves the text copyable is a privacy trap. |
| 2026-11-18 | Existing-text editing stays in v1. | It works well in testing. |
| 2026-11-18 | v1 core features implemented: Merge & organize (new `:core:tools`), Whiteout with true removal, flatten on save, stamps/initials, and Compress (later removed, see above). Detailed decisions are in `V1_PLAN.md` → Implementation notes. | Phase 0–5 of the v1 plan. |
| 2026-11-18 | Added `sh.calvin.reorderable` 2.5.1 for drag-to-reorder. | Apache 2.0 and small. Writing reliable grid drag-and-drop by hand isn't worth the maintenance. |
| 2026-11-17 | Encrypted PDFs on API 26–34: decrypt with PDFBox into an app-private cache file that is deleted on close (and swept on startup). API 35+ uses `PdfRenderer` `LoadParams` natively. | Avoids ~5 MB/ABI of PDFium JNI. The plaintext temp file lives only in the app sandbox, which is the same trust boundary as the app's memory. |
| 2026-11-17 | Saving defaults to **a new copy**. After "Save as copy" the viewer switches to the copy and clears overlays from the original. | It's never ambiguous which file holds the edits, and the original is never touched by default. |
| 2026-11-17 | Overwrite = write to temp → validate by reopening → back up the original → write → restore the backup on failure. | Storage Access Framework providers can't atomically rename, so the backup gives the same guarantee. |
| 2026-11-17 | Type 3 fonts are editable (glyph removal works). Replacement text uses a bundled font, and the user is told. | Android's own PDF generator (Skia) emits Type 3, so blocking it would have made many phone-made PDFs uneditable. |
| 2026-11-17 | Pending edits are stored as a crash-safe draft per document and restored on reopen with a Discard option. | Nothing is lost if the app is killed mid-edit. |
| 2026-11-17 | Conversion fonts: Word → PDF maps document fonts to the bundled Noto Sans/Serif (or the system monospace). Proprietary fonts (Calibri, Aptos) can't legally be bundled. The report names the substituted fonts. | A licensing constraint. Page breaks still matched Word on the test document. |
| 2026-11-17 | Word → PDF text is laid out at 10× resolution, with an explicit per-line pitch. | `StaticLayout` skips the spacing multiplier on a paragraph's last line, and integer font metrics drift. Both made output measurably taller than Word. |
| 2026-11-17 | PDF → Word moves running headers/footers into the Word header/footer and turns page numbers back into fields. Ligatures are expanded, and conversion PDFs are written without ligatures. | This keeps page structure and produces searchable, editable text in any app. |
| 2026-11-17 | Legacy .doc (Word 97–2003) gets a clear "save as .docx" message rather than an attempt. | It's a binary format, and no lightweight, permissively licensed reader exists. |
| 2026-11-17 | Bundled fonts: Noto Sans and Noto Serif, Regular and Bold (SIL OFL 1.1, license at `core/editing/src/main/assets/fonts/OFL.txt`). Italic is a synthetic shear, identical on screen and in the PDF. | About 2.7 MB. The same TTFs drive the preview and the embedded subset, so layout matches exactly. |

## 1. Requirements analysis (summary)

Hard constraints that drive every decision:

| Constraint | Consequence |
|---|---|
| Fully offline, no backend | No `INTERNET` permission at all. Every engine must be on-device. |
| Fixed one-time price (paid up front), no in-app purchases or ads, closed source (see `PRODUCT_DECISIONS.md`) | **No AGPL/GPL dependencies** (rules out MuPDF, iText, Ghostscript unless a commercial license is bought). |
| Lean, low maintenance | Prefer platform APIs; avoid huge native stacks (LibreOffice). |
| Never corrupt originals | All writes go to a new file by default; overwrite is an explicit, atomic (write-temp-then-swap) action. |
| No broad storage access | Storage Access Framework only. No `READ/WRITE_EXTERNAL_STORAGE`, no `MANAGE_EXTERNAL_STORAGE`. |

## 2. Library recommendations and licensing

| Purpose | Choice | License | Why / implications |
|---|---|---|---|
| Page rendering | **Android `PdfRenderer`** (platform, PDFium-backed) | Part of Android (Apache 2.0) | Zero APK cost, fast native rendering, maintained by Google. Limitation: password-protected files only on API 35+ (`LoadParams`). |
| Structure, editing, text extraction, encryption, saving | **PDFBox-Android** (`com.tom-roush:pdfbox-android`) | Apache 2.0 | Safe for closed-source commercial use; keep NOTICE file in the about screen. Supports RC4/AES-128/AES-256 encryption, user/owner passwords, permissions, text extraction with glyph positions, font embedding. Too slow for on-screen rendering — used only for processing. ~3–4 MB. |
| DOCX read/write | **Custom OOXML engine** (ZIP + Android `XmlPullParser` / streaming XML writer) | Ours | Apache POI isn't a good fit for Android (it relies on java.awt and StAX, has a huge method count and adds 15 MB+, and the Android ports are stale). docx4j relies on JAXB and is heavy. DOCX is a documented ZIP-of-XML format, and the subset we need (paragraphs, runs, styles, numbering, tables, images, sections) is tractable. |
| DOCX → PDF layout | **Custom layout engine** on `StaticLayout` + `android.graphics.pdf.PdfDocument` | Platform | Android's Skia PDF backend emits real, selectable text with embedded font subsets, which is not image-only output. |
| OCR (optional, after Phase 4) | **Tesseract4Android** + bundled `traineddata` | Apache 2.0 | Fully offline. ML Kit's bundled model also works offline, but it's a proprietary Google SDK that may send diagnostics, which conflicts with the privacy promise. |

Rejected:
- **MuPDF**: AGPL. Using it would require open-sourcing the whole app or buying an Artifex commercial license, which isn't viable at this app's price point.
- **iText 7**: AGPL or commercial, so it's ruled out for the same reason.
- **LibreOffice core (MPL)**: the license is fine, but it adds 100 MB+ of native code and a large maintenance burden.
- **Aspose / PSPDFKit / PDFTron (Apryse)**: commercial SDKs that cost thousands per year, which doesn't fit the price point.
- **PdfiumAndroid JNI bindings (Apache 2.0)**: kept as a fallback if `PdfRenderer` proves insufficient. It adds about 5 MB per ABI, and the popular forks are lightly maintained.

## 3. Technically difficult features and honest limitations

1. **Editing existing PDF text (hardest).** A PDF has no paragraphs or text flow. It stores positioned glyph runs, often with *subset* fonts that only contain the characters already in the document, custom encodings, or Type 3 fonts.
   - Plan: parse the content stream with PDFBox, identify the text-show operators for a selected run, then **remove those operators** (a real deletion, not a white box drawn over them) and write the replacement text at the same position, size and color.
   - Font: reuse the original font if it contains every needed glyph. Otherwise substitute the closest bundled font and **tell the user** a font was substituted.
   - Edits do not reflow a whole paragraph. Each run is edited on its own line.
   - Documents we can't modify (Type 3 fonts, text inside complex form XObjects, documents whose owner permissions forbid editing) show a clear message. We never silently fall back to an overlay.
   - "Add text" is a separate tool and is labelled as added text.
2. **PDF → DOCX fidelity.** We'll reconstruct the layout from glyph positions: lines become paragraphs, font-size statistics identify headings, glyph patterns identify bullets and numbering, ruling lines and column alignment identify tables, and images are extracted. Simple documents convert well. Multi-column layouts, forms, rotated text and scanned pages get a fidelity warning before export. Scanned pages need OCR, and without it they're reported as "no text layer".
3. **DOCX → PDF fidelity.** Supported: styles, headings, bold/italic/underline, size, color, font family (mapped to bundled fonts), alignment, spacing and indents, bulleted and numbered lists, tables (borders, column widths, basic merged cells), inline images, page size and margins, page breaks, and headers and footers. **Not supported:** floating shapes and text boxes, SmartArt, equations, tracked changes, comments and fields beyond page numbers. The app lists any unsupported elements it found in the file.
4. **Password-protected files on API < 35.** `PdfRenderer` can't open them. Options:
   - (a) Decrypt with PDFBox into a file in the app's private cache, deleted on close. This leaves a plaintext temp file inside the app sandbox.
   - (b) Bundle PDFium JNI, which adds about 5 MB per ABI.
   - **Recommendation: (a) on API 26–34 and native `LoadParams` on 35+.** This is a decision for Phase 3.
5. **Search highlighting.** This needs per-glyph bounding boxes. PDFBox `TextPosition`s give user-space coordinates, which we map to rendered pixels. Extraction runs page by page in the background, so results stream in while extraction continues.

## 4. Architecture and modules

MVVM with unidirectional state (`StateFlow`) and manual DI (an `AppContainer`, without Hilt, to keep things simple).

```
:app            Compose UI, navigation, ViewModels, theme / design system
:core:files     SAF access, persisted URI grants, recent documents, copy/rename/share, save-to-folder
:core:pdf       Rendering session (PdfRenderer), region rendering, bitmap cache, page geometry
:core:editing   PDFBox snapshot, text analysis (GlyphCollector → TextRunGrouper),
                ContentStreamEditor (real glyph removal), whiteout (BackgroundSampler,
                RedactionScanner, Redactor), flatten (Flattener, PageRasterizer),
                PdfEditWriter, fonts, signatures/initials/stamps, drafts, undo history
:core:tools     Merge/organize/split (DocumentAssembler, SplitPlanner), photo pages
                (ImageDecoding, ImageLayout)
:core:search    (Phase 3, not started) Incremental text extraction + match index
:core:security  (Phase 3, not started) Encrypt / decrypt / permissions via PDFBox
```

Dependencies flow one way: `app → core:*`. Core modules depend only on `core:pdf`, except `core:tools → core:editing` (it shares the PDFBox setup).

## 5. Native code / custom processing

- No hand-written native code is planned. PDFium comes with the platform.
- The custom (Kotlin) engines are the OOXML reader/writer, the layout analysis for PDF → DOCX, the layout engine for DOCX → PDF, and the content-stream text editor.
- Tesseract (optional OCR) would add prebuilt native libraries.

## 6. Implementation sequence

1. **Phase 1 (done): foundation.** Project, theme, SAF import, recent documents, virtualized viewer with zoom, thumbnails, last position, share / save copy / rename, and `ACTION_VIEW` intent handling.
2. **Phase 2 (done): core editing.** Bring in PDFBox. Annotation layer model; add-text overlays; signature pad and saved signatures (stored as vector strokes in app storage); move, resize and delete; save-as-copy pipeline; then existing-text editing as described in §3.1.
3. **Phase 3: security and search.** Add, change and remove passwords; user/owner passwords and permissions; open-with-password flow; incremental search with highlights.
4. **Phase 4 (done): conversion.** OOXML writer → PDF → DOCX, then OOXML reader → DOCX → PDF, with progress, cancellation and fidelity reports.
5. **Phase 5: quality.** Large-document profiling (500+ pages, image-heavy), low-memory handling (`onTrimMemory`), a corpus of malformed files, accessibility pass, device matrix.

Each output file gets validated in independent readers: Acrobat, Chrome or pdf.js, and `qpdf --check` for PDFs; Word and LibreOffice for DOCX.

## 7. Phase 1 notes

- The home screen only shows tools that work in the current build. Conversion and signature entry points appear when those phases land, so there are no placeholder buttons.
- In Phase 1, encrypted PDFs show a clear "password-protected" error state. Phase 3 replaces it with the password prompt.
