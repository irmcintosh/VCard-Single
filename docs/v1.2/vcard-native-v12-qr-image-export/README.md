# VCard Native V1.2 — QR Image Export Skill Package

**Version:** 1.0.0  
**Prepared:** 2026-11-04  
**Status:** Planning artifact; Android implementation NOT started or authorized by this ZIP.

## Owner decisions incorporated
- Temporary sharing: **Option A**, existing secure `cache/contact-share/payload/` folder, no FileProvider XML change.
- Primary Contact QR export controls: **dedicated Contact QR screen only**, not inline Share tab.
- All three dedicated QR contexts: Save Image + Share Image.

## Files
- `SKILL.md` — agent instructions and guardrails.
- `references/QR_EXPORT_TECHNICAL_SPEC.md` — implementation architecture and proposed file inventory.
- `references/QR_EXPORT_TEST_PLAN.md` — automated, instrumented and emulator acceptance criteria.
- `references/QR_EXPORT_EXECUTION_CHECKLIST.md` — stage-by-stage controlled process.
- `references/INVESTIGATION_REPORT.md` — source investigation supplied by owner, preserved verbatim.

## Installation in VS Code workspace
1. Extract ZIP to `C:\Users\Ian12724\Downloads\PixelApp\VCard\.agents\skills\vcard-native-v12-qr-image-export\` so `SKILL.md` is directly inside that directory. If your Codex environment uses a different skill discovery location, verify its documented configuration rather than guessing.
2. Do not replace the existing `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md`; this package supplements it.
3. Review package, then separately authorize Codex implementation; instruct it to read the skill and source references first.
4. Do not edit Android source during installation of this documentation package.

## Source precedence
Approved requirements and current live Android source take priority. Some earlier V1.2 planning documents predate the already-approved schema 5 and card-style work; do not revert completed features or treat old planning status labels as current implementation status.
