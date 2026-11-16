# Minimal — STYLE_SPEC.md

**Status:** Draft implementation-level design specification for owner review. Core behavior is approved; the suggested layout composition below is **proposed**, not yet an approved reference image.

## Design intent
Quiet, content-first presentation with user-selected content only.

## Composition guidance (proposal)
Full Name is always visible. Show only selected optional sections that contain saved values. Present 0–4 selected sections in a simple, balanced vertical composition; cover and photo appear only when selected.

## Distinguishing rules
Selections: Profile Photo; Cover Image; Professional Title; Organization; Biography; Phone; Email; Location; Website; Professional Links (single section); Hosted Profile Link/QR (single section). Never display a fifth selected section or silently clear a selection. Selected-but-empty sections remain selected in storage and are omitted visually; the editor indicates missing values.

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

## Minimal selection and rendering test matrix
- 0 selections: Full Name only.
- 1–4 selections: only the chosen nonempty sections appear.
- Attempt fifth: editor prevents it.
- Selected empty value: selection retained, no blank row, missing-value notice in editor.
- Switch Minimal → another style → Minimal: selections unchanged.
- Professional Links and Hosted Profile Link/QR each consume exactly one slot.
- Full Name is never an optional selection.

## Review status
- Implementation: NOT VERIFIED against this specification.
- Emulator visual acceptance: PENDING.
- Owner approval of exact composition: PENDING.
