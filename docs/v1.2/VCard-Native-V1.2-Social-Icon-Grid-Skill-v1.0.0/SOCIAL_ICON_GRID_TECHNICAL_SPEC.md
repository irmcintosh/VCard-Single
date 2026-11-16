# Social Icon Grid — Technical Specification

## Approved scope
My Card rendering and its identical Appearance Editor live preview; six non-Classic styles only. Classic's renderer, link presentation, spacing, typography, order, colors, and interaction remain byte-for-byte unchanged unless owner separately approves a different change. Preserve Minimal's Professional Links as one optional slot.

## Baseline (from read-only Codex investigation; verify in live workspace)
- `SocialProfile` in `Profile.kt` stores id, platform, label, HTTPS URL, card visibility, contact-export visibility, legacy field. `ProfileCodec.kt` and `BackupDataCodec.kt` persist links; list order and duplicates are significant.
- `SocialPlatformOption` IDs: `linkedin`, `github`, `facebook`, `instagram`, `x`, `youtube`, `tiktok`, `threads`, `bluesky`, `mastodon`, plus Other. `SocialPlatformSelection.fromSaved` accepts aliases and `twitter`→X.
- `toMyCard` produces `CardProfessionalLink`; `visibleProfessionalLinks` filters hidden, invalid, and blank-label entries without reordering.
- `ProfileCard` is shared by My Card and Appearance Editor live preview; thumbnails are separate static drawings.
- Classic uses private `ProfessionalLinks` in `ClassicCard.kt`; six other styles use `ProfessionalLinkList` in `CardSections.kt`.
- Existing click path: `Social` action → `openContactAction` → validated HTTPS browser `ACTION_VIEW`, with no-handler feedback.
- Existing drawable resources: `ic_linkedin`, `ic_github`, `ic_external`; provenance of first two is currently unknown and must be reviewed, not presumed cleared.

## Implementation plan (proposal, verify before edits)
1. Add `ui/screens/card/SocialIconGrid.kt` for reusable presentation and pure mapping/column calculation. Use the existing `SocialPlatformSelection.fromSaved` rather than ad hoc lowercase matching. Do not mutate saved platform values.
2. Map ten supported platforms to reviewed bundled assets; Other and unknown values use the existing `ic_external` fallback. `twitter` maps to X. Keep mapping deterministic and testable.
3. Render in the existing order with duplicates intact; do not deduplicate or impose a new count cap. If using Compose keys, use a collision-safe identity including position; no assumption of unique labels/URLs.
4. Icon above a **visible** label, max two lines with ellipsis. Provide the complete meaningful screen-reader description using the existing `social_open_description` and URL; ensure no duplicate/confusing announcements. 48 dp minimum touch target in both dimensions, keyboard/focus behavior, and large-font legibility. Do not truncate accessibility content.
5. Responsive grid: 1–4 columns, based on actual available container width, font scale, minimum cell width, and link count. Avoid horizontal overflow or clipping at 1.5x/2.0x. Choose exact cell minimum/spacing after preview verification; do not invent a fixed spec as an approved number. Handle 1, 2, 5, 11+ links and very long labels.
6. Keep existing URL click semantics and failure handling. Never open arbitrary unvalidated schemes.
7. Integrate in `ProfessionalLinkList` in `CardSections.kt` (prefer preserving established name/call contract). Preserve existing per-style wrappers/section labels, adapting only where necessary:
   - Minimal: optional Professional Links slot unchanged; section is one selected item.
   - Professional: within compact panel, adjust list-specific dividers only as required.
   - Modern: visually distinguish grid cells from primary contact action tiles.
   - Executive: restrained treatment and existing divider.
   - Editorial: preserve section typography and hierarchy.
   - Horizon: calculate columns from the actual opaque content panel width.
8. Shared `ProfileCard` makes the live preview match My Card. Do not alter thumbnail art without explicit approval.
9. Preserve light/dark and accent contrast. **Do not assume brand icons may be recolored:** asset-specific usage restrictions govern. If a consistent accent tint conflicts with a platform's rules, escalate rather than silently recoloring.

## Expected file scope (not an unconditional permission list)
Create: `ui/screens/card/SocialIconGrid.kt`, approved vector drawables for eight missing platforms (possibly replacements for two existing unknown-provenance assets only after approval), focused tests, icon provenance record.
Modify: `CardSections.kt`, focused tests, potentially six non-Classic style files only when demonstrably required; `strings.xml` only if existing accessible string is insufficient.
Do not edit: `ClassicCard.kt`, profile models/codecs/schema, ViewModel/editor, `MainActivity`, QR exporter, Custom Share, Hosted Profile, widget, Manifest, Gradle, signing, version.

## Stop conditions
Unknown asset provenance or incompatible trademark terms; unapproved dependency; new schema requirement; any need to modify Classic or protected files; significant mismatch between investigation and live code; failures needing changes outside approved scope.
