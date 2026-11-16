# VCard Native — Development Roadmap

**Rule:** Complete one milestone, verify it on device, and record results before advancing.

## Phase 1 — Development environment
- Install Android Studio and SDK.
- Create Compose starter project; resolve Gradle sync.
- Connect Pixel and run starter app.
**Exit:** User confirms a successful device launch.

## Phase 2 — App foundation
- Set up packages, MVVM, navigation, Material 3 theme, reusable components.
**Exit:** Three main destinations navigate correctly on Pixel.

## Phase 3 — Card interface
- Build card header, photo, bio, action buttons, optional secondary details.
**Exit:** Matches approved visual direction and handles missing data.

## Phase 4 — Profile editing and persistence
- Add form, validation, photo picker, local storage, immediate UI updates.
**Exit:** Data survives restart; image changes persist.

## Phase 5 — Sharing
- Generate offline QR, export `.vcf`, invoke Sharesheet, optionally share hosted link.
**Exit:** Second phone scans QR; contacts app imports `.vcf`; airplane-mode test passes.

## Phase 6 — Optional integrations
- Assess Google Wallet eligibility, issuer requirements, and security; implement only after approval.
**Exit:** Documented go/no-go decision; core app unaffected.

## Phase 7 — Quality and release
- Accessibility, edge cases, device testing, error handling, signing, APK generation.
**Exit:** Reproducible signed build and verified installation.

## Change control
New feature requests must be recorded in `07_DECISION_LOG.md`, reflected in the specification, and added to the roadmap only after approval.
