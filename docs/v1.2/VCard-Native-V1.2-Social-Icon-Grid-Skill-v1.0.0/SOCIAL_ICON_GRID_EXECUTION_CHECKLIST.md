# Social Icon Grid — Execution Checklist

## Preparation only (current authorization)
- [x] Owner approved design: Classic unchanged; six styles use grid; 2-line labels; max 4 columns; existing Other; live preview; documented reviewed brand icons.
- [x] Read-only Codex investigation received.
- [x] Skill documentation packaged; **no Android source edits authorized**.
- [ ] Owner reviews and approves this skill for implementation.
- [ ] Icon provenance and platform usage rights reviewed with owner; outstanding conflicts resolved.

## Implementation phase (requires separate explicit approval)
- [ ] Read protection skill and live approved V1.2 docs.
- [ ] Reinspect actual source and confirm investigation is still valid.
- [ ] Identify exact file list and create external timestamped SHA-256-verified backups.
- [ ] Record and approve brand icon source/usage matrix.
- [ ] Add offline bundled approved icon assets and mapping.
- [ ] Implement shared responsive grid only in six non-Classic styles.
- [ ] Verify Classic remains unchanged and Minimal selection rules intact.
- [ ] Add focused JVM and, where practical, instrumented tests.
- [ ] Run 3 Gradle verification commands and capture exact results.
- [ ] Confirm protected-file hashes, no signing/Manifest/Gradle changes.
- [ ] Provide full Codex report and stop for owner emulator review.

## Owner acceptance (later)
- [ ] Android Studio Sync, emulator install, app launch.
- [ ] Six styles + Classic regression; preview parity.
- [ ] Labels, brand icons, accessibility, responsive/font scale.
- [ ] URLs/order/duplicates and related-feature regression.
- [ ] Owner explicitly accepts milestone.

## Stop immediately if
Icon usage permission uncertain; code drift changes the plan; any protected file/schema/Classic modification required; a new dependency is needed; a test failure requires unrelated changes.
