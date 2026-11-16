# Widget refinement decision record — 2026-11-04

## Explicitly approved
- Accent only on initials, person icon and menu icon; use saved committed appearance.
- System light/dark mode controls widget colors.
- VeryNarrow: remove duplicate initials.
- Standard: menu top-right; improve centering.
- Consistent padding and compact title 12sp.
- Adaptive text limits: Standard/Expanded one-line at >=1.3x; Compact title hidden at >=1.5x.
- Update corresponding tests; preserve four logical size classes, three current accents, navigation and existing instances.
- Defer widget-picker preview changes.
- Prepare implementation skill only; **no application code modification yet**.

## Deferred/unresolved
- Warm Gold, Coral, Silver are required by overarching V1.2 approved requirements but absent from current code according to read-only report; not authorized in this widget task.
- Contrast and font clipping risks require emulator confirmation.
- No signed release, phone installation, manifest/provider-info edits or schema changes.

## Evidence
Source basis: Codex read-only Quick Card Widget Investigation report supplied in conversation; V1.2 requirements §5 and decision V12-11. Source paths and line numbers must be revalidated against user's current Windows checkout before coding.
