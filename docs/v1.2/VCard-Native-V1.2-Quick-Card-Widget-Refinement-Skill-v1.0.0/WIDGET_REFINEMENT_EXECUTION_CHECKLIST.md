# Execution checklist

## Preparation — skill authoring only
- [x] Owner approved design decisions and skill preparation.
- [x] Investigation report reviewed.
- [ ] Owner separately authorizes app implementation.

## After separate implementation approval
- [ ] Read project protection skill and approved V1.2 documents.
- [ ] Verify skill SHA256SUMS.txt and inspect current source.
- [ ] Inventory exact file changes and check for unrelated worktree changes.
- [ ] Timestamped external recoverable backups; SHA-256 before editing.
- [ ] Implement only approved saved-accent, contrast, spacing, menu placement, duplicate-initials and adaptive-text changes.
- [ ] Preserve four logical sizes, three layouts, navigation and existing widget instances.
- [ ] No manifest, provider-info, Gradle, signing, schema, package or version edits.
- [ ] Add/adjust focused tests without weakening assertions.
- [ ] Run three approved Gradle tasks; record counts and warnings.
- [ ] Compare protected hashes; verify backups.
- [ ] Provide changed-file list, evidence, unresolved risks, emulator checklist; STOP.

## Owner acceptance later
- [ ] Emulator UI/resize/large fonts and light/dark tested.
- [ ] Saved accent and draft isolation tested.
- [ ] Widget actions tested.
- [ ] Multi-instance and upgrade checks planned for release.
