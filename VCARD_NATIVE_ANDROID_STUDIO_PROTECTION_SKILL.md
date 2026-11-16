# VCard Native — Android Studio Project Protection Skill

**Version:** 1.0  
**Purpose:** Protect the working native Android project, its Android Studio synchronization, build configuration, existing features, and user data during Codex-assisted development and troubleshooting.

## When to apply

Apply these rules to **every** Codex task involving VCard Native: investigation, feature implementation, UI changes, refactoring, testing, dependency maintenance, build repair, and documentation updates. Treat this document as a required project guardrail, not a substitute for the approved requirements or direct inspection of source code.

**Project root:** `C:\Users\Ian12724\Downloads\PixelApp\VCard`  
**Android project:** `C:\Users\Ian12724\Downloads\PixelApp\VCard\android`

## Authority and approved baseline

1. Read relevant approved project documents before recommending changes. In particular, consult `09_V1.1_REQUIREMENTS_APPROVED.md`, `07_DECISION_LOG.md`, and architecture/design documentation as applicable.
2. Preserve the released V1.0.0 behavior while implementing approved V1.1 features. Do not restore the V1.0.0 handoff over active V1.1 source.
3. Preserve established package names, routes, APIs, data formats, and user data unless a change is explicitly approved.
4. A successful CLI build does **not** prove Android Studio Sync, emulator launch, navigation, or device behavior works.
5. A ZIP archive or old report is evidence of its captured state, not proof of the current Windows workspace state.

## Mandatory two-stage change process

### Stage A — Read-only investigation

- Inspect the actual current files, relevant requirements, call sites, and the latest error logs/reports.
- Identify the specific root cause with file paths and line numbers where possible.
- Distinguish **observed**, **inferred**, and **unverified** findings.
- State the smallest proposed correction, exact files to be changed, regression risks, and verification steps.
- **Do not modify files, run auto-fix commands, delete caches, or regenerate project configuration during investigation.**
- Stop and request user review/approval of the investigation report.

### Stage B — Approved implementation

- Implement only the specifically approved correction.
- Before editing, inventory intended files and make a recoverable backup of any build-critical configuration being changed. Never overwrite the sole backup.
- Keep diffs minimal; preserve unrelated behavior and formatting.
- If findings change or the repair needs additional files/versions/configuration, **stop and request approval** rather than expanding scope.
- After changes, report exact file diffs, test results, and remaining unverified checks.

## Protected build and IDE files

**Do not change these as part of ordinary UI or feature work:**

- `android/settings.gradle.kts`, root and module `build.gradle.kts`
- `android/gradle.properties`, `android/gradle/libs.versions.toml` if present
- `android/gradle/wrapper/gradle-wrapper.properties` and wrapper files
- `android/gradle/verification-metadata.xml`
- `android/gradle/config.properties`, `android/.gradle/config.properties` if present
- `.idea/`, `local.properties`, SDK/JDK paths, run configurations
- AndroidManifest, application ID, signing configuration, packaging configuration, SDK targets, and dependency versions, except when the approved task specifically requires them

For any build-critical change, provide the reason, precise proposed diff, backup/rollback plan, and user approval **before** implementation. Never upgrade Gradle, Android Gradle Plugin, Kotlin, Java/JDK, Compose, or Android Studio merely to silence a warning.

## Dependency verification safeguards

- Never disable dependency verification or add broad trusted-artifact/wildcard exemptions to make Sync pass.
- Distinguish **missing checksum metadata** from **checksum mismatch**; they require different responses.
- Read the latest dependency-verification HTML report, extract exact coordinates/classifiers/filenames, and check for duplicate rows before editing.
- For missing entries, target only the reported artifacts; avoid transitive source-resolution probes when an exact inventory is required.
- Use Gradle-supported SHA-256 metadata generation or a controlled equivalent, review the generated entries, and preserve every existing checksum.
- Verify artifact identity and bytes; accurately state whether checksums are locally calculated, repository-provided, or independently authenticated. Stop on actual mismatches or evidence of tampering.
- A later Android Studio Sync may expose **additional variant-specific artifacts** (such as `gradle811-sources.jar`); investigate the new report rather than undoing previous valid entries.
- Never delete caches, regenerate the entire verification file, or modify repositories without explicit approval.

## Testing and evidence gates

Record each gate separately, with **PASS / FAIL / NOT RUN**:

1. Static inspection and diff review.
2. Relevant automated tests (including their actual counts, not merely `UP-TO-DATE`).
3. `:app:assembleDebug` using the configured Gradle JDK.
4. **Android Studio Gradle Sync** performed in the IDE.
5. Emulator/device install and app launch.
6. Affected feature's on-device UI/navigation/behavior tests.
7. Regression checks for existing features, including the primary full-profile QR and saved profile data where relevant.

Never report a gate as passed based on another gate. Do not assert emulator or IDE success when only Codex command-line tasks were executed. If an IDE action cannot be performed by the agent, provide one precise manual verification step and wait for the result.

## Recovery and stop conditions

Stop and report immediately if:

- The current project differs materially from the investigated files.
- A checksum mismatch or suspicious artifact appears.
- A requested fix would require changing unapproved build configuration, application ID, signing, data schema, or dependencies.
- An attempted repair produces a new failure whose cause is not yet understood.
- A backup or rollback path cannot be established for a build-critical change.

Do not stack speculative fixes. Preserve the latest known-good state and report the **first actionable failure**. Never instruct the user to delete caches, reinstall the IDE, or recreate the project as an initial troubleshooting step.

## V1.1-specific preservation requirements

- Per approved decision **V11-002**, Quick Share replaces the **Show QR** action on **My Card**, while the existing primary full-profile QR remains accessible through its separate approved route.
- Preserve V1.0.0 contact/export behavior, profile persistence, and QR functionality.
- Do not add new navigation entry points, redesign screens, or silently change approved UX to work around a wiring problem.
- For UI changes, verify **both the visible label and the click destination**, then verify the old primary QR remains accessible.

## Required agent report template

**Investigation:** Current state; approved requirement; evidence (file/line/report); verified root cause; proposed minimal fix; exact intended files; risks; approval needed.

**Implementation (only after approval):** Approval scope; backup/rollback; exact files changed; diff summary; tests/build with PASS/FAIL/NOT RUN; Android Studio Sync status; emulator status; feature regression status; unresolved issues; next single user action.

## How to use with VS Code Codex

At the beginning of every VCard Native task, instruct Codex:

> Read `VCARD_NATIVE_ANDROID_STUDIO_PROTECTION_SKILL.md` first. Follow its two-stage investigation/approval process and protected-file rules. Do not edit anything until I approve the investigation report. Do not claim Android Studio Sync or emulator success without direct evidence.

**Placement recommendation:** Save this file in the project root, next to the `android` directory. Add a reference to it in the project's agent instructions (for example `AGENTS.md`, if used), without overwriting existing instructions.

**Limit:** This skill is a procedural safeguard; it cannot prevent every bug or guarantee a successful Android Studio build. Its effectiveness depends on the agent reading and following it, preserving backups, and completing the verification gates.
