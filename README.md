# Kprofiles

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Maven Central](https://img.shields.io/maven-central/v/dev.goquick.kprofiles/dev.goquick.kprofiles.gradle.plugin?logo=apache-maven&label=Maven%20Central)](https://central.sonatype.com/artifact/dev.goquick.kprofiles/dev.goquick.kprofiles.gradle.plugin)
[![CI](https://img.shields.io/github/actions/workflow/status/mobiletoly/kmp-kprofiles/gradle.yml?branch=main&logo=github&label=CI)](https://github.com/mobiletoly/kmp-kprofiles/actions/workflows/gradle.yml)
[![License](https://img.shields.io/github/license/mobiletoly/kmp-kprofiles?logo=apache&label=License)](LICENSE)


Profile‑ and platform‑aware **Compose Multiplatform** resources **and config** for **Android, iOS,
and Desktop**.  
Kprofiles prepares a single **merged resource tree** for Compose by layering your shared assets with
optional overlays (platform family, build type, and one or more “profiles”) — **last wins**.  
It can also generate **profile‑aware Kotlin config** from YAML overlays (often replacing BuildKonfig
in your workflow).

> **Important:** If a module targets multiple platform families (e.g., Android + iOS + Desktop),
> build **one family per Gradle invocation** (e.g., `-Pkprofiles.family=ios`). Single‑family modules
> don’t need this flag.

---

## Why this exists

Compose Multiplatform stores shared resources in `src/<sourceSet>/composeResources` (e.g.,
`src/commonMain/composeResources`).  
When you need **variants** (brand, theme, customer, region), Android **flavors** only solve this for
Android and don’t exist for `commonMain` or iOS/Desktop. Teams end up copying files or writing
ad‑hoc Gradle tasks.

**Kprofiles** gives you a small, predictable system: keep shared assets where they are, add overlays
in well‑known folders, select overlays per build, and let the plugin hand Compose a single,
conflict‑free tree.

---

## Concepts

- **Shared** — your normal resources under `src/<sourceSet>/composeResources` (e.g.,
  `src/commonMain/composeResources`).
- **Platform family** — high‑level target: `android`, `ios`, or `jvm`. Auto‑detected, or pass
  `-Pkprofiles.family=…` (or `KPROFILES_FAMILY` environment variable).
- **Build type** — when known (`debug` / `release`) for Android variants and some Kotlin/Native
  compilations.
- **Profiles** — your own layers such as `brand-alpha`, `theme-blue`, `dev`, `staging`. Select with
  `-Pkprofiles.profiles=a,b` (or `KPROFILES_PROFILES` environment variable).

**Merge order:** Shared → Platform → BuildType → Profiles (left→right). **Last wins** on
conflicts.  
Overlays can **add new keys** and **override** existing ones — Compose generates `Res.*` against the
merged tree.

---

## Quick start

### 1) Apply plugins

```kotlin
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("dev.goquick.kprofiles") version "0.1.2"
}
```

### 2) Create the tiniest demo overlay

```text
src/commonMain/composeResources/drawable/logo.png                  # shared
overlays/profile/theme-blue/composeResources/drawable/logo.png     # overrides
```

### 3) Build with a profile (and family when needed)

```bash
# Profile via Gradle property (comma-separated)
./gradlew :sample-app:composeApp:generateKprofiles -Pkprofiles.profiles=theme-blue

# If your module targets multiple families (jvm, ios, android, ...), also pass the family:
./gradlew :sample-app:composeApp:generateKprofiles -Pkprofiles.family=jvm -Pkprofiles.profiles=theme-blue

# Or use environment variables
KPROFILES_PROFILES=theme-blue KPROFILES_FAMILY=jvm ./gradlew :composeApp:build

The included sample app demonstrates the `.env.local` fallback: it reads `SAMPLE_APP_SECRET` via
`[=env]` and we ship a `.env.local` with a harmless value so `./gradlew :sample-app:build` works out
of the box. It also defines `sampleApp.customerId` in `sample-app/gradle.properties` and consumes it
with `[=prop]` inside the generated config. Override the environment variable (or edit `.env.local`)
and/or tweak `sample-app/gradle.properties` to simulate different secrets—real projects should
git-ignore `.env.local` and let CI inject values via the environment.
```

That’s it — Compose now generates `Res.*` against the **merged** tree, so your overlayed `logo.png`
is used.

### Profile stack precedence

Kprofiles resolves the active profile stack once per build using this order:

1. Gradle property `-Pkprofiles.profiles`
2. Environment variable `KPROFILES_PROFILES`
3. DSL fallback `kprofiles.defaultProfiles`
4. No profiles (shared-only)

When defaults are applied, the plugin logs one INFO message with the resolved stack and how to
override it.

---

## Directory layout & overlay order

Defaults require **no configuration**. Kprofiles assembles the prepared tree by scanning the
following directories (apply order matches this list, last wins):

```
Shared (always included)
└─ src/<sourceSet>/composeResources/
   ├─ values/strings.xml
   ├─ drawable/logo.png
   └─ font/inter.ttf

Platform family overlays (auto‑detected, selected via `kprofiles.family`)
└─ overlays/platform/<family>/composeResources/
   └─ … (e.g., overlays/platform/android/composeResources)

Build-type overlays (auto‑detected when debuggable/release is known)
└─ overlays/buildType/<type>/composeResources/
   └─ … (e.g., overlays/buildType/debug/composeResources)

Profile overlays (selected via CLI/env/defaults)
└─ overlays/profile/<profile>/composeResources/
   ├─ values/*.xml                (supports qualifiers: values-es, values-night, …)
   ├─ drawable/*                  (supports qualifiers: drawable-anydpi-v26, …)
   ├─ font/*
   └─ files/*                     (arbitrary blobs)
```

Only folders that look like resource roots (`values*`, `drawable*`, `font`, `files`) are copied.
Missing directories are simply skipped, so you can opt into overlays one folder at a time.

**Example overlay files**

```
overlays/platform/android/composeResources/values/colors.xml
overlays/buildType/debug/composeResources/values/strings.xml
overlays/profile/theme-blue/composeResources/drawable/logo.png
overlays/profile/brand-alpha/composeResources/values/strings.xml
```

### How merging works (at a glance)

1. Copy **shared** from `src/<sourceSet>/composeResources` (or your configured `sharedDir`).
2. Apply overlays in order, skipping any missing directory:
    1) **Platform family** → 2) **Build type** → 3) **Profiles** (left → right).
3. Only resource roots are considered: `values*`, `drawable*`, `font`, `files`.
4. **Override rule:** a later overlay **replaces** an earlier file when the **relative path** and *
   *name** match (e.g., `drawable/logo.png`). Shared < Platform < Build type < Profiles.

> **Override rule**: an overlay file **replaces** earlier entries when the **relative path** and
> **name** match (e.g., `drawable/logo.png`). Shared < Platform family < Build type < Profiles.

### Platform & build-type quick reference

| Target / compilation example      | Platform family | Overlay directory                                              |
|-----------------------------------|-----------------|----------------------------------------------------------------|
| `androidDebug`                    | `android`       | `overlays/platform/android/…` + `overlays/buildType/debug/…`   |
| `androidRelease`                  | `android`       | `overlays/platform/android/…` + `overlays/buildType/release/…` |
| `iosArm64` / `iosSimulatorArm64`  | `ios`           | `overlays/platform/ios/…`                                      |
| `macosX64` / `desktop` JVM target | `jvm`           | `overlays/platform/jvm/…`                                      |
| `jvmMain` (non-Android)           | `jvm`           | `overlays/platform/jvm/…`                                      |
| `wasmJs`                          | `wasm`          | `overlays/platform/wasm/…`                                     |

Notes:

- Family names are normalized by the plugin; you only need a directory for the families you care
  about.
- Build-type directories only run when Gradle knows the variant is debug or release (Android
  variants and debuggable Kotlin/Native compilations). Other targets skip this level automatically.

---

## How merging works

1. Copy **shared** from `src/<sourceSet>/composeResources` (or your configured `sharedDir`).
2. Apply overlays in this order, skipping any directory that does not exist:
    1. Platform **family** (e.g., `android`, `ios`, `jvm`).
    2. **Build type** when available (`debug`, `release`).
    3. Each profile from the resolved stack, left → right.
3. Only folders that look like resource roots are considered:
    - `values` (and qualified like `values-es`, `values-night`)
    - `drawable` (and qualified like `drawable-anydpi-v26`, `drawable-xxhdpi`, …)
    - `font`
    - `files`
4. Register the prepared directory with Compose; generation tasks (e.g.,
   `generateResourceAccessorsForCommonMain`) use this merged tree.

> **Order matters**: `-Pkprofiles.profiles=theme-blue,brand-alpha` means `brand-alpha` can override
`theme-blue` and shared.

---

## Profile-aware config

You can also generate a typed Kotlin config object from simple YAML files. We have functionality
for overlay-driven, profile-aware config generation (something that can replace BuildKonfig in
your workflow while being compatible with kprofiles). Enable the feature once:

```kotlin
import dev.goquick.kprofiles.generatedConfig
...

kprofiles {
    generatedConfig {
        enabled = true
        packageName = "dev.example.config"
        typeName = "AppConfig"
    }
}
```

Place flat key/value YAML at `src/commonMain/config/app.yaml` along with optional overlays that
follow the **same order** as resources:

- `overlays/platform/<family>/config/app.yaml`
- `overlays/buildType/<type>/config/app.yaml`
- `overlays/profile/<profile>/config/app.yaml`

Only scalars are allowed (string, int, double, boolean). Missing files are skipped silently.

### Example

_src/commonMain/config/app.yaml_
```
apiBaseUrl: "https://api.example.com"
retryCount: 3
```

_overlays/profile/staging/config/app.yaml_
```
apiBaseUrl: "https://staging.example.com"
featureX: true
```

When you build with `-Pkprofiles.profiles=staging`, the plugin merges the YAML (shared → platform →
build type → staging) and generates:

```kotlin
package dev.example.config

object AppConfig {
    const val apiBaseUrl: String = "https://staging.example.com"
    const val featureX: Boolean = true
    const val retryCount: Int = 3
}
```

Tasks to regenerate everything:

```bash
# Regenerate both overlays + generated config without compiling the app
./gradlew :composeApp:generateKprofiles
```

Tasks (rarely needed manually):

```bash
# Inspect the merged YAML output (for debugging only)
./gradlew :composeApp:kprofilesMergeConfigForCommonMain

# Print the stack + merged key/value table for quick inspection
./gradlew :composeApp:kprofilesConfigPrint
```

The generated sources live under `build/generated/kprofiles/config/<sourceSet>` (default
`commonMain`), and the Kotlin compiler picks them up automatically when
`kprofiles.generatedConfig.enabled=true`. Eligible scalars (String/Boolean/Int/Long/Double with
finite values) are emitted as `const val` by default; disable via
`generatedConfig.preferConstScalars.set(false)`.

### Advanced config usage

Need to inject a raw Kotlin expression instead of a scalar? Prefix the string with `[=val]` and the
generator emits the remainder verbatim without quotes. For example:

```
mainColor: "[=val] androidx.compose.ui.graphics.Color(0xFF0D47A1)"
```

becomes:

```kotlin
val mainColor = androidx.compose.ui.graphics.Color(0xFF0D47A1)
```

Assigned expressions always use `val` (never `const val`) and omit explicit types so you can
reference any shared API.

You can also pull secrets/config directly from build inputs:

- `[=env] VAR_NAME` / `[=env?] VAR_NAME` — read OS environment variables (optional `env?` variant
  falls back to `""`). When the variable is missing and `envLocalFallback` is `true` (default),
  Kprofiles also looks for a matching entry in `<root>/.env.local` so developers can keep
  secrets out of the tree while CI still relies on real env vars.
- `[=prop] KEY` / `[=prop?] KEY` — read Gradle properties (passed via `-Pkey=value` or
  `gradle.properties`).

Values are never echoed to logs, making it easy to flow CI secrets into generated config without
checking them into source control. For local development, drop sensitive values into
`<project>/.env.local` (git-ignored) and keep CI/CD pipelines wiring them via real environment
variables.

---

## Configuration (DSL)

Everything is optional; defaults are sensible.

```kotlin
kprofiles {
    // Default stack when CLI/env are not set. Order matters (last wins).
    defaultProfiles.set(listOf("theme-blue", "brand-alpha"))

    // Which KMP source set to prepare (currently only "commonMain" is supported).
    sourceSets.set(listOf("commonMain"))

    // Shared resource root (default: "src/commonMain/composeResources").
    sharedDir.set(layout.projectDirectory.dir("src/commonMain/composeResources"))

    // Pattern to locate profile overlays; must contain %PROFILE%.
    // Default: "overlays/profile/%PROFILE%/composeResources"
    profileDirPattern.set("overlays/profile/%PROFILE%/composeResources")

    // Top-level resource roots allowed in overlays. Suffix qualifiers supported with '-...'.
    // Default: {"values", "drawable", "font", "files"}
    allowedTopLevelDirs.set(setOf("drawable", "values", "font", "files"))

    // Allow [=env] lookups to fall back to <root>/.env.local (default: true).
    envLocalFallback.set(true)

    // When an overlay replaces an existing file: WARN, FAIL, or SILENT (default: WARN).
    collisionPolicy.set(CollisionPolicy.WARN)

    // Enforce stricter Android-ish folder names (default: false).
    strictAndroidNames.set(false)

    // Extra logging while preparing resources (default: false).
    logDiagnostics.set(false)
}
```

### Build-time knobs

- **Gradle property:** `-Pkprofiles.profiles=theme-blue,brand-alpha`
- **Environment variable:** `KPROFILES_PROFILES=theme-green`
- **Platform family:** `-Pkprofiles.family=jvm` (or `KPROFILES_FAMILY=jvm`)

If neither is set, `kprofiles.defaultProfiles` is used (if provided), otherwise shared-only.

When your module declares more than one Kotlin target family (e.g., JVM + iOS), you must select one
per invocation using `-Pkprofiles.family=…` (or `KPROFILES_FAMILY`). This keeps Compose from
emitting duplicate `Res.*` accessors. Single-family modules infer the family automatically.

### Platform selection

- **Why:** Compose generates a `Res.*` accessor per source set. Limiting each build to one platform
  family keeps those accessors unique and avoids duplicate-symbol errors.
- **How:** pass `-Pkprofiles.family=<android|ios|jvm|...>` (or `KPROFILES_FAMILY`) whenever your
  module targets multiple families. Single-family modules infer the family automatically.
- **Examples:**
    - Desktop run:
      `./gradlew :composeApp:run -Pkprofiles.family=jvm -Pkprofiles.profiles=theme-blue,brand-alpha`
    - iOS framework (from Xcode script):
      `-Pkprofiles.family=ios -Pkprofiles.profiles=theme-blue,brand-bravo`

---

## Diagnostics

Run these to understand what the plugin is doing (use your module name instead of `:composeApp`):

```bash
# Show resolved stack + directories for theme-blue → brand-alpha
./gradlew :composeApp:kprofilesPrintEffective -Pkprofiles.profiles=theme-blue,brand-alpha

# Validate structure (warns about missing values/*.xml or illegal Android names)
./gradlew :composeApp:kprofilesVerify -Pkprofiles.profiles=theme-blue,brand-alpha

# Inspect every prepared file with its origin and size
./gradlew :composeApp:kprofilesDiag -Pkprofiles.profiles=theme-blue,brand-alpha
```

Diagnostics print the **context vector** (platform family, build type, stack source: CLI |
ENV | DEFAULT | NONE) plus every overlay directory actually applied for resources and config.

Tip: set `kprofiles.strictAndroidNames.set(true)` in your build script if you want `kprofilesVerify`
to **fail** instead of warn whenever filenames break Android's lowercase/underscore rules.

---

## Tips

### Strings overlays

- Keep all shared keys in strings.xml.
- Put platform/build-type/profile-specific additions in separate files (e.g., strings_platform.xml)
  so you don’t replace the whole shared file.

**Shared (common)** _src/commonMain/composeResources/values/strings.xml_

```
<resources>
<string name="app_name">Kprofiles Demo</string>
<string name="welcome">Welcome</string>
</resources>
```

**Android-only additions** _overlays/platform/android/composeResources/values/strings_platform.xml_

```
<resources>
<string name="platform_label">Android</string>
</resources>
```

**iOS-only additions** _overlays/platform/ios/composeResources/values/strings_platform.xml_

```
<resources>
  <string name="platform_label">iOS</string>
</resources>
```
