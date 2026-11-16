# Controlled Execution Checklist — QR Export

**No edits until owner separately authorizes implementation.**

- [ ] Read original protection skill and approved V1.2 docs; reconcile any outdated planning statements with current approved decisions.
- [ ] Reinspect actual live QR objects, three screen signatures, FileProvider path XML, `NativeContactSharing` and `TemporaryContactFiles`; identify precise insertion points.
- [ ] Confirm Option A and dedicated-primary-screen-only placement; no Share-tab inline controls.
- [ ] Inventory exact new/modified files and protect exclusions. If scope expands, STOP.
- [ ] Create timestamped external backup directory and SHA-256 manifest for all existing files to modify; verify copies and protected baseline; document rollback.
- [ ] Implement/test shared `QrPng` from displayed `ContactQr`, four-module quiet zone, integer pixel scaling, PNG integrity and ZXing parity.
- [ ] Implement/test unique collision-safe filenames and MediaStore save/publish/failure cleanup.
- [ ] Extend existing temporary share path narrowly for PNG; keep VCF/public-key behavior and size limits; test cleanup and grants.
- [ ] Add shared accessible `QrImageActions`, safe async state and accurate feedback.
- [ ] Wire dedicated Primary, Custom, Hosted QR screens to the exact displayed QR; preserve old controls, navigation and inline Share tab.
- [ ] Review diff: no manifest, FileProvider XML, Gradle, SDK/JDK, schema, encoders, card styles, widget, version or signing edits.
- [ ] Run unit tests, debug build, Android-test compile; record actual test counts and failures separately.
- [ ] Compare protected/reference hashes and verify no unexpected modifications.
- [ ] Deliver report with file list, exact changes, backup location/hash proof, verification table, residual risks, emulator steps.
- [ ] STOP for owner's Android Studio Sync and emulator visual/scan acceptance. No release signing or physical-device install.
