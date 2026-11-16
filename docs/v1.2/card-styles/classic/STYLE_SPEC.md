# Classic — STYLE_SPEC.md

**Status:** Draft implementation-level design specification for owner review. Core behavior is approved; the suggested layout composition below is **proposed**, not yet an approved reference image.

## Design intent
Exact V1.1 My Card reference; no redesign.

## Composition guidance (proposal)
Retain the extracted ClassicCard.kt implementation and its original cover, overlapping portrait, identity, bio, contact rows, professional links, hosted profile actions and public-key presentation.

## Distinguishing rules
Do not alter spacing, typography, hierarchy, colors, shape, image crop, action ordering, or responsive rules. Verify against the actual V1.1 emulator/device screenshots, not concept art.

## Data and interactions
- Follow `../CARD_STYLE_SHARED_RULES.md` for the full renderer, action, theme, responsiveness and preview contract.
- Do not modify profile data or QR/share payloads when rendering this style.
- For non-Minimal styles, preserve every eligible Classic field and action; respect existing visibility rules.
- For Minimal, render only Full Name and selected nonempty sections; keep existing screen-level controls available.

## Visual reference
- `REFERENCE.png`: **not included**. Add only an owner-approved screenshot/mockup; do not treat generated concept art as approved automatically.
- No unverified exact dp sizes, font sizes, or hex colors are mandated here. For Classic, the existing V1.1 renderer and owner screenshots are the visual authority.

## Style-specific acceptance
- [ ] Composition matches the intent above and is visually distinct from the other six.
- [ ] Field visibility and interactions comply with shared rules.
- [ ] Empty values, absent images and long text render without blank placeholders or clipping.
- [ ] Dark/light and narrow-screen/font-scale checks pass.
- [ ] My Card and large editor preview show the same design for the same appearance state.
- [ ] Owner visually reviews emulator screenshot and approves or records corrections.

## Review status
- Implementation: NOT VERIFIED against this specification.
- Emulator visual acceptance: PENDING.
- Owner approval of exact composition: PENDING.
