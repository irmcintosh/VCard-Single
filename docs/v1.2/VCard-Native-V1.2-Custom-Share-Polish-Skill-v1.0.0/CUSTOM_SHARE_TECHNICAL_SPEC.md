# Technical Specification — Approved Custom Share Polish

## Source baseline from read-only Codex investigation

- `QuickShareSelection.kt`: four optional keys, value filtering, selectedValues, canContinue.
- `QuickShareContactExport.kt`: reduced profile → `ContactExport.from`.
- `ContactExport.kt`: exported row order FN, N, ORG, TITLE, TEL, EMAIL.
- `PhoneNumbers.kt`: exported phone formatting.
- `QuickShareScreens.kt`: Select, Review, QR, field rows.
- `ProfileViewModel.kt`: Quick Share session, field toggles, Continue/Back/Cancel, QR/Share paths.
- `SharedPreferencesQuickShareSelectionStore.kt`: saved confirmed field keys.
- `strings.xml`: user-facing strings.

These paths and exact call signatures must be confirmed in the user's live workspace; do not invent APIs.

## Required behavior

1. **Export-matched Review:** Render mandatory Full Name and selected optional fields using the **actual exported contact rows**, with the export's order (Organization before Professional Title) and phone formatting. Use a small pure preview projection over `QuickShareContactExport.from(profile, selection).rows`, not a new formatter or a change to export bytes. Map only the four permitted optional fields. Carefully handle FN/N and duplicate/empty rows without exposing unselected fields.
2. **Refresh on selection change:** Compute preview once per relevant session change, not per Compose recomposition. A narrowly scoped `ProfileViewModel.kt` session property/update is approved. Keep existing raw `selectedValues` intact for callers and tests; no persistence of preview data. Ensure Select All/Clear All/toggle and profile snapshot changes are handled consistently.
3. **Select UI:** Full Name explicitly labelled **Always included**, non-toggleable; show all four optional fields in the established order, with missing/blank values visibly **disabled** and labelled **Not in saved profile**. Unavailable fields cannot be selected. Preserve existing saved-choice filtering, eligibility, and Continue rules. Add `N of 4 optional fields selected` feedback. Do not add a full preview to Select.
4. **Review UI:** Use the export projection only; preserve existing Done, Share Contact, Generate QR actions and navigation. For invalid/unexportable saved data, show an early, clear warning without fabrication or altering the export error path; don't claim sharing succeeded. Ensure no selected but absent values leak.
5. **Accessibility/layout:** Make entire selectable row a single understandable toggle with label/value/checked or disabled state; avoid competing text-selection semantics inside clickable rows. Make Full Name's mandatory status accessible. Maintain ≥48 dp touch targets and responsive actions at narrow widths and large font scales; support light/dark themes, long text, and TalkBack.
6. **Strings:** Add localized-resource strings for new labels/status/error messages in `strings.xml`; do not hardcode English UI text in Kotlin.

## Strict nonchanges

Do not change `QuickShareContactExport.kt`, `ContactExport.kt`, `PhoneNumbers.kt`, `QuickShareQrGenerator.kt`, selection-store interface/implementation or key format, QR image export, Sharesheet handoff, confirmed-selection write semantics, profile schema, codecs, or navigation behavior. In particular, QR selection-write failure currently allows showing QR with an error; Share Contact write failure blocks Sharesheet. **Preserve this asymmetry.** Selections persist only at the existing Generate QR / Share Contact confirmation points, not on toggle/Continue/Cancel/Done.

## Source-scan test traps

`QrImageExportContractTest` scans `QuickShareScreens.kt` between `fun QuickShareQrScreen(` and `private fun QuickShareFieldOption(`; preserve function names/order. `SocialIconGridTest` forbids SocialIconGrid references in QuickShareScreens. `QuickShareViewModelTest` checks raw selectedValues: add a separate preview property, don't repurpose it.

## Stop conditions

If existing `ContactExport.rows` does not reliably distinguish the selected optional fields, if error handling cannot be made safe without changing payloads, or if source differs materially from investigation, stop and seek owner decision. Do not silently expand scope.
