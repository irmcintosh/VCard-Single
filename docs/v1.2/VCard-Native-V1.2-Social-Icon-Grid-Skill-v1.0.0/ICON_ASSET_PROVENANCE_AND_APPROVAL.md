# Icon Artwork — Provenance, Brand Guidelines, and Approval Gate

**Gate status: OPEN / NOT SATISFIED.** This skill package includes no third-party logo artwork and makes no claim that any platform's terms have been verified. Owner approval of the *review process* is not blanket permission to download, alter, or ship every trademark.

## Mandatory review per icon
For each platform, record: (1) asset filename; (2) artwork source and direct official or appropriately licensed URL; (3) source/rights holder; (4) copyright/license text and attribution requirements; (5) relevant trademark/brand guideline URL and permitted context; (6) whether monochrome, recoloring, cropping, or modification is allowed; (7) asset hash SHA-256; (8) review date; (9) reviewer and owner approval status; (10) required notices or credits. Keep copies/links to applicable license notices where redistribution permits.

## Platforms to review
| Platform | Stored ID | Current asset status | Approval |
|---|---|---|---|
| LinkedIn | `linkedin` | Existing `ic_linkedin` provenance unknown | Pending |
| GitHub | `github` | Existing `ic_github` provenance unknown | Pending |
| Facebook | `facebook` | Missing | Pending |
| Instagram | `instagram` | Missing | Pending |
| X | `x` | Missing; alias `twitter` | Pending |
| YouTube | `youtube` | Missing | Pending |
| TikTok | `tiktok` | Missing | Pending |
| Threads | `threads` | Missing | Pending |
| Bluesky | `bluesky` | Missing | Pending |
| Mastodon | `mastodon` | Missing | Pending |
| Other | custom/unknown | Reuse existing `ic_external` | Existing generic asset; review provenance as appropriate |

## Decision rules
- Prefer official platform assets and explicit brand guidance where feasible, or clearly licensed third-party artwork whose trademark use is also suitable.
- A permissive copyright license for an icon drawing **does not** grant trademark permissions.
- Do not automatically tint every logo with the selected app accent. Use monochrome only if permitted; otherwise use the brand-compliant color treatment.
- No hotlinked images, network fetches, new image loaders, unknown-provenance copied SVGs, or fabricated license statements.
- If guidance prohibits the intended presentation, propose a compliant alternative and **request owner approval** before adding it.
- Before implementation, present the completed asset matrix and representative visual samples for owner review. A legal specialist may be appropriate for uncertain commercial trademark use; this document is not legal advice.

## Provenance record destination
Propose `docs/v1.2/SOCIAL_ICON_ASSET_PROVENANCE.md` in the live project; creating/updating it requires implementation-stage authorization and verified backup if replacing an existing file.
