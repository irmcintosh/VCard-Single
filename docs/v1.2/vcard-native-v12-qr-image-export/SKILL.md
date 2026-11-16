---
name: vcard-native-v12-qr-image-export
version: 1.0.0
purpose: Implement approved V1.2 QR PNG save and share across three existing QR presentations, without changing encoded data or protected Android configuration.
---

# VCard Native V1.2 — QR Image Export Implementation Skill

## Activation and authority
Use for the **separately authorized** QR PNG Save Image / Share Image implementation only. **This package is a reviewed plan, NOT authorization to edit code.** Obtain the owner's explicit implementation approval before Stage B. Owner decisions for this package: **Option A**, reuse existing FileProvider-allowed `cache/contact-share/payload/` folder; **Primary Contact QR actions on dedicated QR screen only**, not inline Share-tab QR. The approved V1.2 requirements and actual current source outrank this package when they conflict; stop and ask before changing approved scope.

## Mandatory first actions
1. Read the original project-root `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` in full, plus `docs/v1.2/V1.2_REQUIREMENTS_APPROVED.md`, `V1.2_DECISION_LOG.md`, `V1.2_ROADMAP_AND_PROGRESS.md`, `V1.2_TECHNICAL_CONSTRAINTS.md`, and relevant architecture references.
2. Read this skill's `references/QR_EXPORT_TECHNICAL_SPEC.md`, `references/QR_EXPORT_TEST_PLAN.md`, `references/QR_EXPORT_EXECUTION_CHECKLIST.md`, and `references/INVESTIGATION_REPORT.md`.
3. Reinspect **live** files under `C:\Users\Ian12724\Downloads\PixelApp\VCard\android`. The investigation is a snapshot, not proof of current code. Confirm existing QR, sharing, FileProvider paths and call signatures. If materially different, STOP for a revised plan.
4. Confirm explicit owner approval of implementation. If not granted, stop after read-only review.
5. Before changes, inventory exact files and make timestamped external backups outside the project (`C:\Users\Ian12724\Downloads\PixelApp\VCard_Backups\...`), recording and verifying SHA-256 hashes for all existing files to modify; establish protected-file baseline hashes and rollback procedure. Never move/modify `docs\signing\vcard-native-release.p12`.

## Non-negotiable invariants
- Export **the exact `ContactQr` object currently shown** in each screen; never re-encode payloads or alter `ContactQrEncoder`, `ContactQr`, `ContactQrRenderer`, `ContactQrPanel`, `QuickShareQrGenerator` or existing contact/vCard export behavior.
- Three contexts: dedicated Primary Contact `QrPresentationScreen`, Custom Share `QuickShareQrScreen`, Hosted Profile `OnlineCardQrScreen`. **No export controls on inline Share tab**.
- Save PNG to `Pictures/VCard Native` through MediaStore, no broad storage permissions, no overwrite, truthful result, cleanup partial writes.
- Share `image/png` via existing FileProvider and Android Sharesheet with temporary **read-only** URI grant, Option A folder only. Preserve `.vcf` and public-key sharing and their existing restrictions.
- No manifest, `contact_file_paths.xml`, Gradle, dependencies, SDK/JDK, schema, app ID/version, signing, widget, card styles, Appearance Editor, or profile-data changes.
- Do not install on physical phone, sign release, or advance milestones.

## Workflow
Follow `references/QR_EXPORT_EXECUTION_CHECKLIST.md` in order. Implement one shared PNG encoder, one MediaStore save path, one narrowly extended existing share path, one shared Compose action component. Use coroutines/background dispatch for encoding/I/O, cancellation-safe UI state and accessibility feedback. Preserve existing navigation and actions.

## Verification and reporting
Run the project's existing verified commands from the correct working directory:
- `.\verification\run-gradle.ps1 :app:testDebugUnitTest`
- `.\verification\run-gradle.ps1 :app:assembleDebug`
- `.\verification\run-gradle.ps1 :app:compileDebugAndroidTestKotlin`
Never run `:app` alone. Report test count and pass/fail separately; Android-test **compile** is not execution. Stop for owner's Android Studio Sync, emulator installation, QR scan and visual review. Report exact diffs, hashes, tests, risks, and NOT RUN gates. Do not claim completion without emulator acceptance.
