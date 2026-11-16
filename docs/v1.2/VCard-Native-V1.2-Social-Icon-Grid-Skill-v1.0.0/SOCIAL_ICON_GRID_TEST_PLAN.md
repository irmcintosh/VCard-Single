# Social Icon Grid — Verification Plan

## JVM / source-level checks
- Map all ten supported IDs and Other; test case-insensitive saved values, display names, `twitter`→X, `otherProfessional`, blanks, unknown names.
- Assert every mapped resource exists and provenance review is complete.
- Verify order and duplicates survive visible-link filtering, including identical labels/URLs and hidden/invalid entries.
- Verify responsive columns: no zero columns for nonempty links, never above four or visible item count, no width overflow; exercise multiple widths/font scales.
- Verify click uses the existing Social action and original URL, existing HTTPS validation and no-handler feedback.
- Verify accessible full label/URL description, 48 dp targets, visible max-two-line labels, no network icon fetching.
- Verify all six non-Classic styles use shared grid through existing renderer; Classic source hash remains unchanged; Minimal slot selection unchanged.
- Run existing persistence, backup, contact export, Custom Share, Hosted Profile, widget, QR, and appearance tests unchanged.

## Automated commands (from Android module)
` .\verification\run-gradle.ps1 :app:testDebugUnitTest `
` .\verification\run-gradle.ps1 :app:assembleDebug `
` .\verification\run-gradle.ps1 :app:compileDebugAndroidTestKotlin `

Do not run `:app` alone. Record exact executed test count, failures, and whether instrumented tests were merely compiled. Do not infer emulator success from Gradle.

## Emulator owner acceptance
- All seven styles: Classic visually identical to accepted V1.1 baseline; six new styles show grid.
- Light/dark and each currently implemented accent; contrast and brand-color compliance.
- 1, 2, 5, 11+ links, duplicates, very long labels, custom Other; correct order and click destination.
- Narrow screen and 1.0x, 1.5x, 2.0x fonts; no clipped labels or horizontal overflow.
- TalkBack announces each full link and actionable purpose; touch targets accessible.
- Minimal Professional Links slot selected/unselected; count still one.
- Appearance Editor preview matches My Card for same saved/draft choices; Cancel/Save remain correct.
- Existing My Card navigation, QR PNG actions, Custom Share, Hosted Profile, and widget unaffected.
- Android Studio Sync, install, and app launch are separate verification gates.

## Reporting
Use PASS / FAIL / NOT RUN for each gate; include screenshots only if actually captured. Owner confirms emulator acceptance before milestone is marked complete.
