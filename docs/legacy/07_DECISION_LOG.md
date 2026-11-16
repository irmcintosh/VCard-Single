# VCard Native — Decision Log

| ID | Decision | Status | Rationale / caveat |
|---|---|---|---|
| D-001 | Build fully native Android app in Kotlin/Compose | Approved direction | Avoid WebView wrapper |
| D-002 | Use premium dark-first Material 3 visual design | Approved direction | Based on generated mockup |
| D-003 | Exclude all NFC functionality | Approved | Explicit user request |
| D-004 | Core profile, QR, and vCard features work offline | Approved direction | Reliable sharing without network |
| D-005 | Google Wallet remains optional | Deferred | Must verify issuer/API requirements |
| D-006 | Use GitHub Pages card as functional reference | Approved reference | Existing site not embedded in app |
| D-007 | Use step-by-step Android Studio instruction | Approved | User is new to toolchain |
| D-008 | Hybrid sharing (vCard QR + optional web link) | Proposed | Recommended, not explicitly selected |
| D-009 | Use project-only memory | Recommended | User should confirm project creation setting |
| D-010 | `com.vcardnative.app` application ID | Placeholder | Must confirm uniqueness and ownership |
| D-011 | 2026-10-31: FG-00 STATUS: CLOSED — OWNER ACCEPTED WITH DOCUMENTED LIMITATIONS | Explicit owner acceptance | Accepts SDK 36, isolated toolchain/build/dependency/unit/lint evidence and API 31 install/launch/UI rendering/cleanup. Retains incomplete API 31 extended crash/ANR/responsiveness, untested API 37 app runtime, parser/native-symbol/lint warnings and unverified production dependencies/release/features. [Approval record section 21](../P0.5%20Technical%20Architecture%20%26%20Feasibility/P0.5_APPROVAL_RECORD.md#21-explicit-owner-fg-00-closure--2026-10-31). Other gates, technical decisions and phase statuses unchanged; no production or next-phase authorization. |
| D-012 | 2026-11-01: Milestone 1.12 Security & Release Hardening CLOSED — OWNER ACCEPTED; Milestone 1.13 NOT STARTED | Explicit owner approval | The owner explicitly accepted documented residual risks and limitations, confirmed production-signing isolation from Gradle build workers, and authorized M1.12 closure. The 3 Critical / 21 High build-tool advisory matches remain unresolved, not remediated; accepted deferred tests and product limitations remain documented. See [M1.12 Step 4D final security acceptance](M1.12_STEP4D_FINAL_SECURITY_ACCEPTANCE.md). Milestone 1.13 requires separate explicit owner approval; no later milestone is authorized by this decision. |
| D-013 | 2026-11-01: Milestone 1.13 Branding & Naming COMPLETE — OWNER ACCEPTED; Milestone 1.14 NOT STARTED | Explicit owner acceptance | The approved premium full-color and monochrome icon designs (`design/branding/launcher/source/`) are final. Adaptive and legacy launcher resources are implemented. By owner decision, legacy `ic_launcher_round.png` resources are frameless circles to eliminate `IconLauncherShape` warnings; the glowing frame is kept in `ic_launcher.png` and the SVG masters. Debug and unsigned release builds passed; lint reported 57 warnings, 0 errors, and no icon-related findings. The original launcher backup ([M1.13_STEP3_ICON_BACKUP](M1.13_STEP3_ICON_BACKUP/)) is preserved. Application ID `com.mackseoul.vcardnative` and display name "VCard Native" are retained; D-010's placeholder is superseded for the application ID only. Themed-icon device testing, API 31 verification and physical-device testing are deferred to Milestone 1.14. Previously accepted residual risks (D-012) and known issues remain documented and unresolved. See [M1.13 Step 3C report](M1.13_STEP3C_LAUNCHER_ICON_IMPLEMENTATION.md). No later milestone implementation is authorized by this decision. |

## Change protocol
When a decision changes, add a new entry with date, reason, and affected documents. Do not silently overwrite historical decisions.
