# Card Style Shared Rules — V1.2

**Status:** Implementation guidance derived from approved V1.2 requirements. Detailed visual arrangements are **proposals**, not individually owner-approved pixel-perfect designs. Approved documents and actual V1.1 Classic implementation take precedence.

## Authority and scope
- Read `docs/v1.2/V1.2_REQUIREMENTS_APPROVED.md`, `V1.2_DECISION_LOG.md`, `V1.2_ROADMAP_AND_PROGRESS.md`, `V1.2_TECHNICAL_CONSTRAINTS.md`, and `04_UI_DESIGN_GUIDELINES.md` where available.
- Seven styles only: Classic, Minimal, Professional, Modern, Executive, Editorial, Horizon. No Compact.
- V1.2 Milestone 1A schema 5 and Milestone 1B extracted Classic renderer are already implemented; do not revert them to the older documentation's historical schema 4 description.
- The source code is authoritative for identifiers, available profile fields, existing callbacks, theme colors and existing V1.1 rendering behavior.

## Rendering contract
- My Card and the large Appearance Editor preview must use the same card renderer and the same draft/saved profile mapping. Gallery thumbnails must visually differentiate styles without triggering external actions.
- Except Minimal, styles must preserve **all existing eligible fields and actions**, subject to existing visibility and empty-value rules. Respect portrait, cover, links, hosted profile, public key and existing contact actions where applicable.
- Minimal: Full Name mandatory; 0–4 selected optional items; selections persist through style changes; selected-but-empty remains selected but produces no blank row; the editor indicates missing data. Never delete hidden profile data.
- Keep card style, accent and light/dark theme independently selectable. Preserve source-defined Sapphire/Jade/Amethyst. New accents belong to a separate implementation increment.
- Preserve the existing screen-level navigation, Custom Share and QR payloads, widget, profile persistence, and app identity.
- Use existing Material 3/project resources; no unapproved dependencies, SDK/Gradle changes, or external image assets.

## Visual acceptance criteria
- [ ] Distinct from the other six styles by layout/hierarchy, not only color.
- [ ] Readable in dark and light themes with existing accents.
- [ ] Long names, long links, missing photos/covers and empty fields are handled gracefully.
- [ ] No clipping/overlap on narrow emulator screens or at font scale 1.5+.
- [ ] Interactive targets are at least 48dp and accessibility labels are meaningful.
- [ ] No data lost when changing styles or saving appearance.
- [ ] Actions and URLs match the saved profile and existing behavior.
- [ ] Large editor preview matches the My Card renderer; thumbnail is clearly representative.
- [ ] No claims of pixel-perfect equivalence without emulator screenshot comparison.

## Appearance Editor acceptance
- [ ] Seven visual thumbnails in a scrollable gallery, clearly indicating selection.
- [ ] Larger live preview below gallery; preview reflects draft theme, accent, style and Minimal selections.
- [ ] Minimal selection counter n/4 and fifth-selection prevention.
- [ ] Save persists; Cancel discards; Restore Default changes draft only (Dark / Sapphire / Classic / zero Minimal items).
- [ ] Unsaved-change confirmation preserved, including nested navigation.

## Review protocol
1. Run approved unit tests, debug assembly, and Android-test compilation.
2. Inspect each style on Android Emulator with populated and sparse profiles, light/dark, long text and larger fonts.
3. Compare Classic to approved V1.1 screenshots; do not substitute generated concept images.
4. Record each issue by style, screen state, screenshot, expected behavior, actual behavior and severity.
5. Fix only failing styles/criteria and rerun regressions. Physical phone receives released signed builds only.

## Protection
Before any code edits, follow `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` and verified external source backups. Never modify or relocate `vcard-native-release.p12`, AndroidManifest, build configuration, package ID, or signing without separate approval.
