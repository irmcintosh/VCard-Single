# QR Export Test and Acceptance Plan

## JVM automated tests
1. PNG bytes: signature, IHDR dimensions/bit depth, chunks, CRCs, IDAT inflation, image decode using available JVM image reader; verify 1-bit black/white and exact module pixel map.
2. Quiet zone: four white modules on each side; no duplicated quiet zone; all pixels in border white.
3. ZXing round-trip: PNG decode matches **exact** current QR payload for primary contact (including Unicode and high-density QR), Custom Share field selections, Hosted Profile HTTPS URL. Include versions 1 and 40 or highest supported test fixture, and non-ASCII vCard text.
4. Naming: context prefix, timestamp, random/UUID uniqueness under rapid repeated saves, safe characters; no overwrites.
5. Temporary file restrictions: allowed PNG name and bounded size; old `.vcf` and public-key names/limits unchanged; stale PNG cleanup and existing cleanup regressions; canonical-path safety.
6. Sharing intent behavior where existing JVM test infrastructure supports it: `image/png`, URI, stream, ClipData, read grant, no write grant; preserve existing contact sharing tests.
7. Source/contract checks: three screens pass their **displayed** `ContactQr` to shared actions; no controls on inline Share tab; no re-encode; existing navigation unchanged.
8. Error/cancellation handling: no success feedback before publish, no false success on failed write, no partial entries left after best-effort cleanup.

## Android instrumented tests (compile is not execution)
- MediaStore save: image visible in `Pictures/VCard Native`, `IS_PENDING` cleared; two saves yield distinct items; forced write failure cleans entry.
- Sharesheet: receiving app can open PNG read-only content URI; MIME, `EXTRA_STREAM`, ClipData and temporary grants correct.
- Instrumented tests must not depend on owner's private gallery data; clean up test-owned items only.

## Manual emulator acceptance matrix
| Context | Save PNG | Share PNG | Payload parity | Existing action |
|---|---|---|---|---|
| Dedicated Primary Contact QR | Gallery entry | Android Sharesheet | Scans same contact vCard | Done, Preview/Send Contact preserved |
| Custom Share QR | Gallery entry | Android Sharesheet | Scans exactly selected fields | Back/selection unchanged |
| Hosted Profile QR | Gallery entry | Android Sharesheet | Scans exact saved HTTPS URL | Selectable URL and Done preserved |

For each: save twice (unique files), open saved PNG, verify black/white quiet zone and scan using independent decoder or second camera; share to a compatible app, verify file opens and reads; verify failure feedback. Check light/dark, font scale 1.5, TalkBack labels, narrow phone. If independent scanner unavailable, clearly mark manual scan NOT RUN; JVM ZXing parity is not a substitute for device check.

## Mandatory gates and reporting
- Read-only source/diff inspection: PASS/FAIL.
- Verified external backups and protected baseline: PASS/FAIL.
- `:app:testDebugUnitTest`: PASS/FAIL and actual test count.
- `:app:assembleDebug`: PASS/FAIL.
- `:app:compileDebugAndroidTestKotlin`: PASS/FAIL; **not** equivalent to executed tests.
- Android Studio Sync: PASS/FAIL/NOT RUN (owner).
- Emulator install, launch and three-context UX/scan: PASS/FAIL/NOT RUN (owner).
- Physical phone: **NOT RUN** during development; only after separately approved official release.

Stop on any regression, unknown build failure, QR mismatch, unapproved file requirement, or protected configuration change.
