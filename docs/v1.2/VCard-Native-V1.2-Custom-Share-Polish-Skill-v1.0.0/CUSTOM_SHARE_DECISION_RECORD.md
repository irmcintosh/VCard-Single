# Owner Decisions — Custom Share Polish

Approved in conversation, 2026-11-04:

| Topic | Decision |
|---|---|
| Preview placement | **Review only**; no Select-screen full preview |
| Preview source/order | Reuse exact existing contact export rows and export ordering |
| Missing values | Display disabled optional rows, labelled **Not in saved profile** |
| Selection feedback | Display `N of 4 optional fields selected` |
| Full Name | Mandatory and clearly labelled **Always included** |
| ViewModel exception | Narrow change to `ProfileViewModel.kt` solely for Custom Share preview session state/update |
| Strings | Add necessary `strings.xml` resources |
| Accessibility/responsiveness | Improve semantics, touch targets, large-font/narrow-screen buttons |
| Invalid saved data | Warn early in preview without changing export behavior |
| Selection persistence | Unchanged |
| QR/vCard/Sharesheet | Unchanged |
| Save-failure asymmetry | Preserve existing QR vs Share Contact behavior |
| New fields | None |
| Implementation authorization | **Not yet granted**; skill preparation only |

If a design or implementation choice conflicts with approved V1.2 requirements, stop and request owner approval.
