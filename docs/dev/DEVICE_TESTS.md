# On-device tests (Maestro)

The real app is installed on an Android emulator (or a phone) and driven screen by screen with
[Maestro](https://maestro.dev). Every flow takes screenshots at each major step; the runner records logcat, the
crash buffer and dropbox crash/ANR entries per flow, and writes `summary.md` with pass/fail and extracted stack traces.

## What is covered

| Flow (`.maestro/flows/`) | Walks through |
|---|---|
| `01_onboarding` | Clean install → language, value pages (and Back), privacy toggles, theme, creator focus, Get started; onboarding not shown again |
| `02_home` | Hero, quick actions, scrolling; Account, Templates, Drafts, Cloud open and return |
| `03_scripts` | New script → title/body, markup tools, undo, preview, overflow menu, autosave; library, search (hit + empty), long-press actions → duplicate, favorites filter, sort, reopen + edit |
| `04_teleprompter` | Open from the script editor, play/scroll, pause, speed and text-size steppers, mirror, restart, settings screen (preset), resume prompt |
| `05_camera_studio` | Preview, flip camera ×2, lens carousel (≈12 lenses incl. Pro ones), swipe live filters, Filters & background sheet (filters, portrait blur, remove all), Beauty panel (tabs, slider, looks → paywall for free users), settings/audio/pro/grid/prompter, video↔audio mode, ~5 s recording → Open in Editor |
| `06_audio_only` | Audio-only studio, input sheet, ~4 s recording, “Audio saved”, record another, close |
| `07_editor` | Fresh take → editor: play, select clip, split, delete (trim), undo, filter, text overlay, export and wait for “Your video is ready” |
| `08_ai_studio` | AI Studio shows “Setup needed” honestly without a gateway; a tool with a topic → Generate fails gracefully |
| `09_projects` | Tabs, sort, actions sheet → duplicate → open in editor, search, Templates |
| `10_account_settings_about` | Account hub, Settings (runtime language switch fa↔en, theme, reduce motion, all sections), Cloud & sync, Privacy, Terms, About → version ×7 → tester dialog (wrong code rejected), Licenses |
| `11_paywall` | Paywall with no store (emulator): honest unavailable state, retry, plan chips, restore, close; paywall from Account → Subscription |
| `12_camera_permissions` | Permissions revoked → in-app rationale → system dialog → grant → live preview |

Selectors are the app's own strings as regexes matching **both** Persian and English (`'Scripts|متن.ها'`, `.` stands
for the ZWNJ), so the same flows run in both languages; `-e APP_LANG=en` picks English during onboarding (default
Persian). Where text is not enough, screens use `Modifier.testTag("…")` (exposed as resource ids by
`testTagsAsResourceId` in `MainActivity`): `script_title`, `script_body`, `scripts_search`, `ai_input`,
`editor_text_input` — Compose text-field placeholders are not visible to UI automation, so fields need a tag.

Besides the flows, every emulator job installs and runs the instrumented GL tests of `engine:beauty`
(`ShaderProgramsTest` compiles every shader in GLES3/GLES2 modes, `EffectsPipelineTest` runs the effects processor
off-screen) — results in the "Instrumented tests" section of `summary.md`.

## Results from CI (no login needed)

Workflow: `.github/workflows/device-tests.yml` — on pushes to `claude/ravango-video-production-app-4m2l7j` and
`wip/device-tests` that touch app code or flows, and manually (Actions → Device tests → Run workflow; optional
inputs: `locales`, `flows`). Matrix (parallel jobs): API 34 x86_64 in Persian, API 34 in English, API 28 x86_64 in Persian; emulated front and back cameras.

- Summary: `curl -L https://github.com/BehnamJalaliCo/RavanGo-Android/releases/download/device-tests/summary.md`
- Everything (screenshots as JPEG, logcat, Maestro logs, JUnit): `curl -L -o device-tests.zip https://github.com/BehnamJalaliCo/RavanGo-Android/releases/download/device-tests/device-tests.zip`
- Full-size PNGs and screen recordings of failed flows: the `device-results-api*` workflow artifacts.

The job fails (after uploading) when any flow fails or the app crashed / ANR'd. Layout of each flow folder
(`<locale>/<flow>/`): `maestro.log`, `report.xml`, `maestro/…png` (step screenshots), `debug/` (Maestro's own failure
screenshot + view hierarchy), `screenshots/` (one per step), `failure.png`, `logcat.txt`, `crash_buffer.txt`,
`dropbox_*.txt`, `app-crash-reports/` (the app's own reports from `files/diagnostics/reports`), `app-events.log`
(`files/diagnostics/events.log`), `video/` (failed flows only). `summary.md` also lists the camera engine lines of
each flow (encoder/recording start, take stops, session errors, finalizer/muxer failures).

## Running locally

Requirements: Android SDK platform-tools (`adb`), Java 17, Maestro (`curl -fsSL https://get.maestro.mobile.dev | bash`),
and an emulator or a phone with USB debugging.

```bash
# Emulator (x86_64): build with x86_64 natives (debug only; release APKs stay ARM-only)
./gradlew :app:assembleDebug -Pravango.emulatorAbi=true
# Phone (ARM): a normal debug build is enough
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

maestro test .maestro/                                   # every flow, Persian
maestro test -e APP_LANG=en .maestro/                    # every flow, English
maestro test .maestro/flows/05_camera_studio.yaml        # one flow
maestro studio                                           # inspect the screen / build selectors interactively

# The CI runner (per-flow logcat, crash/ANR detection, summary.md):
scripts/device-tests/run-suite.sh app/build/outputs/apk/debug/app-debug.apk build/device-tests "fa en"
# … plus the instrumented GL tests:
./gradlew :engine:beauty:assembleDebugAndroidTest
TEST_APKS=engine/beauty/build/outputs/apk/androidTest/debug/beauty-debug-androidTest.apk \
  scripts/device-tests/run-suite.sh app/build/outputs/apk/debug/app-debug.apk build/device-tests fa
```

Emulator suggestion (same as CI): `-gpu swiftshader_indirect -camera-back emulated -camera-front emulated`, Pixel 5,
4 GB RAM. `OWNER_CODE_CONFIGURED=true` makes `10_account_settings_about` assert the tester dialog (only builds with
`RAVANGO_OWNER_CODE_SHA256` show it); the real owner code is never used by the tests.

## Writing flows

- Take strings from `values/strings.xml` + `values-fa/strings.xml`; use `'English|فارسی'`, wrap in `.*….*` when the
  text is part of a merged node (list items with subtitles), and replace ZWNJ with `.`.
- Start with `- runFlow: ../subflows/launch.yaml` (launch, permissions, onboarding, crash-report prompt) so a flow
  runs alone; leave with `../subflows/go_home.yaml`. Camera: `open_camera.yaml`, `record_take.yaml` (fails unless the
  take ends in the saved-take sheet or an explicit message), `camera_recover.yaml` (error card → Retry).
- `optional: true` only for steps that depend on screen size or state (e.g. lenses beyond the visible carousel).
- Maestro types ASCII only on Android; use English text in `inputText`.
- Validate: `maestro check-syntax <flow.yaml>`.

## Known gaps

- The emulated cameras show a synthetic scene with no face: face lenses, beauty and makeup run through the GPU
  pipeline (crashes would be caught) but their visual result cannot be verified; screenshots show “face not detected”.
- Software GL (SwiftShader) and emulator encoders are slower than phones: timing-sensitive behaviour (frame budget,
  thermal, ANR thresholds) differs from real devices.
- No Play Store on `google_apis` images: purchases can only be tested up to the “unavailable” state.
- Trimming by dragging clip handles is not automated (split + delete is used instead); gallery pickers (import) and
  share sheets are system UI and are not driven.
