# VCard Native V1.2 — Social Icon Grid Implementation Skill

**Version:** 1.1.0 | **Status:** VISUAL DESIGN APPROVED; APPLICATION IMPLEMENTATION NOT YET AUTHORIZED

## Activation
Use only after explicit owner approval to implement. First read `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md`, current V1.2 approved requirements, decision log, technical constraints, card-style shared rules, and all files in this skill. Verify `SHA256SUMS.txt`. The owner's later design approval supersedes v1.0.0's plan to obtain official brand-logo assets: create custom, generic symbols as defined in `APPROVED_ICON_DESIGN.md`.

## Scope
Six non-Classic card styles: Minimal, Professional, Modern, Executive, Editorial, Horizon, plus shared Appearance Editor live preview. Classic is frozen byte-for-byte. Preserve Minimal's Professional Links slot. Preserve existing profile models, ordering, duplicates, visibility, validation, and HTTPS click path. No schema, navigation, Custom Share, Hosted Profile, widget, QR, Gradle, Manifest, signing, package/version changes or network icon loading.

## Implementation workflow (after separate approval)
1. Inspect live code and confirm the architecture described in the included investigation; stop if material drift.
2. Before edits, create external timestamped SHA-256-verified backups of every existing file to change; never touch the signing `.p12`.
3. Implement original symbol resources or Compose drawings from `APPROVED_ICON_DESIGN.md`, not copied brand logos. Maintain a provenance and visual-similarity record; no downloading required.
4. Integrate responsive grid through existing `ProfessionalLinkList` interface if feasible; preserve all six style wrappers and Classic unchanged.
5. Add focused tests; run the three project verification commands from the test plan.
6. Report changed files, backup/hash results, tests, remaining emulator checks; stop for owner acceptance.

## Stop conditions
Any need to modify Classic or protected files; icon design materially differing from approved mockup; unapproved dependency; new schema; unapproved project documentation edit; uncertainty about copied brand imagery; test failures outside approved scope. Ask the owner before proceeding.
