# RavanGo — Architecture

RavanGo (روان‌گو) is a production video-creation app: **Teleprompter + Pro Camera + Beauty/Makeup + Editor + AI**.
This document is the map of the codebase. Read it before adding a module or crossing a module boundary.

## Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.2 (JVM 17) | Coroutines/Flow everywhere, K2 compiler |
| UI | Jetpack Compose + custom design system (`core:designsystem`) | Pastel/glass premium look, not stock Material |
| DI | Hilt (KSP) | Compile-time graph, first-class Android support |
| Persistence | Room (local-first) + DataStore (settings) + Keystore-backed `SecureStore` | Works fully offline |
| Camera | **Camera2** directly | Capability-accurate UI (ISO, shutter, focus distance, WB, fps ranges, stabilization, HDR) |
| Real-time GPU | EGL14 + GLES 2/3 (`engine:render`) | One GPU pipeline renders preview *and* encoder input → effects identical in preview and file |
| Encoding | MediaCodec (surface input) + segmented MediaMuxer | Hardware encoding, crash-safe segments |
| Face tracking | MediaPipe Face Landmarker (478 3D landmarks, 2 faces, GPU delegate) + canonical face mesh with UVs | Snapchat-style architecture: makeup as UV-space face-mask textures, mesh-warp reshape; on-device, no network |
| Editor / export | Media3 Transformer + Effect + CompositionPlayer | Hardware-accelerated composition, same graph for preview and export |
| AI (text) | Anthropic Claude via official Java SDK, through the RavanGo gateway | Keys never ship in the APK |
| Speech-to-text | Gateway (Whisper-compatible) / Android on-device recognizer | Word timestamps for captions |
| Auth / Sync / Storage | Supabase (GoTrue + PostgREST + Storage) over OkHttp | One backend, row-level security, SQL schema in `backend/` |
| Billing | Google Play Billing 8 behind `BillingProvider` | Swappable for Cafe Bazaar / Myket |
| Background | WorkManager (+ Hilt workers) | Sync, uploads, long exports |

## Module graph

```
app ──► feature:* ──► engine:* / platform:* ──► core:*
```

* **core** — foundations with no product knowledge.
  * `core:model` (pure Kotlin): every domain model + cross-cutting service contracts (`AuthSessionProvider`, `SyncController`, `EntitlementProvider`).
  * `core:common`: dispatchers, `Outcome`, logging, device tier, thermal & storage monitors, crash guard, `AppConfig`, `StartupTask`.
  * `core:designsystem`: theme, colors, typography (Vazirmatn/Sahel/Samim), components, haptics, motion.
  * `core:ui`: permissions (just-in-time + rationale), RTL helpers, hardware key routing (volume keys / Bluetooth remotes), shared strings.
  * `core:navigation`: type-safe routes for the whole app.
  * `core:database` (Room), `core:datastore` (settings + SecureStore), `core:data` (offline-first repositories, built-in presets/templates, `LocalChangeBus`).
  * `core:media`: media probing, gallery publishing, PCM decoding, DSP (`VoiceProcessor`, `LevelMeter`, `SilenceDetector`).
* **engine** — heavy, UI-less subsystems (the teleprompter engine also ships its renderer composable).
  * `engine:render`: EGL/GL utilities and the `GlFrameProcessor` plug-in contract.
  * `engine:camera`: Camera2 capabilities, session control, GL pipeline, encoders, segmented muxer, recording recovery.
  * `engine:audio`: input routing (built-in / wired / USB / Bluetooth), capture, DSP chain, metering, monitoring, audio-only recording.
  * `engine:beauty`: face tracking + beauty/makeup GL passes, adaptive quality. Plugs into the camera as a `GlFrameProcessor`.
  * `engine:teleprompter`: markup parser, time-based scroll engine, `TeleprompterView` renderer.
  * `engine:editor`: document → Media3 composition, custom GL effects, preview, export, reverse, freeze frame.
  * `engine:ai`: provider-agnostic text AI, speech-to-text, subtitle building, video intelligence (silences, cuts, highlights, shorts, auto-edit).
* **platform** — integrations with external backends: `platform:auth`, `platform:cloud`, `platform:billing`.
* **feature** — screens + view models. Features never depend on each other (one exception: `feature:camera` embeds the `feature:beauty` panel). Cross-feature navigation uses routes from `core:navigation`.

## Key design decisions

### Recording is sacred
The camera GL renderer never blocks on effects: beauty reports its frame cost and the camera's
`FrameBudget` lowers beauty quality (FULL → BALANCED → LIGHT → MINIMAL) before a frame is ever dropped.
Thermal status (`ThermalMonitor`) and device tier (`DeviceProfiler`) cap quality up front. Recordings are written in
segments; if the process dies, completed segments are recovered on next launch (`CrashGuard` sections).

### One pipeline, identical output
`Camera2 → SurfaceTexture (OES) → GL: normalize/rotate/crop to aspect → [GlFrameProcessor…] → preview surface + encoder surface`.
Aspect-ratio crops (9:16, 1:1, 4:5…), front-camera mirroring and beauty are all applied on the GPU once.

### Local-first data & sync
Every syncable row has `updated_at`, `sync_status` (`SYNCED|PENDING|CONFLICT`) and a tombstone `deleted_at`.
Repositories write locally and publish to `LocalChangeBus`; `platform:cloud` debounces and pushes/pulls with
last-writer-wins. With no account or no network the app is fully functional.

### Swappable services
Every external dependency sits behind an interface: `AiTextService`, `SpeechToTextService`, `EyeContactService`,
`BillingProvider`, auth providers, cloud backend. When a service is not configured, the UI states exactly which
service is required (never a fake button).

### Monetization is data
`Entitlements` are computed from purchases + a remote-overridable `MonetizationConfig` (plan → features/limits).
Changing what is free vs. Pro requires no code change in features — they only ask `EntitlementProvider.has(feature)`.

## Localization & RTL
Persian is the default app language (selectable in onboarding/settings, per-app locale API with AppCompat backport).
All layouts use start/end, `AutoMirrored` icons and `LocalLayoutDirection`. Content (scripts, subtitles) has its own
direction (`ContentDirection.AUTO` detects the first strong character), independent of the UI language.

## Configuration
`secrets.defaults.properties` (committed, empty) → `secrets.properties` (git-ignored) → env vars `RAVANGO_*`.
See `docs/SERVICES.md` for which external services each feature needs.
