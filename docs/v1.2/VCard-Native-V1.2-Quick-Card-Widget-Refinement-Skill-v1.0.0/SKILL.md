# Widget refinement — Codex task skill

## Mandatory preflight
1. Read `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` completely.
2. Read `docs/v1.2/V1.2_REQUIREMENTS_APPROVED.md`, `V1.2_DECISION_LOG.md`, `V1.2_ROADMAP_AND_PROGRESS.md`, and `V1.2_TECHNICAL_CONSTRAINTS.md`.
3. Read this entire skill, validate SHA256SUMS.txt, inspect actual current widget source and tests; stop if different materially from investigation.
4. Do not treat installation of this skill as permission to implement. Obtain separate owner authorization first.
5. For authorized changes, inventory paths and make timestamped SHA-256-verified recoverable backups OUTSIDE the project before editing; preserve unrelated changes.

## Approved scope after separate authorization
Refine Quick Card Widget spacing, alignment, long-text behavior, system day/night color handling, and committed saved-accent synchronization for current Sapphire/Jade/Amethyst. Preserve all widget actions and instances, four logical size classes and three XML layouts. No new widget fields, actions, style mirroring or organization.

## Nonnegotiable protection
Do not modify AndroidManifest.xml, quick_card_widget_info.xml, Gradle, SDK/JDK, package ID, signing `.p12`, schema, repository codecs, profile navigation, QR, Social Icon Grid, Hosted Profile, Custom Share, or app version. Never copy or move the original signing key. Stop and seek approval for any necessary protected-file edit. Never claim IDE Sync, emulator or physical-device verification from a CLI build.

## Work stages
Inspect and plan -> external backups -> minimal implementation -> focused tests -> CLI verification -> report -> stop for owner emulator acceptance. No signed release, no physical phone.
