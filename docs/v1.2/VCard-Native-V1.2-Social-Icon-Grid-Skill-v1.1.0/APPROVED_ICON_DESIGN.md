# Owner-approved Social Icon Grid — visual design contract

Reference: user-supplied 3-column black/white mockup, subsequently revised in a generated mockup. **Only LinkedIn and Facebook symbols changed**; all other symbols and their labels are unchanged. The reference mockup is the visual authority; the table below is a precise text approximation for implementation. No image file is bundled here.

| Saved platform | Visible label | Approved custom symbol | Notes |
|---|---|---|---|
| linkedin | LinkedIn | lowercase `in` | Plain typographic glyph, NOT a traced LinkedIn logo or boxed brand mark |
| github | GitHub | angle-bracket slash `</>` | Generic code symbol |
| instagram | Instagram | outlined camera | Generic camera |
| facebook | Facebook | lowercase `f` | Plain typographic glyph, NOT a traced Facebook logo |
| youtube | YouTube | outlined circle with play triangle | Generic play button |
| tiktok | TikTok | single musical note | Generic note, not TikTok's stylized logo |
| x | X | outlined single speech bubble | Do not substitute the X brand mark |
| threads | Threads | `@` | Plain at-sign |
| bluesky | Bluesky | two overlapping chat bubbles | Not the butterfly brand mark |
| mastodon | Mastodon | outlined single speech bubble | Not the Mastodon brand mark |
| other / unknown | Other or saved custom label | chain-link icon | Reuse existing `ic_external` if its appearance matches; otherwise request approval before changing the agreed symbol |

- Preserve original user-defined visible labels where present; table names are example platform labels, not permission to replace saved custom labels.
- Black background/white glyphs in the approved visual reference; in-app treatment should follow theme contrast and selected accent without changing the symbol shapes. Do not assume a black background is required in every card style.
- Icon above visible label; two-line maximum with ellipsis, complete accessible announcement. Responsive 1–4 columns, at least 48 dp target width and height; avoid clipping at 1.5x and 2.0x font scales.
- Use original vector paths or typographic Compose drawing for these generic symbols, no remote downloads, brand-logo tracing, official logo assets, or new dependencies. Reuse existing vetted generic resources when matching the reference.
- All eleven symbols should feel like one family: consistent optical size, stroke, alignment and whitespace. The `in` and `f` glyphs can be solid while other icons are outlined, as in the approved mockup.
- No changes to Classic, including its existing icons/tints. Existing Classic provenance concern remains a separate release-review issue.
- Re-review visual similarity for `in`, `f`, and all symbols before release; custom creation alone is not a trademark clearance.
