# VCard Native — Development Progress

## Current Milestone Status — 2026-11-01

**Milestone 1.10: OWNER-APPROVED / CLOSED.** This records the project owner's approval supplied on 2026-11-01.

**Milestone 1.11: OWNER-ACCEPTED / CLOSED WITH DOCUMENTED LIMITATIONS (2026-11-01).** This records the owner's acceptance of Milestones 1.1–1.11. The Step 3 recovery/deletion and Step 4E structured-name implementation and verification evidence remains in [M1.11_RECOVERY_DELETION_EVIDENCE.md](M1.11_RECOVERY_DELETION_EVIDENCE.md) and [M1.11_STRUCTURED_NAME_EVIDENCE.md](M1.11_STRUCTURED_NAME_EVIDENCE.md). Acceptance does not mark the documented physical-device/recipient tests complete; those remain outstanding limitations.

Step 3 validation: full JVM suite 172 tests / 27 suites passed; debug APK, instrumentation APK compilation, and lint passed (57 lint warnings, no errors). Isolated API 37 and disposable API 31 each passed 56 instrumentation tests, three actual process-kill/replay scenarios, and two emulator reboots on the final APK. Recovery/pending controls were reachable at 200% font scale. Physical power-loss and recipient Android/iPhone compatibility are not claimed. The owner's emulator data was not used or modified.

**Step 4A: contact compatibility test preparation — 2026-11-01 (historical test-preparation record).** Prepared [physical-device instructions and package](../../android/verification/m111-contact-compatibility/README.md), [expected-results matrix](../../android/verification/m111-contact-compatibility/EXPECTED_RESULTS.md), [results worksheet](../../android/verification/m111-contact-compatibility/results.csv), and [field comparison](../../android/verification/m111-contact-compatibility/field-comparison.csv). Ten synthetic fixtures cover normal fields, country-code preferences, Unicode/escaping, privacy, mononym/empty N, large encodable QR and independent encoding/resource/geometry rejections. Focused JVM tests: 21 / 4 suites passed; full JVM regression: 173 / 28 suites passed, no failures/errors/skips. Generated VCF/QR bytes and PNG round trips are verified; 60 physical-device trials remain NOT_RUN. No production implementation/UI/schema/dependency changes, device/emulator operations, or Android/iOS recipient compatibility claims. The physical-device trials remain an outstanding limitation after owner acceptance.

At this historical Step 4A point, Milestone 1.12 had not started.

**Step 4E: owner-approved structured names — implemented / automated verification, physical results pending at time of record (2026-11-01).** Optional First/Middle/Last Name are added to existing Basic Information and Details without changing required Full Name, card presentation, navigation or dependencies. No name splitting or Full Name rewriting. Additive AppData schema 3 retains strict schema-1/2 readers and the unchanged backup formatVersion 1, A2 framing and durable-operation protocol. Name components are independently export-enabled (off by default); included components produce N before FN. Legacy all-empty/excluded name exports are byte-identical. Production standard structured export matches successful Candidate 01 SHA-256. Android Camera's valid NOTE newline-escape limitation remains documented; escaping is unchanged.

Final checks: **187 JVM tests / 29 suites passed**, debug build and instrumentation APK compilation passed; lint **57 warnings, zero errors**. Isolated API 37 passed **2 structured-name tests plus 56 existing instrumentation regressions**. New name fields and Continue/Save actions were reachable using real swipes at 200% font scale in portrait and landscape. Model/repository reconstruction, genuine schema-1/2 A2 load/upgrade, backup round trips and injected deletion/recovery failures passed; this step does not claim new physical power-loss, process-kill, reboot or recipient verification. All 79 recorded existing-fixture hashes remain unchanged. A nullable-callback dispatch defect exposed by managed name-workflow tests was corrected without changing coordinator transitions. See [Step 4E evidence and physical procedure](M1.11_STRUCTURED_NAME_EVIDENCE.md). The physical recipient tests remain incomplete despite subsequent owner acceptance of Milestone 1.11.

**Milestone 1.12: COMPLETE — OWNER ACCEPTED (2026-11-01).** The owner explicitly approved closure: “I explicitly approve the documented residual risks and limitations, confirm the production-signing isolation requirement, and authorize closing Milestone 1.12. Proceed with recording milestone closure, but do not start Milestone 1.13 until I approve.” Step evidence: [Step 4A final gate assessment](M1.12_STEP4A_FINAL_SECURITY_GATE_ASSESSMENT.md), [Step 4B dependency integrity and release tests](M1.12_STEP4B_DEPENDENCY_INTEGRITY_AND_RELEASE_TEST_REPORT.md), [Step 4C build-tool advisory triage](M1.12_STEP4C_BUILD_TOOL_ADVISORY_TRIAGE.md), and [Step 4D final security acceptance](M1.12_STEP4D_FINAL_SECURITY_ACCEPTANCE.md). Recorded verification: 187 JVM tests passed; 58 release-variant instrumentation tests passed on isolated API 31 and 58 on isolated API 37; debug and release builds passed; strict Gradle dependency verification passed; lint reported 59 warnings and zero errors. No confirmed vulnerability was identified in the queried packaged application runtime graph. The 3 Critical and 21 High OSV version matches in build/test tooling remain unresolved; exploitability was not established, and no dependency remediation was performed. Accepted limitations remain documented, including incomplete chooser lifecycle and manual release walkthroughs, process-kill tests not run in Step 4B, plaintext user-directed backups, no encryption at rest, disabled R8/resource shrinking, Android Camera Notes newline display, schema-3 downgrade incompatibility, and incomplete physical structured-name recipient testing. Production signing credentials must remain isolated from Gradle build workers. The test-signed APKs are not production-distributable; no production signing credential was created or changed.

**Milestone 1.13: COMPLETE — OWNER ACCEPTED (2026-11-01).** The owner accepted the final Branding & Naming implementation. The following is recorded:

- **Artwork:** the approved premium full-color and monochrome icon designs are final.
- **Launcher resources:** adaptive and legacy resources are implemented.
- **Round icons:** the legacy round icons are frameless, which eliminates the `IconLauncherShape` warnings.
- **Builds:** the debug and unsigned release builds passed.
- **Lint:** 57 warnings and 0 errors, with no icon-related findings.
- **Backup:** the original launcher backup in [M1.13_STEP3_ICON_BACKUP](M1.13_STEP3_ICON_BACKUP/) is preserved.
- **Deferred to Milestone 1.14:** themed-icon device testing, API 31 verification and physical-device testing.
- **Unchanged:**
  - application ID `com.mackseoul.vcardnative`;
  - display name "VCard Native";
  - version, signing and build configuration.
- **Residual risks:** previously accepted residual risks and known issues remain documented and are not resolved by M1.13. These include:
  - the M1.12 build-tool advisory matches;
  - Android Camera Notes newline display;
  - schema-3 downgrade incompatibility;
  - incomplete physical structured-name recipient testing.
- **Next milestones:** Milestone 1.14 implementation has not started; Milestones 1.14–1.16 have not started.

Milestone 1.13 step history:

- **Step 1:** read-only branding audit in [M1.13 Step 1 audit](M1.13_STEP1_BRANDING_AUDIT.md).
- **Step 3:** interrupted. It left provisional, unapproved raster launcher resources and backed up the originals to [M1.13_STEP3_ICON_BACKUP](M1.13_STEP3_ICON_BACKUP/).
- **Step 3A:** stopped. The flattened reference was insufficient for faithful raster extraction.
- **Step 3B:** produced vector reconstructions in `design/branding/launcher/source/` (master and monochrome SVGs).
- **Owner approval:** the owner approved both SVGs as the final icon artwork.

**Step 3C: launcher icon implementation — implemented, verified and owner accepted (2026-11-01).**

- **Resources:** the provisional resources were replaced with resources rendered directly from the approved SVGs:
  - Legacy square and round PNGs.
  - 108 dp adaptive foreground and monochrome PNGs.
  - A radial-gradient navy vector background.
  - The provisional glow PNG was removed.
- **Masking:** opaque artwork reaches 32.15 dp, inside the 33 dp safe zone. The glowing frame is preserved in the main legacy `ic_launcher.png` icons and the approved SVG masters. It is omitted from the adaptive layers to avoid a second border inside the launcher mask. By owner decision, it is also omitted from the legacy `ic_launcher_round.png` icons, which are the originally verified frameless circles restored byte-for-byte.
- **Unchanged:** app ID, name, namespace, version, Kotlin, manifest, Gradle, splash configuration, themes and signing.
- **Build and lint:** final debug and unsigned release builds passed. Lint reported 57 warnings and 0 errors in both debug and release, with no icon findings. The 2 `MonochromeLauncherIcon` warnings were resolved, and the 5 interim `IconLauncherShape` warnings were eliminated.
- **Resource checks:** aapt2 confirmed that normal and round launcher resources resolve.
- **Visual checks:**
  - The local mask, size and monochrome renders were inspected.
  - On the disposable API 37 AVD, the launcher icon was inspected under the circle mask after the test-signed synthetic install was replaced with the debug build.
  - Themed-icon, API 31 and physical-device rendering were NOT_RUN and are deferred to Milestone 1.14.
- **Report:** [M1.13 Step 3C report](M1.13_STEP3C_LAUNCHER_ICON_IMPLEMENTATION.md).

**Step 4B: dependency integrity and release security verification — verified with findings (2026-11-01).** Added reviewed strict SHA-256 dependency verification metadata at [verification-metadata.xml](../../android/gradle/verification-metadata.xml): 425 resolved components / 734 artifact entries; no dependency versions, repositories, wrapper, signing settings, or app behavior changed. Strict verification passed the 187-test JVM suite, debug/release builds and lint, debug instrumentation compilation, and release instrumentation compilation; lint remains 59 warnings / 0 errors. A disposable test-signed release target was installed/launched and verified non-debuggable on fresh isolated API 31 and API 37 AVDs; 58 release-variant instrumentation tests passed on each. OSV queries of 425 resolved component versions found 3 Critical and 21 High advisory matches in build/plugin transitive components, plus 26 Moderate and 2 Low matches. The separate 91-coordinate app/test/compiler-plugin scan had no matches. No dependency remediation was performed. Chooser recipient-completion/task-removal/reboot behavior and direct recipient delete denial remain unverified; six process-kill tests were intentionally not run. See [M1.12 Step 4B report](M1.12_STEP4B_DEPENDENCY_INTEGRITY_AND_RELEASE_TEST_REPORT.md).

The approved Milestone 1.10 interface, A2 protocol, backup compatibility, vCard 3.0 exports, QR sharing, and privacy controls remain protected. Step 3 adds an owner-approved persistence-protocol extension and only required recovery/pending-operation UI. Step 4E authorizes optional name fields, additive AppData schema 3 and the bounded export mapping correction; no dependency or backup container changes. Owner emulator data must not be used or modified. Older APKs may not understand external operation intents or schema 3; downgrade safety is not claimed.

## Current P0.5 Status — 2026-10-31

**FG-00 STATUS: CLOSED — OWNER ACCEPTED WITH DOCUMENTED LIMITATIONS**

The owner explicitly accepted the bounded FG-00 evidence and limitations for closure; see [approval record section 21](../P0.5%20Technical%20Architecture%20%26%20Feasibility/P0.5_APPROVAL_RECORD.md#21-explicit-owner-fg-00-closure--2026-10-31) and the [feasibility evidence register](../P0.5%20Technical%20Architecture%20%26%20Feasibility/P0.5_FEASIBILITY_EVIDENCE_REGISTER.md).

- [x] SDK Platform 36 verified; isolated AGP 8.10.1 / Gradle 8.11.1 / Kotlin and Compose compiler 2.2.20 / JBR 21.0.11 verified.
- [x] Dependency resolution and debug compilation passed; one JVM unit test passed; lint passed with zero errors and five warnings.
- [x] API 31 harness install, Activity launch and Compose rendering confirmed by UI hierarchy; authorized cleanup succeeded.
- [ ] API 31 extended crash/ANR and responsiveness checks: incomplete, expressly retained.
- [ ] API 37 application runtime: not tested, expressly retained.
- [ ] SDK parser, native-symbol and lint warnings: documented, not resolved.
- [ ] Production dependencies, release behavior and feature compatibility: not verified by the synthetic harness.

The unchecked items remain limitations, not passing tests or automatic next work. FG-01 through FG-07 remain **OPEN / NOT EXECUTED**. P0.5 remains **PARTIALLY APPROVED / INCOMPLETE**. Production application development/architecture approval and P0.6/P0.7 advancement are not authorized. No additional build, emulator test, SDK installation or investigation occurred for this documentation update.

## Historical Development Baseline — 2026-10-29

The setup-first record below is preserved as history. Its "no build" statements describe that earlier baseline, not the later isolated harness evidence; its next-action instructions do not authorize current execution.

**Last baseline:** 2026-10-29  
**Current phase:** Phase 1 — Environment setup (not started)  
**Build status:** No Android project compiled or APK verified yet.

## Completed planning
- [x] Product concept established: premium native Android digital business card.
- [x] Kotlin/Jetpack Compose/Material 3 selected as direction.
- [x] Conceptual ten-screen dark-themed mockup generated.
- [x] NFC explicitly removed from scope.
- [x] Project name chosen: VCard Native.
- [x] Project Instructions drafted for ChatGPT Project.
- [x] Development source documentation baseline created.

## Awaiting validation
- [ ] Confirm project-only memory setting and upload source files.
- [ ] Install and launch Android Studio.
- [ ] Install SDK and confirm toolchain versions.
- [ ] Create starter project and complete Gradle sync.
- [ ] Enable Pixel USB debugging and run starter app.
- [ ] Finalize application ID, minimum SDK, and hybrid sharing choice.

## Environment record (fill after setup)
| Item | Verified value |
|---|---|
| Android Studio | Not yet recorded |
| Android Gradle Plugin | Not yet recorded |
| Gradle | Not yet recorded |
| Kotlin | Not yet recorded |
| Compile / target / min SDK | Not yet recorded |
| Pixel / Android version | Not yet recorded |
| First successful device run | Not yet completed |

## Next action
Open `02_ANDROID_STUDIO_SETUP.md` and complete Step 1 only; report result before moving on.

## Issues and test log
No build/test results yet. Record each issue with date, error, attempted fix, outcome, and evidence.
