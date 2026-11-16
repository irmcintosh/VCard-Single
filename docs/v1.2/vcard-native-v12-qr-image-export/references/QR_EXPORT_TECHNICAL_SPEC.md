# QR Export Technical Specification — V1.2

**Status:** Proposed implementation design based on 2026-11-04 read-only Codex investigation; owner approved Option A and dedicated-primary-screen placement. **No implementation authorized by this document alone.** Validate against current source before editing.

## Existing architecture (investigation-reported)
- `ContactQrEncoder.kt`: ZXing encoder (EC M) for contact export and hosted HTTPS URL.
- `ContactQr.kt`: `ContactQr(version, modules)`, `isBlack(x,y)`; current screen geometry has 4-module quiet zone.
- `ContactQrRenderer.kt`, `ContactQrPanel.kt`: current crisp on-screen drawing.
- Primary: `QrPresentationScreen` in `ShareScreen.kt`, uses `state.contactQr` created in `ProfileViewModel.refreshContact()`; inline Share tab QR **must not** gain buttons.
- Custom: `QuickShareQrScreen` in `QuickShareScreens.kt`, uses session's `QuickShareQrGenerator.generate(profile,selection)` output; selection changes regenerate QR.
- Hosted: `OnlineCardQrScreen` in `ShareScreen.kt`, uses screen's QR of validated `state.onlineCardUrl`; URL changes regenerate QR.
- `NativeContactSharing.kt`, `TemporaryContactFiles.kt`, `ContactFileProvider.kt`: existing content URI share path. FileProvider XML reportedly exposes `cache/contact-share/payload/` and `cache/public-key-share/payload/`. Verify.
- Reported minSdk 31, targetSdk 36, ZXing core 3.5.4; verify from current files. No new dependency expected.

## Shared PNG encoding
Proposed `domain/sharing/QrPng.kt`: `QrPng.encode(qr: ContactQr): ByteArray`, testable on JVM; 1-bit black/white PNG with correct PNG signature, IHDR, IDAT, IEND, CRC32 and zlib-compressed scanlines. Draw **same module matrix** with **four white modules on each side**, square integer pixels, no antialiasing. Suggested `modulePx = max(6, floor(1200 / (side + 8)))`; total side `(side+8)*modulePx`, approximately 1110–1189 px for QR versions 1–40. Verify module count, arithmetic bounds, PNG memory size, compression validity, and maximum QR. Do not double-add quiet zone if source `ContactQr` already includes one: inspect its representation and test exact output border. PNG generation must not alter encoded QR payload; round-trip decode exact bytes/content with ZXing.

Use one `QrImageContext` enum for PrimaryContact, CustomShare, HostedProfile; filenames e.g. `VCard-Native-Contact-QR-20261104-181839-123-a1b2c3.png`. Timestamp plus cryptographically adequate random/UUID suffix (not timestamp alone), sanitized filename, separate naming for contexts. A new MediaStore entry must be created for **each** save; do not overwrite even on name collision. Capture and report actual MediaStore `DISPLAY_NAME` where feasible.

## Save via MediaStore
Proposed `platform/sharing/QrImageGallery.kt`:
1. Insert into `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with `DISPLAY_NAME`, `MIME_TYPE=image/png`, `RELATIVE_PATH=Pictures/VCard Native`, `IS_PENDING=1` (supported on project's Android versions).
2. Open output stream, write complete bytes, close safely.
3. Update `IS_PENDING=0`; treat failure to publish as failure, not success.
4. On any exception/cancellation, best-effort delete the inserted URI, report failure accurately. Do not claim saved until published. No storage permission request.
5. Run I/O off main thread; prevent duplicate in-flight UI operations while retaining ability to save multiple distinct images sequentially.

## Share through existing scoped infrastructure — approved Option A
Extend `NativeContactSharing` with `shareQrImage(png)` reusing `shareBytes` or existing chooser path. Write PNG only under existing FileProvider-allowed `cache/contact-share/payload/<uuid>/...png`, via `TemporaryContactFiles`. Existing `.vcf` and public-key allowed filenames and size limits **must remain intact**. Add narrowly defined PNG filename/type and **separate bounded maximum PNG size** based on measured output, not unlimited bytes. Preserve UUID isolation and canonical-path checks. Add PNG to existing stale temporary-file cleanup; verify cleanup cannot remove unrelated files or prematurely delete files still needed by recipients.

Use `ACTION_SEND`, `type=image/png`, `EXTRA_STREAM=contentUri`, `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION` on both underlying send intent and chooser as existing path requires. No write grant, `file://` URI, global FileProvider paths, new XML path, or manifest changes. Verify MIME and receiving-app readability.

## UI integration
New `ui/screens/share/QrImageActions.kt`: shared Save Image and Share Image controls, min 48 dp touch target, disabled/working state, accessible announcement of success/failure, distinct messages for saving vs sharing. Render directly below QR panel in **dedicated** `QrPresentationScreen`, `QuickShareQrScreen` when QR exists, and `OnlineCardQrScreen` when QR exists. Use the **same QR instance passed to the panel**, not a fresh encoder call; ensure recomposition/async operation captures the intended QR at tap time. Hosted URL and Custom Share selection changes must not export stale QR unexpectedly. Keep Done, Back, Preview, Send Contact (.vcf), selectable Hosted URL, navigation, and inline Share tab unchanged.

Proposed interface `QrImageExport` injected from `MainActivity` through `ProfileApplication` in `ProfileScreens.kt`, following current sharing injection conventions; verify actual signatures and prefer smallest change. No ViewModel or schema change.

## Expected file inventory (subject to live inspection)
**New:** `domain/sharing/QrPng.kt`, `platform/sharing/QrImageGallery.kt`, `ui/screens/share/QrImageActions.kt`, `QrPngTest.kt`, `QrImageGalleryTest.kt`.

**Modified:** `NativeContactSharing.kt`, `TemporaryContactFiles.kt`, `ShareScreen.kt`, `QuickShareScreens.kt`, `ProfileScreens.kt`, `MainActivity.kt`, `strings.xml`, `TemporaryContactFilesTest.kt`, `NativeContactSharingTest.kt`.

**Do not touch:** encoders/QR model/renderers, FileProvider XML, manifest, build system, `ProfileViewModel`, schema 5, card renderers, Appearance Editor, widget, version, signing. If an unlisted file or protected change becomes necessary, STOP and seek authorization before expansion.
