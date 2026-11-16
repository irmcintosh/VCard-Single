# Test and emulator acceptance plan

## JVM/regression tests
- Four logical size classes map to the same existing three RemoteViews XML layouts; size thresholds and resize behavior unchanged.
- Accent projection uses committed saved profile, not editor draft; Save refreshes, Cancel and draft Restore Default do not update widget.
- All three current accents map to same source-defined light/dark values as application theme.
- Light/dark color-pair calls are supported; name/title colors avoid stale single-mode resolved values.
- Adaptive line limits at 1.0, 1.3, 1.5, 2.0; no zero-line or blank-state regressions.
- VeryNarrow does not duplicate initials; photo, initials, generic person fallback preserved.
- XML padding, Standard top-right menu, 48dp menu target, unchanged view IDs and click identities.
- Compact title >=12sp. Update prior `QuickCardWidgetMenuTest` expectations transparently; never weaken/remove tests.
- Existing body tap to Share; overflow My Card, Share, Profile; multiple widget IDs and update ordering.
- No QR, card-style, Social Icon Grid, Hosted Profile, Custom Share, schema or signing regressions.

## CLI verification (only after separately approved implementation)
From project root use exactly:
` .\verification\run-gradle.ps1 :app:testDebugUnitTest `
` .\verification\run-gradle.ps1 :app:assembleDebug `
` .\verification\run-gradle.ps1 :app:compileDebugAndroidTestKotlin `
Never use `:app` alone. Report actual counts and PASS/FAIL/NOT RUN; Android-test compilation is NOT execution.

## Owner emulator acceptance
- Place and resize widgets through VeryNarrow, Compact, Standard, Expanded; verify padding, centering, menu position, truncation.
- Test long names, long titles, empty data, emoji, non-Latin text, photos, initials and person fallback.
- Save Sapphire/Jade/Amethyst and confirm widget accents update; Cancel and unsaved Restore Default do not.
- Toggle Android light/dark while widget is on launcher; check contrast without waiting for refresh.
- Font scale 1.3 and 2.0; confirm no clipping. Two widgets of different sizes update independently.
- Body tap -> Share; menu -> My Card/Share/Profile, including unsaved-edit confirmation.
- Process restart, reboot, widget resize, and in-place upgrade from signed V1.1 preserving widget instance: final release testing, not claimed by CLI.
- TalkBack content and menu label; check against expected existing behavior.

## Evidence gate
Distinguish JVM test success, debug build, androidTest compilation, actual androidTest execution, IDE Sync, emulator observation, and physical-device upgrade. Never infer one from another.
