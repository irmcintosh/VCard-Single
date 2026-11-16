# Social Icon Grid — Verification Plan v1.1.0

## JVM and source tests
- Assert mapping for all ten supported platform IDs and Other to the **specific custom symbols** in `APPROVED_ICON_DESIGN.md`, including LinkedIn `in` and Facebook `f`; aliases, case variants, `twitter` → X, `otherProfessional`, blanks and unknown values.
- Verify no imported official-logo resources or staged SVGs, no new network image loading or dependencies. Check original resource provenance record.
- Preserve list order, duplicates, custom labels, hidden/invalid filtering and original URLs.
- Responsive columns 1–4 for widths and font scales; no zero columns for nonempty data, no overflow; two-line visible labels and full accessibility text; minimum 48 dp touch targets.
- Preserve Social action URL path, HTTPS validation and failure feedback.
- All six non-Classic styles use shared grid; Classic source and resources unchanged; Minimal optional slot unchanged; Appearance Editor live preview uses shared renderer.
- Run existing persistence, backup, contact export, Custom Share, Hosted Profile, widget, QR and appearance tests.

## Automated commands (from Android module)
` .\verification\run-gradle.ps1 :app:testDebugUnitTest `
` .\verification\run-gradle.ps1 :app:assembleDebug `
` .\verification\run-gradle.ps1 :app:compileDebugAndroidTestKotlin `

Do not run `:app` alone. Record actual test counts and distinguish instrumented-test compilation from execution.

## Emulator owner acceptance
- Six styles show correct 11-symbol mapping; Classic remains visually identical.
- LinkedIn `in` and Facebook `f` match approved reference; nine other icons unchanged from reference.
- Light/dark and all supported accent colors; 1, 2, 5, 11+ links, duplicates, long custom labels, Other.
- Narrow screens and 1.0x/1.5x/2.0x fonts, no clipping/overflow, TalkBack full descriptions, keyboard focus and 48 dp targets.
- Taps open correct HTTPS URLs; Minimal slot selected/unselected; live preview parity and Save/Cancel.
- QR PNG, Custom Share, Hosted Profile and widget regressions unaffected.
- Explicit owner emulator acceptance before closing milestone. No physical phone installation or signed release.
