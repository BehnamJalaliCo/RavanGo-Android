# COMMON BRIEF (applies to every RavanGo agent)

You are one of several engineers building **RavanGo (روان‌گو)**, a production-grade Android video-creation app
(Teleprompter + Pro Camera + Beauty/Makeup + Editor + AI). The foundation is already committed. You work in your own
git worktree (your current working directory). Other agents work in parallel on other modules.

## Read first
- `docs/ARCHITECTURE.md` (module map, principles).
- `core/model/**` (all domain models + service contracts in `core/model/.../service/Services.kt`).
- `core/designsystem/**` (theme + components) and `core/ui/**` (permissions, RTL helpers, HardwareKeyHandler, shared strings).
- The contract files for the modules you touch or consume (listed in your task).

## Hard rules
1. **Only modify files inside the modules assigned to you.** Exceptions: you may ADD new dependencies to
   `gradle/libs.versions.toml` (append in a block commented with your area) and to your own modules' build files.
   Never edit `settings.gradle.kts`, `app/**`, `core/**` (except where your task explicitly allows), or other agents' modules.
2. **Contracts**: files marked `CONTRACT` define APIs other agents code against in parallel. When you implement a
   contract, keep every existing public signature. You may add new members/types (additive only).
3. **Production quality, no fakes.** No `TODO()`, no placeholder screens, no buttons that do nothing. If something
   needs an external service/SDK, implement the real integration layer and show a clear, localized
   "requires <service>" state. If a capability is not supported by the device, detect it and hide/disable the option
   with an explanation. Handle errors, cancellation, lifecycle (config changes, background), and permissions
   (request just-in-time with a rationale using `core:ui` `rememberPermissionRequester` / `PermissionRationaleCard`).
4. **Architecture**: MVVM/UDF. `@HiltViewModel` view models exposing `StateFlow<UiState>`; screens get them with
   `hiltViewModel()`. Suspend/Flow APIs, `Dispatchers` via the qualifiers in `core:common` (`@IoDispatcher` etc.).
   Use `Outcome`/`ErrorKind` from `core:common` for expected failures and `ErrorKind.message()` from `core:ui` for text.
   Log with `RgLog`. Do not bind (Hilt `@Binds/@Provides`) interfaces owned by other agents — just inject them.
5. **Navigation**: each feature exposes `fun NavGraphBuilder.<feature>Destinations(navController: NavHostController)`
   in its `Navigation.kt` (a stub already exists — replace it, keep the function name/signature). Routes are the
   `@Serializable` types in `core/navigation/.../Routes.kt` (read-only for you). Navigate anywhere with
   `navController.navigate(SomeRoute(...))`; read args with `backStackEntry.toRoute<SomeRoute>()` or
   `SavedStateHandle.toRoute<SomeRoute>()` in the ViewModel.
6. **Localization**: all user-facing text in string resources of YOUR module: English in `src/main/res/values/strings.xml`,
   **natural, fluent Persian** in `src/main/res/values-fa/strings.xml` (Persian is the primary language; use proper
   Persian typography: ZWNJ «‌» where needed, Persian punctuation «،» «؛» «؟»). Prefix string names with your module
   (e.g. `prompter_…`) to avoid merge clashes. Numbers shown to users go through `localizeDigits()` / formatters in
   `core:common` (`formatDuration`, `formatBytes`).
7. **RTL/LTR**: layouts must work in both. Use start/end paddings, `Icons.AutoMirrored.*` for directional icons,
   `LocalLayoutDirection` for gesture math. Content text (scripts, subtitles) uses `ContentDirection` +
   `resolve()`/`detectDirection()` from `core:ui`.
8. **Design**: premium, minimal, pastel, soft — NOT stock Material. Use `core:designsystem`: `RgScreen`/`RgTopBar`,
   `GradientBackground`, `GlassSurface`, `RgCard`, `RgPrimaryButton`/`RgSecondaryButton`/`RgOutlineButton`/`RgTextButton`,
   `RgIconButton` (use `glass = true` over video), `RgSlider`/`RgLabeledSlider`, `RgSwitch`, `RgSegmentedControl`,
   `RgChip`/`RgChipRow`, `ColorSwatchRow`, `RgTextField`, `RgBottomSheet`, `RgConfirmDialog`, `RgListItem`, `RgGroup`,
   `SectionHeader`, `EmptyState`, `ShimmerBox`, `LoadingState`, `RgProgressBar`, `ProBadge`, `RgTag`,
   `Modifier.pressable{}` (spring press + haptic), `rememberHaptics()` + `HapticEvent`, `RgTheme.colors`,
   `Spacing`, `Radius`, `Motion`. Camera/teleprompter/editor surfaces use `StudioTheme` (always dark). Micro-interactions:
   animate state changes (`animate*AsState`, `AnimatedVisibility`, `AnimatedContent`), haptics on meaningful actions.
   You may add module-local components when needed. Respect `RgTheme.reduceMotion`.
9. **Monetization gating**: inject `EntitlementProvider` (core:model service) and check `has(ProFeature.X)`; when a
   gated action is attempted, navigate to `PaywallRoute(source = "<where>", feature = ProFeature.X.name)` or call a
   provided `onRequirePro`. Show `ProBadge` next to gated options. Never hard-code plan logic.
10. **Performance**: avoid recomposition in hot paths (read fast-changing state in draw/layout lambdas, e.g.
    `Modifier.graphicsLayer { }`/`drawBehind`/`offset { }`), stable lambdas, `key`s in lists, no main-thread I/O.
11. **Tests**: add JVM unit tests (`src/test`) for your pure logic (parsers, math, state reducers). Keep them fast.

## Environment & build
- Android SDK: always `export ANDROID_HOME=/opt/android-sdk` before Gradle (local.properties is not in git).
- Maven Central is mirrored via `~/.gradle/init.d/mirror.gradle` (already set up — do not change).
- Several agents build concurrently on a 4-core/15 GB machine. ALWAYS build like this (bounded memory, no daemon):
  `./gradlew --no-daemon --console=plain -q -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process --max-workers=2 :<module>:compileDebugKotlin`
  and `:<module>:testDebugUnitTest` for tests. Build only your modules (Gradle builds their dependencies).
  NEVER run `./gradlew --stop`, `pkill java` or anything that kills other agents' processes. Don't build `:app`
  (other bindings are still being written in parallel, so the app graph is incomplete).
- Inspect library APIs you are unsure about with `javap`/`unzip -l` on jars under `~/.gradle/caches/modules-2/files-2.1/`
  (AARs contain `classes.jar`). Do not guess APIs — verify, then compile. Iterate until your modules compile warning-light
  and tests pass.

## Finish
- Commit your work in your worktree with a clear message ending with these two lines exactly:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_012tqzzSUycndJq4zWLrbbpP`
  (use `git -c user.name="Claude" -c user.email="noreply@anthropic.com" commit ...`). Do NOT push.
- Final report (concise): what you built (by file/area), any contract additions, dependencies added, services
  required, known limitations, the branch name and commit hash.
