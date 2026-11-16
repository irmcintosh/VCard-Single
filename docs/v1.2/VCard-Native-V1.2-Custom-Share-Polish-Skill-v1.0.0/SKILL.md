# Custom Share Polish — Codex Implementation Skill

## Authority and sequence

The owner approved the proposed Custom Share design and preparation of this skill **only**. Do not edit Android application code until separately authorized. Read `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` first; read `docs/v1.2/V1.2_REQUIREMENTS_APPROVED.md`, `V1.2_DECISION_LOG.md`, `V1.2_ROADMAP_AND_PROGRESS.md`, and `V1.2_TECHNICAL_CONSTRAINTS.md`. Inspect current source and reconcile against the read-only investigation report before editing.

## Implementation boundaries after separate authorization

Preserve automatic Full Name and only Professional Title, Organization, Phone, Email as optional fields. Keep existing Select → Review → Share Contact / Generate QR workflow and navigation. Preserve saved selection keys and confirmation timing, QR/vCard payload generation, Android Sharesheet, error semantics, and existing backup compatibility. Never change export formatting merely to match the preview: the preview must match export.

Allow only minimal Custom Share changes. `ProfileViewModel.kt` is an explicitly approved *narrow exception* for session preview state and updates; do not alter unrelated methods. No Gradle, AndroidManifest, signing, app version, package, SDK, dependencies, schema, codecs, widget, card styles, social icons, QR PNG export, or Hosted Profile changes.

## Safety gates

1. Read all references and current source; verify skill checksums.
2. Inspect git/worktree state and list exact intended edits; stop on material differences from investigation.
3. Before editing, make timestamped SHA-256-verified recoverable backups of **every existing file to be modified**, under `C:\Users\Ian12724\Downloads\PixelApp\VCard_Backups\` (outside project); baseline/hash protected references.
4. Implement minimal change and tests; preserve function names and test source-scanning boundaries.
5. Run ` .\verification\run-gradle.ps1 :app:testDebugUnitTest`, `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin` from `android` (three separate invocations; never `:app` alone).
6. Report precise changed files, backup hashes, test counts, build results, and unverified emulator/IDE gates. Stop; do not start final regression or release.

Stop and ask approval if export/selection storage APIs or unrelated protected behavior must change.
