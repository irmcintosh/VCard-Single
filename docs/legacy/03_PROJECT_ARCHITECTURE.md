# VCard Native — Architecture

**Status:** Proposed implementation blueprint; finalize versions after environment setup.

## Principles
Native Kotlin + Jetpack Compose; Material 3; MVVM; local-first data; testable components; no NFC or mandatory backend.

## Suggested package structure
```text
app/src/main/java/<application-package>/
  MainActivity.kt
  navigation/          App navigation and destinations
  ui/theme/            Color, typography, shape, theme
  ui/components/       Reusable card, button, QR components
  ui/screens/card/     Main card UI and view model
  ui/screens/share/    QR and share UI
  ui/screens/profile/  Edit profile UI and view model
  ui/screens/settings/ Appearance and preferences
  data/model/          Profile data model
  data/local/          DataStore persistence and image paths
  data/repository/     ProfileRepository interface/implementation
  domain/              Validation, vCard formatting, QR payload
  platform/            Intents, FileProvider sharing, photo picker
```

## Data flow
Compose UI -> ViewModel -> Repository -> local persistence. ViewModel exposes immutable `StateFlow` UI state; UI observes it lifecycle-aware. Keep Android Context-heavy operations in platform adapters.

## Storage plan
- **DataStore:** Small profile settings and appearance preferences; consider typed serialization for structured profile data if justified.
- **App-private storage:** Imported profile/cover images; store file references, not full bitmaps in DataStore.
- **Room:** Not needed for one local profile; add only if multiple entities or complex queries emerge.
- Avoid storing personal data in logs; avoid unnecessary runtime permissions.

## Sharing plan
- QR mode A: locally generate QR from escaped vCard text; validate payload length and camera compatibility.
- QR mode B: optional public URL; label as online-dependent.
- `.vcf`: generate valid vCard text with line endings/escaping, save temporarily in cache, and share using `FileProvider` content URI with read permission.
- Call/SMS/email/browser actions: `ACTION_DIAL`, `ACTION_SENDTO` with `smsto:` or `mailto:`, and `ACTION_VIEW` for HTTPS links; handle absent apps.
- Contact-save UX: allow recipients to import `.vcf`; do not assume direct Contacts Provider writes are required.

## Security and quality
Validate URLs and contact fields, sanitize filenames, minimize permissions, support accessibility, keep offline features offline, test on Pixel and second scanner device.

## Dependency policy
Pin versions only after checking current Android official guidance and compatibility; record exact versions in Gradle and progress log. Google Wallet requires a separate feasibility design review.
