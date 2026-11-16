# Social Icon Grid — Technical Specification v1.1.0

## Approved scope and reference
Follow `APPROVED_ICON_DESIGN.md` for the exact 11 symbol-to-platform mappings. The owner approved a custom icon set, replacing the v1.0.0 plan for official brand-logo downloads. The original user-provided mockup and approved revised mockup are visual references; do not invent a new icon language. Classic's renderer and resources remain byte-for-byte unchanged. Grid only in Minimal, Professional, Modern, Executive, Editorial, Horizon, and their shared Appearance Editor live preview.

## Existing architecture (from prior Codex report; verify live)
- `SocialProfile` in `Profile.kt` stores platform, label, URL, card visibility, export visibility; `ProfileCodec.kt` and `BackupDataCodec.kt` preserve list order and duplicates.
- `SocialPlatformSelection.fromSaved` handles platform aliases, including `twitter` → X; unknown/custom → Other.
- `toMyCard` and `visibleProfessionalLinks` feed `CardProfessionalLink` entries into `ProfileCard`.
- Classic uses its private `ProfessionalLinks` in `ClassicCard.kt`. Other six styles call `ProfessionalLinkList` in `CardSections.kt`.
- Existing Social action passes URL to `openContactAction`, which validates HTTPS and launches a browser with failure feedback.

## Implementation plan
1. Introduce `ui/screens/card/SocialIconGrid.kt` with reusable Compose layout, platform mapping, responsive column calculation. Use `SocialPlatformSelection.fromSaved` rather than separate string heuristics. Do not change persisted data.
2. Create local original icons via Android vector XML or Compose primitives for generic symbols. The approved typographic `in`, `f`, and `@` may be rendered with typography to ensure fidelity. Do not use any official logo kit, staged brand assets, traced paths, network images or third-party icon libraries. Reuse existing generic icons only when visually consistent and provenance is acceptable. `ic_external` is the agreed Other fallback.
3. Preserve order, duplicate entries, user labels and URL values. Use collision-safe keys when needed. No link-count cap.
4. Show icon above a visible label (maximum two lines with ellipsis); full label/URL in accessible `social_open_description`. Each actionable cell at least 48 dp in both dimensions; keyboard focus and TalkBack should work.
5. 1–4 responsive columns computed from actual container width, font scale and minimum cell width; no horizontal clipping at 1.5x/2.0x. Test 1, 2, 5, 11+ entries and very long labels. The approved mockup happens to use three columns, not a fixed requirement.
6. Preserve original `Social` click action, HTTPS validation, failure feedback and custom label display.
7. Prefer keeping `ProfessionalLinkList` name and call signature in `CardSections.kt` while switching only the six non-Classic styles to the grid. Keep style wrappers, Minimal's single optional slot, Professional's compact panel, Modern's distinct action tiles, Executive's restraint, Editorial's hierarchy and Horizon's opaque content panel.
8. `ProfileCard` already powers My Card and Appearance Editor live preview. Do not change gallery thumbnail drawings unless separately approved.
9. Theme-aware contrast: approved visual is white-on-black. In app, keep generic symbols readable in light/dark modes and accent themes without introducing official brand-color rules. Verify `in` and `f` are not copied/traced brand logos; record visual-similarity review.
10. Existing LinkedIn/GitHub artwork with unknown provenance remains untouched in Classic; do not change or reuse it in the new grid without separate authorization. This unresolved legacy concern must be reported, not silently fixed.

## Expected file scope (requires implementation approval)
Create: `ui/screens/card/SocialIconGrid.kt`, original vector drawables only where needed, focused tests, a project-local design/provenance record if explicitly authorized. Modify: `CardSections.kt`, relevant focused tests, six style files only if needed, strings only if necessary. Never modify Classic, model/codecs/schema, ViewModel/editor, MainActivity, Custom Share, Hosted Profile, widget, QR, Manifest, Gradle, signing or version.
