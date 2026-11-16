# Execution Checklist — For Future Separately Authorized Codex Run

- [ ] Confirm separate owner authorization for implementation (skill preparation alone is not authorization).
- [ ] Read protection skill and approved docs.
- [ ] Verify `SHA256SUMS.txt` and inspect every skill file.
- [ ] Confirm source baseline and protected working tree.
- [ ] Inventory exact planned edits (expected: QuickShareScreens.kt, ProfileViewModel.kt narrow exception, strings.xml, new pure preview helper and tests).
- [ ] Backup every pre-existing file to be edited outside project; verify SHA-256 and record rollback paths.
- [ ] Build export-based preview with no payload or storage changes.
- [ ] Add disabled missing rows, selection count, mandatory Full Name label, early invalid-data warning.
- [ ] Fix accessibility and responsive buttons without altering navigation.
- [ ] Preserve `QuickShareQrScreen` / `QuickShareFieldOption` source order and raw selectedValues.
- [ ] Run all three Gradle commands and report actual test count.
- [ ] Hash-check protected/reference files unchanged.
- [ ] Provide emulator acceptance checklist; don't claim unrun instrumentation/emulator tests passed.
- [ ] Stop after report; no final regression/release work.
