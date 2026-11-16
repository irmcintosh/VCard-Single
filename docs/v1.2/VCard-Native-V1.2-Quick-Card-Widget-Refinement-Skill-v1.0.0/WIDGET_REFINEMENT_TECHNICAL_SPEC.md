# Technical specification — owner-approved behavior

## Baseline (from read-only Codex investigation; verify against actual source)
- `QuickCardWidgetProvider.kt` creates RemoteViews and handles refresh/photo/colors.
- `QuickCardWidgetModel.kt` projects committed saved profile into widget state.
- `QuickCardWidgetPolicy.kt` chooses VeryNarrow (120×48 dp), Compact (180×48), Standard (120×160), Expanded (260×120); VeryNarrow and Compact share `quick_card_widget_compact.xml`.
- Standard uses vertical layout; Expanded uses horizontal layout. Existing `QuickCardWidgetMenu.kt` / `QuickCardWidgetMenuActivity.kt` control overflow. Body tap goes to Share despite legacy action name.
- Current widget accent is fixed rather than read from committed appearance; text color resolved at refresh can be stale after system day/night changes.

## Approved outcomes
1. **Accent:** only initials text, generic person icon, and overflow/menu icon receive committed saved accent. Do not accent-tint avatar fill, border, background, main name/title text, or add new controls. Read accent from committed repository state, never Appearance Editor draft. Use source-defined current light/dark Sapphire/Jade/Amethyst mappings; share one source of truth with `Theme.kt` where safe. Do not assume exact colors; inspect source.
2. **System mode:** widget follows Android system light/dark, not app-selected theme. Use Android 12+ supported RemoteViews light/dark color-pair APIs where verified. Ensure text and icons maintain contrast when system mode changes without relying on delayed refresh. Verify exact SDK method signatures and supported remotable methods in current project; do not invent APIs.
3. **VeryNarrow:** remove repeated initials in the text area when initials are already shown in avatar; retain useful photo/initials/generic-person fallback and existing body tap. No new profile fields. Keep behavior clear when photo is present.
4. **Compact:** improve safe padding at rounded corners, title to **12sp** (from 11sp); retain minimum 48dp menu target and existing click identities.
5. **Standard:** overflow menu top-right, with visually centered main content and symmetric effective horizontal spacing; preserve 48dp menu tap target and no overlap.
6. **Expanded:** consistent padding and name/title hierarchy without changing horizontal arrangement.
7. **Adaptive text:** at font scale >=1.3 reduce name/title line limits to one in Standard and Expanded; at >=1.5 hide Compact title. Apply provider line-limit policy rather than relying on unused model fields. Prevent clipping; if exact thresholds prove inadequate on emulator, report for owner decision before expanding scope.
8. **All sizes:** preserve existing profile visibility rules (name/title/photo), navigation, multiple widget instances, size mapping, update/restore behavior, and saved-data-only refresh. Avoid replacing existing RemoteViews architecture.
9. **Picker preview:** DEFER; do not modify protected provider XML or picker placeholder as part of this milestone.
10. **Accent palette:** ONLY three currently implemented accents. The overarching V1.2 requirements document also calls for Warm Gold/Coral/Silver, but owner expressly deferred introducing those during widget refinement. Record as outstanding cross-milestone requirement; do not silently mark six-accent requirement complete.

## Candidate implementation files (not authorization)
- Modify: `QuickCardWidgetModel.kt`, `QuickCardWidgetProvider.kt`, `quick_card_widget_compact.xml`, `quick_card_widget_standard.xml`, `quick_card_widget_expanded.xml`.
- Possibly modify `QuickCardWidgetPolicy.kt` for adaptive text if needed; inspect first.
- Add minimal shared accent-pair helper only if needed, ideally reusing actual Theme.kt mappings without changing approved app appearance.
- Update targeted widget tests and, only if required, `strings.xml` for a concise VeryNarrow empty-state label (requires owner review if adding wording).
- Do NOT modify `QuickCardWidgetMenu.kt`, menu activity, `MainActivity.kt`, `ProfileViewModel.kt`, manifest, provider config, codecs, schema, QR, card styles, or signing.

## Stop conditions
Stop if system-mode fix requires manifest/provider-config changes, a new dependency, undocumented RemoteViews calls, altering click identity, changing saved schema, or adding the three future accents. Provide exact reason and proposed diff for separate approval.
