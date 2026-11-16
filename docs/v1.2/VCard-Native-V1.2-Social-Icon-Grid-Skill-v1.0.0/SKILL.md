# VCard Native V1.2 — Social Icon Grid Implementation Skill

**Version:** 1.0.0 | **Status:** PREPARATION APPROVED; APPLICATION IMPLEMENTATION NOT YET AUTHORIZED

## Purpose
Provide a tightly scoped, reproducible implementation guide for V1.2 Social Presentation. Read this skill alongside the actual current Windows project. This package is not evidence that code or artwork has been verified.

## Activation
Use only after the owner separately approves implementation and the icon asset gate in `ICON_ASSET_PROVENANCE_AND_APPROVAL.md` is satisfied. Read `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` first. Follow the project protection skill's investigation, backup, diff, and verification rules.

## Project paths
- Root: `C:\Users\Ian12724\Downloads\PixelApp\VCard`
- Android module: `C:\Users\Ian12724\Downloads\PixelApp\VCard\android`
- Preferred installation: `docs\v1.2\vcard-native-v12-social-icon-grid\` (do not overwrite an existing directory without owner approval).

## Authoritative requirements
Read `docs\v1.2\V1.2_REQUIREMENTS_APPROVED.md` §4, `V1.2_DECISION_LOG.md` V12-10, `V1.2_TECHNICAL_CONSTRAINTS.md` §§3,4,6, all card-style specifications and shared rules, and current source. The owner's 2026-11-04 follow-up specifically approves preserving Classic unchanged, applying the grid to six other styles, two-line visible labels, max four responsive columns, existing Other icon, shared live preview, and an icon provenance record. This decision should be appended to the live V1.2 decision log only in a separately approved documentation edit.

## Workflow
1. Read the technical spec, asset gate, test plan, checklist, and included investigation report.
2. Inspect the live source and verify each reported path/function; stop on material drift.
3. Prepare a source-and-license review of each platform icon. Do not use unknown-provenance assets. **Stop for owner approval if a source or trademark usage condition is unresolved.**
4. Before any authorized edit, create timestamped SHA-256-verified backups outside the project for every existing file to change. Never move/modify signing `.p12`.
5. Implement only the approved six-style presentation using existing models and URL launch behavior; do not change Classic.
6. Run focused tests and the project's three established Gradle verification commands; distinguish build/test compilation from actual emulator testing.
7. Report exact diffs, backup path, source/provenance, verification, remaining emulator checks. Stop for owner acceptance.

## Absolute exclusions
No schema/persistence changes, platform additions, QR/export, widget, Hosted Profile, Custom Share, My Card navigation changes, Manifest/Gradle/toolchain/signing changes, package/version changes, new runtime dependencies, or network image loading. No physical-phone installation or signed release.
