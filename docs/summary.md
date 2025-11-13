### Project Purpose

- Kprofiles gives Compose Multiplatform projects a predictable way to merge shared assets with platform/build-type/profile overlays so a single Res.* accessor reflects brand/theme
  differences across Android, iOS, and Desktop builds (README.md:3-44).
- Overlays follow a strict Shared → Platform → BuildType → Profiles order, copy only Android-compatible folders (values*, drawable*, font, files), and obey simple “last wins” semantics
  so you can layer arbitrary stacks like theme-blue,brand-alpha (README.md:41-120).
- Active profiles are resolved once per build with precedence CLI > env > DSL defaults, and the plugin logs when it falls back to shared-only stacks, helping teams reason about
  reproducibility (README.md:83-95).
- The MVP checklist shows the repository already went through foundation, overlay pipeline, Compose wiring, diagnostics, testing, config generation, and sample-app integration phases
  (docs/tasks.md:3-56).

### Plugin Design

- KprofilesPlugin activates after the KMP plugin, resolves the profile stack, enforces the commonMain owner source set, and requires a single platform family/build-type per invocation
  (detected or supplied via -Pkprofiles.family/kprofiles.buildType) to avoid duplicate Compose resources (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPlugin.kt:35-95, plugin/
  src/main/kotlin/dev/goquick/kprofiles/PlatformFamilies.kt:26-133).
- The KprofilesExtension exposes defaults for shared directories, overlay patterns, allowed top-level roots, collision policy, strict Android name checking, diagnostics logging,
  `.env.local` fallback toggles, and generated-config options so consumers can tweak behavior declaratively (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesExtension.kt:32-138).
- KprofilesPrepareSharedTask copies only approved roots from src/commonMain/composeResources, filters hidden/symlinked entries, and logs ignored folders before any overlays run
  (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPrepareSharedTask.kt:35-113).
- KprofilesOverlayTask then layers platform/build-type/profile directories in order, warns about missing/ignored folders, enforces the selected collision policy, and copies files
  without bringing in dotfiles or __MACOSX debris (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesOverlayTask.kt:41-200).
- Compose resource tasks (convertXmlValueResourcesFor*, copyNonXmlValueResourcesFor*, generateResourceAccessorsFor*, etc.) are wired to depend on the overlay output and have their
  originalResourcesDir redirected so Compose always sees the merged tree (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPlugin.kt:169-245).
- Dedicated diagnostics tasks (kprofilesPrintEffective, kprofilesVerify, kprofilesDiag) capture the context vector, scan overlays for invalid folders/names, and list every
  prepared file with its origin for easier debugging (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPlugin.kt:360-429, plugin/src/main/kotlin/dev/goquick/kprofiles/
  KprofilesDiagnosticsTasks.kt:35-215).

### Config & CLI

- The README documents flat YAML overlays at src/commonMain/config/app.yaml plus platform/build-type/profile counterparts, allowing only scalar values so merged configs stay trivially
  serializable (README.md:204-259).
- KprofilesMergeConfigTask reads ordered YAML inputs, rejects unsupported types or duplicate keys, and writes a merged JSON snapshot, while KprofilesGenerateConfigTask turns that into
  a Kotlin object using const vals whenever the scalar is finite. `[=env]` lookups can now fall back to `<root>/.env.local` (opt-out via `envLocalFallback`) before failing, making local
  secrets management easier without affecting CI (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesConfigTasks.kt:47-289, plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPlugin.kt:35-520).
- registerConfigGeneration wires merge → generate → Kotlin compilation, registers the generated source directory with the target source set, and forces Kotlin/JVM/Native compile tasks
  to depend on the config output so AppConfig is always up to date (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesPlugin.kt:248-357).
- KprofilesConfigPrintTask plus the documented CLI commands (kprofilesPrintEffective, kprofilesVerify, kprofilesDiag, kprofilesConfigPrint) make it easy to inspect the active stack,
  overlays, and merged key/value table without building the entire project (plugin/src/main/kotlin/dev/goquick/kprofiles/KprofilesConfigTasks.kt:288-333, README.md:323-335).

### Quality & Tests

- ProfileResolverTest proves CLI/env precedence, DSL fallbacks, and name validation logic so profile stacks remain deterministic (plugin/src/test/kotlin/dev/goquick/kprofiles/
  ProfileResolverTest.kt:10-76).
- ProfilesCopyUtilsTest covers folder matching, qualifier handling, and relative-path reporting to ensure only sanctioned directories participate in merges (plugin/src/test/kotlin/dev/
  goquick/kprofiles/ProfilesCopyUtilsTest.kt:11-62).
- KprofilesConfigTasksTest exercises YAML merging edge cases (big numbers, duplicate keys) and confirms Kotlin generation toggles like preferConstScalars behave as expected (plugin/
  src/test/kotlin/dev/goquick/kprofiles/KprofilesConfigTasksTest.kt:23-165).
- KprofilesPluginFunctionalTest runs Gradle TestKit scenarios for overlay order, strict Android naming, collision policies, ignored folders, diagnostics, and platform/build-type
  stacking, demonstrating the plugin’s end-to-end behavior (plugin/src/test/kotlin/dev/goquick/kprofiles/KprofilesPluginFunctionalTest.kt:20-110).

### Sample App

- sample-app/composeApp applies Kotlin MPP, Android application, Compose, and the Kprofiles plugin, enabling generated config output targeted at
  dev.goquick.kprofiles.sampleapp.config.AppConfig (sample-app/composeApp/build.gradle.kts:5-59).
- The shared Compose UI reads Res.* strings/drawables plus generated AppConfig constants to render the current profile, API base URL, retry counts, feature flags, platform label, and
  build type, making overlay effects immediately visible on every target (sample-app/composeApp/src/commonMain/kotlin/dev/goquick/kprofiles/sampleapp/App.kt:19-125).
- Baseline resources live in src/commonMain/composeResources/values, defining shared labels such as platform_label and profile_name before any overlays are applied (sample-app/
  composeApp/src/commonMain/composeResources/values/strings_platform.xml:1-4, sample-app/composeApp/src/commonMain/composeResources/values/strings_profile.xml:1-4).
- Profile overlays override both copy and imagery—for example, theme-blue tweaks messaging while brand-alpha/bravo change CTA text and hints—showcasing last-wins layering (sample-app/
  composeApp/overlays/profile/theme-blue/composeResources/values/strings_theme.xml:1-5, sample-app/composeApp/overlays/profile/brand-alpha/composeResources/values/strings_brand.xml:1-
  4, sample-app/composeApp/overlays/profile/brand-bravo/composeResources/values/strings_brand.xml:1-5).
- Platform and build-type overlays inject context-specific strings and config tweaks—e.g., Android-specific platform labels plus debug/release build strings and YAML overrides
  for retry/timeout behavior (sample-app/composeApp/overlays/platform/android/composeResources/values/strings_platform.xml:1-4, sample-app/composeApp/overlays/buildType/debug/
  composeResources/values/strings_buildtype.xml:1-4, sample-app/composeApp/overlays/buildType/debug/config/app.yaml:1-2).
- YAML config layers illustrate how shared defaults (apiBaseUrl, retryCount, featureX) change per theme or brand, and the generated constants are consumed across all entry points—
  Android MainActivity, Desktop main(), and iOS MainViewController all just call App() so the UI stays consistent per stack (sample-app/composeApp/src/commonMain/config/app.yaml:1-5,
  sample-app/composeApp/overlays/profile/theme-blue/config/app.yaml:1-3, sample-app/composeApp/overlays/profile/brand-bravo/config/app.yaml:1-3, sample-app/composeApp/src/androidMain/
  kotlin/dev/goquick/kprofiles/sampleapp/MainActivity.kt:10-24, sample-app/composeApp/src/jvmMain/kotlin/dev/goquick/kprofiles/sampleapp/main.kt:1-12, sample-app/composeApp/src/
  iosMain/kotlin/dev/goquick/kprofiles/sampleapp/MainViewController.kt:1-5).
- The sample config reads `SAMPLE_APP_SECRET` via `[=env]` (backed by a demo `.env.local`) and
  `sampleApp.customerId` via `[=prop]` defined in `sample-app/gradle.properties`, illustrating both
  secret delivery paths without forcing contributors to set local Gradle properties.
