# VCard Native — Product Requirements Document (PRD)

**Document:** P0.1 Product Definition & Requirements  
**Version:** 1.0 — Approved baseline  
**Date:** 2026-10-29  
**Status:** APPROVED — P0.1 product requirements baseline  
**Project phase:** Phase 0 / P0.1 COMPLETE; P0.2 NOT AUTHORIZED  
**Implementation status:** Not started

## 1. Executive summary
VCard Native is a premium, genuinely native Android digital business card application, initially for personal use on a Google Pixel, with maintainable architecture that can accommodate future expansion. It is not a WebView wrapper. It stores its profile and imagery locally, works offline for core card, editing, QR, contact export and local backup operations, and enables recipients to obtain contact information without installing the app. The existing GitHub Pages card (https://irmcintosh.github.io/vCardB/) is a functional reference, not the application runtime.

## 2. Product vision and audience
**Primary audience:** The owner of the app, using a Google Pixel for professional introductions and contact sharing.  
**Future audience:** Potential other users and multiple profiles, without committing those features to v1.0.  
**Product objective:** Present a polished professional identity, make contact exchange dependable and simple, preserve user control over visible/shared information, and avoid dependence on cloud accounts.

## 3. Approved decisions register
| ID | Approved decision | v1.0 interpretation |
|---|---|---|
| P0.1-01 | Personal-first, expandable | Personal Pixel use first; architecture should not block later expansion. |
| P0.1-02 | One card now, multiple later | Exactly one editable active profile in v1.0. |
| P0.1-03 | Comprehensive professional profile | Identity, contact details, imagery, professional links and optional user-provided public key. |
| P0.1-04 | Hybrid sharing | Offline contact QR default; separate optional web-profile QR; .vcf and native sharesheet. |
| P0.1-05 | Selective field visibility | Separate controls for card display and contact export; preview before export. |
| P0.1-06 | Curated customization | Dark/light, curated accent colors, profile/cover photos, fixed professional design language and subtle animations. |
| P0.1-07 | Local storage + manual backup | Offline core; manual full-app backup and restore; no mandatory cloud. |
| P0.1-08 | Google Wallet deferred | No Wallet UI/button or integration in v1.0. |
| P0.1-09 | Production-quality personal release | Thorough verified Pixel release; no Play Store publication requirement. |
| P0.1-10 | Strictly fixed v1.0 scope | New product features deferred absent explicit revision. |
| P0.1-11 | Measurable production-quality standards | Formal performance, reliability, accessibility, privacy, compatibility gates. |
| P0.1-12 | Guided initial profile setup | Reuse standard editor to guide first-time creation; no separate account/onboarding system. |

## 4. Core user journeys
1. **First run:** Open app with no profile; enter essential information using guided reuse of the profile editor; save and view My Card.
2. **Present card:** Open My Card; view cover, photo, identity, bio and enabled contact actions; hidden/missing fields do not produce broken controls.
3. **Edit profile:** Change fields, photos, links, visibility and curated appearance settings; save locally; data survives restart.
4. **Share offline:** Open Share; show default contact QR containing supported contact data; recipient scans using compatible scanner and can save contact without VCard Native.
5. **Share online:** Switch to distinctly labeled web-profile QR when a valid public URL is configured; recipient opens hosted site with connectivity.
6. **Share file:** Preview export data, generate .vcf and share using Android Sharesheet; compatible recipient contact app imports supported fields.
7. **Preserve data:** Export application backup to user-chosen location; restore validated backup later without corrupting existing data.

## 5. Functional requirements
### FR-01 — Profile and card
- Support one locally editable professional profile.
- Support full name, professional title, organization, biography, location, phone, email, website and optional professional/social URLs (including LinkedIn and GitHub as examples).
- Support locally retained profile photograph and cover image.
- Support optional downloadable **public** encryption key only when a valid user-supplied public key is present; no private-key export.
- Present native call, SMS, email and web actions when relevant fields exist; handle missing handlers gracefully.
- Hide or disable unavailable optional content appropriately; do not show fake functionality.

### FR-02 — First-run setup and editing
- If no profile exists, offer a guided setup through the reusable profile editor.
- Permit editing and saving profile fields and images on device.
- Validate inputs and avoid silently discarding unsaved edits; exact required fields, validation and discard behavior are to be specified during UX/data planning.

### FR-03 — Visibility and privacy
- Support independent per-field visibility for the on-screen card and for contact export where the field/format supports it.
- Ensure fields excluded from export are not present in QR or .vcf payloads.
- Provide preview of information intended for contact export.
- Make clear that changing local visibility does **not** update the independently hosted GitHub Pages profile or revoke already-shared information.

### FR-04 — Sharing
- Generate an offline contact QR from a standards-compatible contact payload; it is the default QR mode.
- Offer a separate web-link QR when a valid hosted URL is configured, clearly distinguishing online dependence.
- Export a standard .vcf file, using secure Android sharing behavior.
- Support Android Sharesheet for supported contact files, QR images and web links; support copying profile URL and saving/sharing QR image as planned.
- Test actual recipient scanning/import behavior and QR size constraints; do not promise universal scanner support.

### FR-05 — Appearance
- Provide premium dark-first styling with an optional light theme, curated accent choices, user-selected profile/cover images, coherent typography and restrained animation.
- Maintain accessible contrast and layout behavior; exact tokens/layouts belong to P0.3.

### FR-06 — Storage, offline and recovery
- Save profile, images, appearance and visibility preferences locally; survive process termination and device restart.
- Keep viewing, editing, contact QR generation, .vcf generation and local backup/restore functional without network connectivity.
- Provide manual app-specific backup and restore covering profile data, images, appearance and visibility settings.
- Validate imported backup before replacing existing state; failed restore must not silently destroy valid data.
- Distinguish full app backup from a contact-only .vcf file.

## 6. Nonfunctional requirements and provisional release gates
| Category | Requirement / proposed verification target |
|---|---|
| Performance | Main card usable within **2 seconds of warm launch** on target Pixel; QR generation within **1 second** for supported payload sizes; no sustained UI stalls. Measurement method finalized in P0.6. |
| Reliability | No known reproducible release-blocking crashes or data-loss defects in approved workflows. |
| Persistence | Profile, imagery and settings persist after app restart and device restart. |
| Offline | Airplane-mode test for card, edit, contact QR, .vcf and local-file backup/restore. |
| QR and contacts | QR scan validated on a second compatible device; .vcf import verified in a compatible contacts app, preserving essential supported fields. |
| Backup | Round-trip backup/restore verified, including images/settings; corrupt input safely rejected; failure preserves valid data. |
| Accessibility | Essential flows usable with screen reader and enlarged text; readable dark/light contrast; touch targets generally >=48 dp; meaningful control labels. |
| Privacy/security | Appropriate app-private storage, minimal permissions, safe file sharing, validated URLs/imports, no personal data in diagnostic logs, no private-key exports. |
| Device/release | Test on actual Pixel; record model/OS/SDK; produce signed installable APK; no Google Play publication required. |

## 7. v1.0 boundaries and exclusions
**Deferred, not part of v1.0:** multiple cards, profile switching, advanced sharing presets, arbitrary custom fields, extra templates/layouts/fonts, automatic/cloud sync, Google Wallet, existing-vCard import, additional networking functionality.  
**Explicit exclusions:** NFC of any kind, NFC tags, Android Beam, mandatory authentication, subscriptions, unnecessary backend services, WebView-based app interface.  
**Change policy:** New functionality is not added during subsequent planning/implementation without explicit revision of P0.1-10; clarifying approved features and necessary correctness/security fixes are allowed.

## 8. Proposed initial screen-level capability map (not final UX approval)
- **My Card:** Profile display and native actions; QR entry point.
- **Share:** Contact QR / optional Web Link QR; QR save/share, .vcf and native sharing access.
- **Profile / Edit:** Guided first-run and subsequent editing, images and visibility.
- **Appearance:** Curated theme/accent/photo controls, possibly nested within Profile or Settings.
- **Settings:** Backup/restore and relevant configuration.
- **Preview:** Contact export preview; may be a sheet rather than a full screen.
The earlier ten-screen mockup is a conceptual reference, **not** a mandate for ten destinations. Final screen hierarchy is reserved for P0.2. No Google Wallet screen in v1.0.

## 9. Dependencies and unresolved specification details (not feature additions)
1. Which identity/contact fields are mandatory for completing initial setup; validation rules and maximum lengths.
2. Exact list and ordering of supported professional links; public-key file type, validation and download mechanism.
3. Per-field display/export defaults and any non-hideable essential identity fields.
4. vCard version/encoding/escaping, supported field mapping, QR payload size limits and fallback when too large.
5. Behavior when web URL is missing, invalid, or out of date; local visibility does not control hosted site.
6. Backup format/versioning, storage destination, integrity checks, restoration conflict/overwrite behavior.
7. Pixel model/OS, Android SDK/toolchain/dependencies and implementation architecture (P0.5).
8. Precise benchmark method and recipient compatibility matrix (P0.6).
9. Exact navigation, empty states and confirmation behavior (P0.2); design tokens and layouts (P0.3).

## 10. Phase 0 planning deliverables and gate
- **P0.1:** This PRD; explicitly reviewed and approved by the project owner on 2026-10-29; COMPLETE.
P0.2 through P0.7 remain pending; P0.2 is NOT AUTHORIZED.
- **P0.2:** UX Flow and Navigation Specification.
- **P0.3:** Approved UI/UX Design Specification.
- **P0.4:** Data Model and Sharing Specification.
- **P0.5:** Technical Architecture Document.
- **P0.6:** Development Roadmap and Test Plan.
- **P0.7:** Final approved v1.0 Development Blueprint.
**No Android Studio project creation, code, or implementation until all Phase 0 planning is approved.**

## 11. Source documents and recordkeeping
This approved PRD consolidated the 12 decisions approved in the planning conversation and the uploaded baseline Project Specification, Architecture, UI Design Guidelines, Development Roadmap, Progress and Decision Log. The existing Progress document still describes environment setup as next; it must be reconciled to the Phase 0-first policy. Following approval, update `01-—-Project-Specification.txt`, `05_DEVELOPMENT_ROADMAP.md`, `06_DEVELOPMENT_PROGRESS.md`, `07_DECISION_LOG.md`, and cross-reference design/architecture docs as needed. **Those source files were not changed during preparation of this PRD.**

## 12. Approval status summary
**Current status:** APPROVED — PRD v1.0; P0.1 COMPLETE.  
**Approval date:** 2026-10-29 (October 29, 2026).  
**Approved decisions:** P0.1-01 through P0.1-12 remain approved and binding.  
**P0.2 status:** NOT AUTHORIZED; not started; must not begin without explicit user approval.


## Approval record
- **Approved by:** Project owner (explicit approval in conversation)
- **Approval date:** 2026-10-29
- **Approval scope:** P0.1 Product Requirements Document v1.0, decisions P0.1-01 through P0.1-12.
- **Change control:** Fixed v1.0 product scope; later planning may clarify implementation without adding features unless explicitly authorized.
- **Next milestone:** P0.2 is NOT authorized and must not begin without separate approval.
- **Implementation:** Not started; no Android build or device test claimed.
