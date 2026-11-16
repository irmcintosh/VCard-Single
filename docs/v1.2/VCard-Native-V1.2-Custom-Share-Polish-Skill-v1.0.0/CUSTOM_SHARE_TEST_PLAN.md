# Custom Share Polish — Test and Acceptance Plan

## JVM tests

- For all 16 optional-field subsets, preview rows/values/order match existing export rows exactly, excluding mandatory Full Name from optional count; ensure no unselected field leaks.
- Phone formatting parity: UK with international preference, UK national, US, and unparseable legacy number. Compare actual exported row values, not a second formatter.
- Invalid profile, no optional fields selected, missing/blank values: safe warning/empty state, no fabricated rows, no crash.
- Selection count and disabled availability reflect saved profile; Full Name always included; saved choice filtering unchanged.
- Preview computation has no write side effects; toggle/Continue/Cancel/Done do not persist choices.
- Preserve existing confirmed-choice behavior and asymmetric save-failure handling for QR versus Sharesheet.
- Regression: QuickShareSelectionTest, QuickShareContactExportTest, QuickShareQrGeneratorTest, QuickShareSelectionStoreTest, QrImageExportContractTest, SocialIconGridTest and relevant navigation tests.

## Android instrumentation

Where existing androidTest harness permits, extend `QuickShareViewModelTest` for preview changes on toggle/Select All/Clear All and no persistence before confirmation. Compile instrumentation tests; only claim execution if actually run.

## Emulator acceptance (owner-assisted, later)

1. Select screen shows Full Name always included, four optional rows including disabled missing values, correct selection count.
2. Review rows exactly match exported vCard and scanned QR for formatted phone and export ordering.
3. Select All/Clear All and navigation update preview correctly; Cancel/Done do not persist new choices.
4. Share Contact and Generate QR work; reopen restores confirmed choices.
5. Check narrow screen and font scaling 1.3×/1.5×/2.0×; buttons usable, no overlap or truncation.
6. TalkBack reads label/value/checked/disabled and mandatory Full Name; light/dark contrast.
7. QR PNG Save/Share and existing card/widget flows remain unaffected.

## Required automated commands (Android module)

` .\verification\run-gradle.ps1 :app:testDebugUnitTest`
` .\verification\run-gradle.ps1 :app:assembleDebug`
` .\verification\run-gradle.ps1 :app:compileDebugAndroidTestKotlin`

Report PASS/FAIL/NOT RUN independently for Gradle, Android Studio Sync, emulator, and physical device.
