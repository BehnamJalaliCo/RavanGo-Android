# Work-in-progress handoff

The foundation lives on `claude/ravango-video-production-app-4m2l7j`. Feature work was split across parallel
engineers; their latest snapshots are pushed to these branches (each branched from commit `b1e8847`):

| Branch | Modules | Remaining at snapshot time |
|---|---|---|
| `wip/teleprompter-scripts` | engine/teleprompter, feature/teleprompter, feature/scripts | feature/scripts (library, import, editor, AI assist) |
| `wip/camera` | engine/camera, feature/camera | Camera Studio UI (feature/camera) |
| `wip/audio` | engine/audio, core/media/dsp | finishing + tests |
| `wip/beauty` | engine/beauty, feature/beauty | BeautyPanel + presets screen |
| `wip/editor` | engine/editor, feature/editor | editor UI, export screen |
| `wip/ai` | engine/ai, feature/ai, backend/functions/ai-gateway | AI Studio UI, gateway |
| `wip/account-cloud-billing` | platform/*, feature/account, feature/paywall, backend/sql | billing, account/settings/paywall UI |
| `wip/home-onboarding-projects` | feature/home, feature/onboarding, feature/projects | polish |

Rules every contributor follows: `docs/dev/AGENT_BRIEF.md`. Architecture: `docs/ARCHITECTURE.md`.

## Resuming
1. For each branch: `git fetch origin wip/<name>` and continue the listed remaining scope in a worktree.
2. Merge each finished branch into the main branch (modules are disjoint; expect small conflicts only in
   `gradle/libs.versions.toml`).
3. Build `:app:assembleDebug`, fix the Hilt graph (bindings come from engine/platform modules), then push —
   CI builds the APKs and publishes the `latest-build` release.
