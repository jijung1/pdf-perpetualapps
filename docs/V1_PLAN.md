# Folio v1 plan: core features

> **Status (2026-11-18): all phases (0–5) implemented and verified.**
> - Tests: 61 JVM unit tests and 30 instrumented tests pass on the emulator, plus a 25-file real-world corpus (`testdata/corpus`). A minified release build was smoke-tested.
> - The UI was walked through on a 1080×2400 emulator: merge, reorder, rotate, save, open, whiteout, the save dialog and stamps.
> - Choices made during implementation, and the known limitations, are listed at the end of this file.
> - **Compress was removed on 2026-11-18** (see Decisions and Phase 4).

Agreed 2026-11-18. Scope is **PDF and images (JPG/PNG/HEIC) only**:
1. **Merge & organize:** combine PDFs and images, reorder, rotate, delete, split.
2. **Fill & sign:** text boxes, stamps, saved signature, whiteout (true removal), flatten on save. Existing-text editing stays.
3. ~~**Compress:** shrink files offline.~~ Removed 2026-11-18.

Everything must be polished: clear wording, undo everywhere, honest messages, no dead ends.

## Decisions
| Decision | Notes |
|---|---|
| Word conversion removed | Archived in `D:\VIBECODE\pdf-editor-backup-2026-11-18.zip`. |
| Existing-text editing stays | — |
| No interactive form filling; every save flattens | Users fill forms by adding text on top. See §2c for the trade-offs. |
| Whiteout removes content underneath | Not just a covering rectangle. See §2b. |
| Compress removed (2026-11-18) | Testing on 25 real PDFs showed it rarely helped. pdfbox-android rewrites files in a less compact layout (no object streams), which often made them larger, and the images that carry the size (JPEG 2000 scans, CMYK print images) can't be decoded safely. A feature that usually says "already as small as possible" hurts more than it helps. The code is archived in `D:\VIBECODE\pdf-editor-backup-2026-11-18-before-compress-removal.zip`. |

---

## Phase 0: Remove conversion (done 2026-11-18)
Removed `:core:convert`, `app/ui/convert`, the Convert route, the home tool cards, the viewer "Convert to Word" item and the .docx intent filter. The FileProvider path is now `work/out/` for tool outputs. Build and unit tests pass.

## Phase 1: Shared output pipeline
- **`PdfJob`:** write to temp → reopen and check page count → export (SAF) or share (FileProvider). It reports progress, supports cancel, and maps errors to `EditError`. The temp/validate code moves here from `PdfEditWriter`.
- **One results sheet for all tools:** name, size, page count, and Open / Share / Save.
- **Inputs:**
  - Password-protected files use the existing decrypt-to-cache path. The output is unprotected, and the UI says so.
  - If owner permissions forbid `canAssembleDocument`, merge and split stop with a clear message.
- **Home screen:** Open a PDF · **Merge & organize** · **Fill & sign** · Signatures · Recents.

## Phase 2: Fill & sign

### 2a. Whiteout tool
- **Model:** `Whiteout(id, page, refPageWidth, box, fill, fillSource = AUTO|WHITE|MANUAL, sampleConfidence)` in `EditModel.kt`. The resolved colour is stored in the edit, so saving always gives the same result.
- **Tool:** `EditTool.WHITEOUT`.
  - Drag to draw a box. It has four corner handles and can be moved.
  - A colour chip offers Auto / White / Pick (swatches plus an eyedropper).
  - Moving or resizing re-samples the colour after a 250 ms pause. A colour picked by hand is never overwritten.
- **Live preview:** while the box is selected, the glyphs that will be removed are outlined. If some extend past the box, a note says "2 characters extend past the box and will be removed too".

### 2b. True removal on save
The save pipeline removes what is under each whiteout box, on the page itself and inside any form XObjects drawn on it:
- **Text:** every glyph whose bounds intersect the box is removed, using `GlyphCollector` + `ContentStreamEditor`. That includes glyphs inside form XObjects.
- **Form XObjects:** a form XObject may be shared, such as a letterhead used on every page. It is copied for this page before editing, so other pages don't change.
- **Images:** each image XObject or inline image whose placement intersects the box is handled like this:
  - Decode it, map the box into image pixel space, paint the fill colour, and re-encode it.
  - JPEG stays JPEG at a similar quality; other images are re-encoded lossless.
  - Shared images are cloned first.
- **Vector paths:** subpaths fully inside the box are deleted. Paths that cross the box edge are clipped out with an even-odd clip.
  - Limitation (documented): the geometry of a path that is only partly covered stays in the file. It contains no extractable text.
- **Fill:** the fill rectangle is drawn on top, then added text and signatures.
- **Check after saving:** the text inside every whiteout box is extracted again and must be empty. If anything is left, the save **fails** with a message rather than leaking content.
- **Unit tests:** glyph–box intersection, image pixel-space mapping, and a round trip (pdfbox-android).

### 2c. Flatten on every save
Order of the save pipeline:
1. **Flatten form fields:**
   - Run `PDAcroForm.refreshAppearances()` (needed when `NeedAppearances` is set), then `flatten()`.
   - Drop the AcroForm and XFA dictionaries and form JavaScript.
2. **Flatten other annotations:**
   - Draw each one's normal appearance stream into the page as a form XObject (mapping BBox to Rect), then remove the annotation.
   - Respect the Hidden and NoView flags (those annotations are dropped).
   - **Keep `Link` annotations** so links stay clickable. Popup annotations are dropped with their parent.
3. Whiteout removal (§2b). It runs after flattening, so it also removes flattened field values.
4. Existing-text replacements.
5. Overlays: whiteout fills, then added text and stamps, then signatures.

The output keeps selectable text. Rasterising pages isn't needed, because whiteout really removes content.

**Concerns with "add text on top, then flatten" (accepted for v1):**
- **Field appearances could hide added text.** A field's appearance (white background, border or existing value) would cover text placed under it. Flattening happens *before* the overlays are drawn, so added text always ends up on top.
- **The output is no longer a fillable form.** Field data is gone, so portals that read form fields (some government or HR systems) get a picture of the form, not the data. Tell the user once on the first save of a document with form fields: "Form fields will be flattened. The PDF will look filled in, but won't be fillable."
- **Lining text up with boxes is manual.** There's no snapping to fields. Mitigation: an optional "snap to field" that uses the field rectangles as guides when a text box is dropped nearby. This is cheap, because the rectangles are read before flattening.
- **Checkboxes and radio buttons:** covered by the ✓ / ✗ stamps (§2d).
- **Fields without appearance streams:** `refreshAppearances` can fail on some fonts with pdfbox-android. Fallback: draw the field value with the bundled font. If it can't be drawn at all, report it rather than drop it silently.
- **Calculated or scripted fields:** these stop working after flattening. That's acceptable, since flattening freezes them.

### 2d. Stamps and initials
- ✓, ✗ and date stamps (`AddedText` presets).
- Saved initials alongside the saved signature (`SignatureStore`).

### 2e. Background sampler for Auto colour
`core/editing/whiteout/BackgroundSampler.kt` is pure Kotlin that works on an `IntArray`, so it can be unit-tested on the JVM.

```kotlin
data class SamplingConfig(
    val ringWidthPt: Float = 6f,
    val innerGapPt: Float = 1f,
    val renderDpi: Int = 150,
    val minLuma: Int = 180,
    val clusterBinBits: Int = 4,
    val clusterTolerance: Float = 18f,
    val minSamples: Int = 40,
    val minDominantShare: Float = 0.55f,
)
sealed interface SampleResult {
    data class Confident(val argb: Int, val samples: Int, val share: Float) : SampleResult
    data class LowConfidence(val reason: Reason) : SampleResult
}
```

Steps:
1. Render the ring around the box from the **original** page at `renderDpi`. The ring runs from `gap` to `gap + ring` outside the box and is clipped to the page.
2. Compute luma `0.299R + 0.587G + 0.114B` and drop pixels below `minLuma`.
3. Find the dominant cluster: the largest bin of a quantised histogram plus its neighbours gives a centroid. Keep only pixels within `clusterTolerance` of it.
4. Take the median of each RGB channel.
5. Check confidence: at least `minSamples` pixels kept **and** a dominant share of at least `minDominantShare`. Otherwise return `LowConfidence`, fall back to white, and show "Couldn't match the background" with a Pick button.
6. All thresholds live in `SamplingConfig`, plus a debug-only tuning panel.

Tests: plain cream, cream with text, a drop shadow, a dark border, a box at the page edge, a speckled scan, an all-dark page (→ LowConfidence), and a light-grey field next to white.

## Phase 3: Merge & organize
- **New module `:core:organize`.** The model is a list of `PageRef(sourceId, pageIndex, rotation)` and `ImageRef(uri, rotation)`. Nothing is written until export.
- **Writer:**
  - `importPage` with `MemoryUsageSetting.setupMixed`; rotation is added to the existing `/Rotate`.
  - Form fields are renamed on collision.
  - Bookmarks are kept for single-source output and dropped (with a message) when merging.
- **Images:**
  - JPEG is embedded as-is when there's no EXIF rotation. PNG is embedded lossless.
  - HEIC is decoded with `ImageDecoder` and re-encoded as JPEG at quality 90. HEIC needs Android 9+; on Android 8.x, show a clear message.
  - EXIF orientation is always applied. Images are downsampled to at most 300 DPI at the chosen page size.
  - Page size: Fit to image / A4 / Letter, plus a margin toggle.
- **Organize screen:**
  - A thumbnail grid with a badge showing each page's source file.
  - Drag to reorder (`sh.calvin.reorderable`, Apache 2.0).
  - Long-press to multi-select, then Rotate / Delete / Duplicate / Extract.
  - Undo from a snackbar plus a full undo stack.
  - Add files (PDFs and images, several at once). Tap a page to see it large.
- **Split sheet:**
  - Modes: Extract selected / By ranges (`1-3, 5, 8-`, shown as chips) / Every N pages / One file per page.
  - A live summary of the files it will create. Output goes to a folder picked with `OpenDocumentTree`.
- **Entry points:**
  - Home.
  - Viewer ⋮ → Organize pages / Split. If there are unsaved edits, show "Save edits first".
  - `ACTION_SEND` / `SEND_MULTIPLE` and "Open with" for images.

## Phase 4: Compress (removed 2026-11-18)
Built, then removed after real-world testing. See the decision above.

## Phase 5: Polish and validation
- Test with a 500-page document, image-heavy files and a 20-photo merge under a memory cap.
- Accessibility: page-tile descriptions, plus Move left / right as an alternative to dragging.
- Check outputs with `qpdf --check` and Chrome's PDF viewer.
- Update the README and technical plan.

**Order:** 1 → 2 → 3 → 4 → 5.

---

## Implementation notes (decisions made while building)

| Area | Decision | Why |
|---|---|---|
| Output pipeline | Instead of one `PdfJob` class, each engine writes to a temp file, then reopens and checks it (`PdfLoading.validate` in tools, `PdfEditWriter.validate` in editing). The UI shares `WorkingPanel` (progress + cancel) and `OutputPanel` (Save / Share / Open). | Same guarantees with less coupling between the editor and the tools. |
| Module layout | New `:core:tools` (merge/organize/split/photos) depends on `:core:editing`. | It reuses the PDFBox setup and single-thread dispatcher. Keeps `:app` thin. |
| Whiteout glyph rule | A glyph is removed when its centre is under the box **or** at least 15% of it is (`RedactionRules`). The preview uses the same rule. | Catches characters the box clips, without eating the line above or below. The UI warns when removed characters stick out past the box. |
| Whiteout engine | `RedactionScanner` walks the page and every form XObject use. `Redactor` rewrites only the streams it must, deepest first. Forms are copied on write, then content is clipped out inside each box (one even-odd clip per box). | Shared letterheads on other pages are untouched. Overlapping boxes are handled correctly. |
| Whiteout safety net | After redaction the page is re-scanned in memory. Any glyph still matching the rule fails the save. | It's better to refuse than to leak. |
| Undecodable images | JBIG2 / JPEG 2000 / stencil masks / images over 36 MP under a whiteout → that page is rendered to an image (200 dpi) after saving, and the user is told which pages. | PDFium can draw them even when PDFBox can't decode them. |
| Whiteout colour | Sampled from a fixed-DPI render of the *original* page ring, re-sampled after every move or resize (250 ms delay). A manual choice is never overwritten. If confidence is low, white is used, with a one-time message and Pick/eyedropper. | Follows the requested algorithm. Results are independent of zoom. |
| Stamps | ✓ and ✗ are vector ink (like signatures), so they scale crisply. Date is added text. Initials are saved separately from signatures. Each pick places **one** stamp. | Testing showed that an armed tool which stays active drops surprise stamps on the next tap. |
| Field snapping | Tapping inside a form box with Add text, Date or a stamp aligns and sizes the result to the box. Dropping text into a box snaps it. | Replaces interactive form filling. |
| Flatten | Form appearances are refreshed, then the AcroForm is flattened. Values whose widgets still have no appearance are drawn by hand in Noto Sans. Other annotations are drawn from their appearance (generated if missing). Links are kept. Annotations that can't be drawn are kept and reported. | Nothing disappears silently. |
| Save order | Replacements → flatten → whiteout (+verify) → fills, text, stamps, signatures. | Replacement glyph IDs refer to untouched content. Whiteout also catches flattened field values. Text written on top of a whiteout survives. |
| Merge | Pages are shallow-copied, with inherited attributes made explicit. Links are remapped to the new copies or dropped. Form fields are pruned to the widgets kept, and name clashes are renamed. Bookmarks are kept only for single-PDF output. | Nothing from a page that was left out is reachable in the output (checked by a test). |
| Photos | JPEG embedded byte-for-byte when upright and ≤ 300 dpi. Otherwise decoded with subsampling and EXIF applied, then JPEG q90 (or lossless for PNG). Page size defaults to Letter in US/CA/MX/PH, A4 elsewhere. | Quality where possible, bounded memory always. |
| Hand-off between screens | Picked or shared files are passed to Organize by an in-memory id. | Avoids long URI lists in routes. If the process dies, the screen shows its empty state with "Add files". |
| Drag-and-drop | `sh.calvin.reorderable` 2.5.1 (Apache 2.0). Accessibility actions "Move earlier/later", "Rotate right", "Delete page" and "Preview" are on every tile. | Reliable grid drag without custom gesture code. Screen-reader users don't need to drag. |

## Known limitations (v1)

- Password-protected PDFs still can't be opened or merged. The messages say so. (A Phase 3 item in the technical plan.)
- A drawn shape that only *partly* lies under a whiteout is clipped away, but its geometry stays in the file. It contains no extractable text.
- Saving can make a file larger. pdfbox-android (2.0.x) can't write compressed object streams, so files from modern tools can grow 2–3× (IRS Publication 17: 3.0 MB → about 9.6 MB). An incremental save would avoid this, but it can't be used after whiteout or text edits, because the removed content would stay in the file.
- Merged files drop tagged-PDF structure (`/StructParents`) and the source's named destinations.
