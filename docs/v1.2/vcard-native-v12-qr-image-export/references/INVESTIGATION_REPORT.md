I can build QR image export without changing the manifest, the file-sharing configuration, Gradle or any dependency, and without touching the existing QR encoders. All three QR contexts already use the same QR data object, so each PNG can be drawn from exactly the QR shown on screen. I need two decisions from you before I start (end of the report). Nothing was changed during this investigation.

## 1. Current QR architecture
All three contexts share one pipeline: the payload is encoded into a `ContactQr` object, and `ContactQrPanel` draws that object on screen.

| Layer | File | Notes |
|---|---|---|
| Encoder | `ContactQrEncoder.kt` | ZXing `Encoder`, error correction level M, no fallbacks. `encode(export)` for contacts, `encodeHostedProfileUrl(url)` for the Hosted Profile. |
| QR grid and sizing | `ContactQr.kt` | `ContactQr(version, modules)` with `isBlack(x, y)`. `geometry()` sizes the QR for the screen and already includes a 4-module white border. |
| Renderer | `ContactQrRenderer.kt` | Draws sharp black and white squares on whole pixels, with no smoothing. |
| On-screen panel | `ContactQrPanel.kt` | Draws a bitmap sized to the screen. Every QR screen uses it. |
| Sharing | `NativeContactSharing.kt`, `TemporaryContactFiles.kt`, `ContactFileProvider.kt` | Opens the Android share sheet with temporary read access for the receiving app. Temporary files are cleaned up when the device has rebooted since they were written. |

Environment, as found in the current files:
- `minSdk` 31, `targetSdk` 36.
- ZXing core 3.5.4 is already a dependency.
- The manifest declares **no storage permissions**.
- The file provider (`ContactFileProvider`) is not exported and allows temporary access grants. Its paths file (`contact_file_paths.xml`) allows only `cache/contact-share/payload/` and `cache/public-key-share/payload/`.

## 2. The three QR contexts

| Context | Screen | Payload source | Current actions |
|---|---|---|---|
| **Primary Contact QR** | `QrPresentationScreen` in `ShareScreen.kt`. The same QR also appears inline on the Share tab (`HomeScreen`). | `refreshContact()` in `ProfileViewModel.kt` builds `ContactExport.from(profile)`, encodes it and stores the result in `state.contactQr`. Outdated results are thrown away using a request counter and the save token. | Done. The Share tab adds Preview contact and Send Contact (.vcf). |
| **Custom Share QR** | `QuickShareQrScreen` in `QuickShareScreens.kt` | `QuickShareQrGenerator.generate(profile, selection)` builds the export from the selected fields and encodes it. The result is kept for the Quick Share session and regenerated when the selection changes. | Done, Back |
| **Hosted Profile QR** | `OnlineCardQrScreen` in `ShareScreen.kt` | The screen itself encodes `state.onlineCardUrl` (the saved, validated HTTPS link) and re-encodes whenever the link changes. | Done. The link is shown as selectable text. |

**Same payload guarantee:** every screen already holds the exact `ContactQr` it shows. Building the PNG from that same object, without encoding again, means the image always carries the same content as the screen.

## 3. Proposed PNG generation
**New file `domain/sharing/QrPng.kt`:** plain Kotlin that can be tested on the computer without an emulator, and doesn't use Android's bitmap classes.
- `QrPng.encode(qr: ContactQr): ByteArray` writes a black-and-white PNG using 1 bit per pixel.
- It uses only Java's built-in compression and checksum classes (`java.util.zip`), so no new dependency is needed.
- The white border is 4 modules wide, the minimum the QR standard requires and the same as on screen.
- Each module is a whole number of pixels, so edges are never blurred: `modulePx = max(6, 1200 / (side + 8))`, where `side` is the module count per side (21 for version 1, 57 for version 10, 177 for version 40).
- The image is always about 1,100–1,200 px square:

  | QR version | Calculation | Image size |
  |---|---|---|
  | 1 | 29 × 41 | 1189 px |
  | 10 | 65 × 18 | 1170 px |
  | 40 (largest) | 185 × 6 | 1110 px |

- **Memory:** before compression the image is under about 180 KB (worst case version 1: 1189 rows of about 150 bytes each). The finished file should be a few KB to a few tens of KB; that's an estimate I'll measure in tests.
- **Repeatable output:** the same QR always produces the same bytes.

**Why not Android's built-in bitmap-to-PNG conversion:** this project can't run Android graphics code in its computer-based tests (no Robolectric), and the conversion produces large 32-bit files. The existing `ContactCompatibilityPackageTest` already reads PNGs back with ZXing in computer-based tests, so I'll follow that pattern.

**Also in `QrPng.kt`:**
- `enum QrImageContext { PrimaryContact, CustomShare, HostedProfile }`.
- `qrImageFileName(context, timestamp)`, which gives names like `VCard-Native-Contact-QR-20261104-181839-123.png`. The timestamp includes milliseconds and contains no characters that are invalid in file names.

## 4. Proposed saving and sharing
**Saving to Pictures/VCard Native** (new `platform/sharing/QrImageGallery.kt`):
- **Permissions:** with `minSdk` 31, the app can always save through Android's MediaStore into the `Pictures/VCard Native` folder. Saving its own images needs no permission on Android 10 and later, so there's no manifest change.
- **Hidden until complete:** the gallery entry is created as "pending" (`IS_PENDING = 1`), so other apps can't see it while it's being written.
  1. Create the entry as pending.
  2. Write the PNG into it.
  3. Clear the pending flag (`IS_PENDING = 0`).
- **No overwriting:** every save creates a new gallery entry; the app never reopens an existing one.
- **Cleanup on failure:** if any step fails, the app deletes the entry it created, so no partial image is left behind.
- **Unique names:** names are unique to the millisecond, and Android adds a suffix if two names still collide.
- **Accurate feedback:**
  - The success message appears only after the pending flag has been cleared, and shows the file name Android actually stored.
  - Anything else shows a specific failure message.

**Sharing** (an addition to `NativeContactSharing`): a new `shareQrImage(png)` reuses the existing sharing path (`shareBytes`).
- It sends one item (`ACTION_SEND`) with type `image/png`.
- It passes the image as a content link (in both `EXTRA_STREAM` and the intent's clip data).
- The receiving app gets temporary read-only access (`FLAG_GRANT_READ_URI_PERMISSION` on both the send and chooser intents, as today).
- It uses the existing temporary-file handling and the same cleanup after a reboot.

**Where the temporary share file lives.** Two options:
- **Option A (recommended): reuse the existing shared folder, with no configuration change.**
  - The PNG is written to the already-allowed path `cache/contact-share/payload/<uuid>/vcard-native-qr.png`.
  - The file provider already works out the type from the file extension, so it reports `image/png`, and it stays read-only.
  - Two source changes are needed (not configuration):
    - `TemporaryContactFiles` must accept the PNG file name with its own size limit. Today it accepts only the two existing file names, capped at 64 KB.
    - `NativeContactSharing.cleanup` must also clean up PNG files.
- **Option B (cleaner separation, needs your approval):** add one line to the protected `res/xml/contact_file_paths.xml`, `<cache-path name="qr-image" path="qr-image-share/payload/" />`, giving QR images their own folder. I don't recommend this unless you prefer separate folders.

## 5. Where the controls go
**One shared component, new file `ui/screens/share/QrImageActions.kt`:**
- **Save Image** and **Share Image** buttons, each at least 48 dp tall.
- A status line that TalkBack announces on success or failure.
- Both buttons are disabled while another export is running.
- The PNG is built in the background, and saving runs on a background thread, so the screen doesn't freeze.
- All three screens use this component, so the export logic isn't duplicated.

**Placement:** directly below the QR panel on each screen.
- **Primary Contact:** `QrPresentationScreen`.
- **Custom Share:** `QuickShareQrScreen`, only when the QR is available.
- **Hosted Profile:** `OnlineCardQrScreen`, only once its QR has been generated.

**Stays as it is:** the Done and Back buttons, Send Contact, Preview, the Hosted Profile link text, the Custom Share selection, navigation and every payload.

**Connecting it up:**
- A small `QrImageExport` interface with save and share functions is passed from `MainActivity` into `ProfileApplication` in `ProfileScreens.kt` and on to the three screens. This follows the existing `shareContact` pattern.
- No ViewModel or schema changes.

## 6. File-change inventory (proposed)
**New:**
- `domain/sharing/QrPng.kt`
- `platform/sharing/QrImageGallery.kt`
- `ui/screens/share/QrImageActions.kt`
- Computer-based test `QrPngTest.kt`
- Emulator test `QrImageGalleryTest.kt`

**Modified:**
- `NativeContactSharing.kt`
- `TemporaryContactFiles.kt`
- `ShareScreen.kt`
- `QuickShareScreens.kt`
- `ProfileScreens.kt`
- `MainActivity.kt`
- `strings.xml`
- `TemporaryContactFilesTest.kt` and `NativeContactSharingTest.kt` (extended)

**Not changed:** `ContactQrEncoder`, `ContactQr`, `ContactQrRenderer`, `ContactQrPanel`, `QuickShareQrGenerator`, `ContactFileProvider`, `ProfileViewModel`, schema 5, the card renderers, the Appearance Editor, and the version and package ID.

## 7. Protected changes and risks
**Protected or dependency changes:** none with Option A. Option B would change only `contact_file_paths.xml`, and only with your approval.

**Risks:**
- **Shared folder (Option A):** cleanup must handle both PNG and `.vcf` files, or old PNGs could build up. Tests will cover this.
- **Size limits:** the temporary-file size limit must be set separately for each file type.
- **Name collisions:** Android's handling varies between versions. Reporting the file name Android actually stored covers this.
- **Hosted Profile:** its QR is created inside the screen, so the buttons must use that exact QR object.
- **Share tab:** it only gets the buttons if you choose that in Decision 2.

## 8. Verification plan
**Computer-based (JVM) tests:**
- The PNG is valid: correct signature, header and checksums.
- The image is exactly `(side + 8) × modulePx` pixels square, matching the sizing table, and the 4-module border is all white.
- Every module's pixels match the QR data.
- ZXing reads each PNG back to **exactly the original payload** for:
  - primary contact exports, including non-English characters and the largest QR version;
  - Custom Share selections;
  - Hosted Profile links.
- File names are correctly formatted, unique, and free of invalid characters.
- Temporary files: the allowed names, the size limits, and cleanup of PNG and `.vcf` files together.
- Source checks that all three screens pass the QR they display to the shared buttons, and that the encoders are unchanged.
- All existing QR, Quick Share and sharing tests still pass.

**Emulator tests** (compiled from the command line, run on the emulator):
- Saving: the pending flag clears and the image appears in `Pictures/VCard Native`.
  - Two saves produce two separate files.
  - A simulated failure leaves no entry behind.
- Sharing: the share request has type `image/png`, a content link, read access and clip data. The receiving app can read the file but not change it.

**Manual emulator checks:**
- In each of the three contexts: Save, find the image in the Photos app, and scan it with another device or camera.
- In each context, Share to Messages or Gmail.
- Send Contact, Preview and the Hosted Profile link still work.
- Font scale 1.5, light and dark themes, and TalkBack announcements.

## 9. Confirmation
This investigation was read-only: no files were created, edited or deleted; no build, test or backup was run; and no configuration was touched.

## Decisions needed before implementation
1. **Temporary share files:** Option A (no configuration change, recommended) or Option B (one added line in the protected `contact_file_paths.xml`)?
2. **Primary Contact QR placement:** buttons only on the dedicated Contact QR screen (recommended), or also on the Share tab's inline QR?