# Kprofiles

Profile‑ and platform‑aware **Compose Multiplatform** resources for **Android, iOS, and Desktop**.  
Kprofiles prepares a single **merged resource tree** for Compose by layering your shared assets with
optional overlays (platform family, build type, and one or more “profiles”) — **last wins**.

> **Important:** If a module targets multiple platform families (e.g., Android + iOS + Desktop),
> build **one family per Gradle invocation** (e.g., `-Pkprofiles.family=ios`). Single‑family modules
> don’t need this flag.

---

## Concepts

- **Shared** — your normal resources under `src/<sourceSet>/composeResources` (e.g.,
  `src/commonMain/composeResources`).
- **Platform family** — high‑level target: `android`, `ios`, or `jvm`. Auto‑detected, or pass
  `-Pkprofiles.family=…` (or `KPROFILES_FAMILY`).
- **Build type** — when known (`debug` / `release`) for Android variants and some Kotlin/Native
  compilations.
- **Profiles** — your own layers such as `brand-alpha`, `theme-blue`. Select with
  `-Pkprofiles.profiles=a,b` (or `KPROFILES_PROFILES`).

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
    id("dev.goquick.kprofiles") version "0.1.0"
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
./gradlew :composeApp:build -Pkprofiles.profiles=theme-blue

# If your module targets multiple families, also pass the family:
./gradlew :composeApp:build -Pkprofiles.family=jvm -Pkprofiles.profiles=theme-blue

# Or use environment variables
KPROFILES_PROFILES=theme-blue KPROFILES_FAMILY=jvm ./gradlew :composeApp:build
```

That’s it — Compose now generates `Res.*` against the **merged** tree, so your overlayed `logo.png`
is used.

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

### Profile stack precedence

Kprofiles resolves the active stack once per build using this order:

1. Gradle property `-Pkprofiles.profiles`
2. Environment variable `KPROFILES_PROFILES`
3. DSL fallback `kprofiles.defaultProfiles`
4. No profiles (shared-only)

When defaults are applied, the plugin logs a single INFO message showing the stack and how to
override it. If nothing is set, it logs that shared resources only are being used.
Platform/build-type overlays don't need extra configuration—the
plugin derives them automatically from the target or compilation and applies them whenever the
corresponding directories exist.

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

## Profile-aware config

You can also generate a typed Kotlin config object from simple YAML files. We have functionality
for overlay-driven, profile-aware config generation (something that can replace BuildKonfig in
your workflow while being compatible with kprofiles). Enable the feature once:

```kotlin
kprofiles {
    generatedConfig.apply {
        enabled.set(true)
        packageName.set("dev.example.config")
        typeName.set("AppConfig")
    }
}
```

Place flat key/value YAML at `src/commonMain/config/app.yaml` along with optional overlays that
follow the **same order** as resources:

- `overlays/platform/<family>/config/app.yaml`
- `overlays/buildType/<type>/config/app.yaml`
- `overlays/profile/<profile>/config/app.yaml`

Only scalars are allowed (string, int, double, boolean). Missing files are skipped silently.

Example:

```
src/commonMain/config/app.yaml
apiBaseUrl: "https://api.example.com"
retryCount: 3

overlays/profile/staging/config/app.yaml
apiBaseUrl: "https://staging.example.com"
featureX: true
```

When you build with `-Pkprofiles.profiles=staging`, the plugin merges the YAML (shared → platform →
build type → staging) and generates:

```kotlin
package dev.example.config

object AppConfig {
    val apiBaseUrl: String = "https://staging.example.com"
    val featureX: Boolean = true
    val retryCount: Int = 3
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
emitting
duplicate `Res.*` accessors. Single-family modules infer the family automatically.

### Profile selection precedence

1. `-Pkprofiles.profiles=…`
2. `KPROFILES_PROFILES=…`
3. `kprofiles { defaultProfiles.set(...) }`
4. Shared-only resources

When the fallback (defaultProfiles) is applied, the plugin logs a single INFO line indicating the
stack and reminding you that `-Pkprofiles.profiles` / `KPROFILES_PROFILES` overrides it.

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

## Examples

**A. Single theme**

```bash
./gradlew :composeApp:build -Pkprofiles.profiles=theme-blue
```

**B. Theme + brand (brand overrides theme)**

```bash
./gradlew :composeApp:build -Pkprofiles.profiles=theme-blue,brand-alpha
```

**C. CI-friendly env var**

```bash
KPROFILES_PROFILES=theme-green ./gradlew :composeApp:assembleDebug
```

### Sample app demo

The repository includes a Compose sample (`sample-app`) wired with platform, build-type, and profile
overlays:

- Resource overlays live under `sample-app/composeApp/overlays/{platform,buildType,profile}/…`
  (e.g., Android overrides `platform_label`, the debug build overrides `build_type_label`).
- Config overlays mirror the structure (e.g., `overlays/platform/jvm/config/app.yaml` tweaks
  `apiBaseUrl`).

Try these commands to inspect the stack:

```bash
# Show the context vector + overlay directories for the desktop target
./gradlew :sample-app:composeApp:kprofilesPrintEffective -Pkprofiles.profiles=theme-blue,brand-alpha

# Print every prepared file with its origin layer
./gradlew :sample-app:composeApp:kprofilesDiag -Pkprofiles.profiles=theme-blue,brand-alpha

# Launch the desktop sample and observe platform/build-type/profile-driven UI changes
./gradlew :sample-app:composeApp:run -Pkprofiles.profiles=theme-blue,brand-alpha
```
