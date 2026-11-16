# VCard Native — UI Design Guidelines

**Status:** Approved visual direction; generated mockup is conceptual, not a pixel-perfect implementation contract.

## Visual identity
- Premium dark navy/charcoal surfaces; restrained electric-blue accent.
- Rounded cards, consistent spacing, polished typography, soft elevation, clear contrast.
- Optional light mode and user-selectable accent colors.
- Use Material 3 components and accessibility semantics; avoid decorative effects that impair readability.

## Navigation
Bottom navigation: **My Card**, **Share**, **Profile**. Secondary routes for contact actions, edit form, appearance, preview, sharing options, and settings.

## Screen contracts
### My Card
Cover image, overlapping circular profile photo, name/title/organization, concise bio, four primary actions (Call, Email, Message, Website), secondary rows (location, public key if provided). Quick QR entry point.

### Share
Large high-contrast QR, explicit mode selector **Contact (vCard)** / **Web Link** (if configured), clear offline/online explanation, Save/Share/Copy/Export actions. QR must be scannable without decorative center obstructions unless tested.

### Contact Actions
Action sheet/list for available call, email, message, website, address, `.vcf` export, optional public key. Hide or disable actions without data.

### Edit Profile
Photo picker, text fields for name/title/organization/location/contact/bio, input validation, save confirmation, discard-changes handling.

### Appearance
Dark/light preview, accent color choices, cover image selection, layout variants only after initial UI works.

### Contact Preview / Save / Share Options
Show recipient-facing details, launch native share or contact import, and show only truthful success states. Do not claim a contact was saved unless the platform actually confirms it.

### Wallet / Settings
Wallet screen is a future concept, not a committed working feature. Settings may manage appearance, profile, backup/export, and app information.

## Implementation guardrails
- Treat mockup imagery, identity, and contact values as placeholders unless user supplies authorized assets/data.
- Use scalable Compose layouts for common Pixel screen sizes and font scaling.
- Provide light/dark contrast, minimum 48dp interactive targets, readable labels, and screen-reader content descriptions.
- No NFC affordances anywhere.

## Design acceptance
UI is visually consistent across screens; core actions remain discoverable; share QR scans reliably; all screens work with large text and narrow widths.
