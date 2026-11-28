# Shaderscape

Interactive live wallpapers for Android, drawn in real time with **OpenGL ES shaders** and **CPU pixel graphics**.
Stack them over your own photos, or ask an AI agent to write new ones.

## Features
- **Live wallpapers that react to touch:** plasma, aurora, a starfield tunnel, a lens flare you drag out of the light,
  refracting rain ripples, pixel fire, Game of Life and retro plasma.
- **Layered wallpapers:** a gallery photo plus up to five layers, each with a blend mode (normal, add, screen,
  multiply), opacity and visibility. Effects like ripples can **distort the layers below** them.
  Photo parallax follows the launcher scroll, or stays centred.
- **Composer:** build, reorder and preview layer stacks, then save them to *My wallpapers*.
- **Battery-aware:** rendering pauses when the wallpaper isn't visible, and the frame rate is capped.
- **Agent skill:** an on-device agent such as [PAgent](https://github.com/PhaniKiran1999/Personal-Agent) can write
  free-form GLSL wallpapers. Shaderscape compiles and checks them, reports errors back so the model can fix them,
  and opens a live preview. You decide whether to **save**, **set as wallpaper**, **add layers** or **discard**.

## Project layout

| Module | What it is |
|---|---|
| `:wallpaper-engine` | A reusable library: `WallpaperService`, the EGL/GLES 2.0 render thread, shader and pixel scenes, compositions and blending, shader validation, the in-app preview view and the set-wallpaper flow. See [wallpaper-engine/README.md](wallpaper-engine/README.md). |
| `:app` | The Shaderscape app: the built-in scenes (`scenes/`, `assets/shaders/`), gallery, detail and composer screens (`ui/`), and the agent skill (`skill/`). |

Design docs: [architecture](design-docs/architecture.md) · [sequence flows](design-docs/sequence-flows.md).

## Build and run

Requirements: Android Studio with JDK 21+, Android SDK 37. The app targets SDK 37, with minSdk 24.

```bash
./gradlew :app:assembleDebug :app:installDebug
./gradlew :app:testDebugUnitTest :wallpaper-engine:testDebugUnitTest
```

Open the app, pick a wallpaper (or compose one), and tap **Set as wallpaper**. The first time, Android shows its
live-wallpaper picker, and the entry is called *Shaderscape*.

### Release APKs
Every push to `master` runs [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml): unit tests, then a
release APK signed with the release key, published as a GitHub Release (`build-<run number>`, version `1.0.<run number>`).

It must be the **same key as PAgent's**: PAgent binds to the wallpaper skill through a signature-level permission.
The repository's `Shaderscape` environment (Settings → Environments) holds four secrets with the same names and values as
PAgent's: `PAGENT_KEYSTORE_BASE64` (the `.jks` file, base64-encoded), `PAGENT_KEYSTORE_PASSWORD`, `PAGENT_KEY_ALIAS` and
`PAGENT_KEY_PASSWORD`.

To sign release builds locally, create `keystore.properties` in the repo root (git-ignored). It's the same file as
PAgent's:
```properties
PAGENT_KEYSTORE_FILE=C:/path/to/pagent-release.jks
PAGENT_KEYSTORE_PASSWORD=...
PAGENT_KEY_ALIAS=pagent
PAGENT_KEY_PASSWORD=...
```
Without it, `assembleRelease` produces an unsigned APK. Don't mix a debug build of one app with a release build of the
other: both declare the skill permission, so Android refuses to install the second one with a different key.

## Writing a wallpaper

**Shader:** add `assets/shaders/mine.frag` containing just `void main()`. The engine supplies `vUv`, `uTime`,
`uResolution`, `uTouch`, `uTaps[4]` (recent taps), `uOffset`, `uLayered` and `uBelow`. Then register it in
`ShaderscapeApp`:

```kotlin
WallpaperRegistry.register("mine", "Mine", "What it does") { AssetShaderScene(it, "shaders/mine.frag") }
```

**Pixels:** subclass `PixelScene` and fill an `IntArray` of ARGB values each frame. The engine scales it up.
The full uniform reference and the layering API are in the [engine README](wallpaper-engine/README.md).

## Agent skill

`skill/WallpaperSkillService` is a skill provider built with the
[Agent Skill SDK](https://github.com/PhaniKiran1999/agent-skill-sdk) (from JitPack,
`com.github.PhaniKiran1999:agent-skill-sdk`). It exposes these tools:

| Tool | Does |
|---|---|
| `create_shader_wallpaper` | Validates GLSL from the model, stores a draft, and returns a preview card |
| `revise_shader_wallpaper` | Updates the same draft from feedback |
| `list_my_wallpapers` | Lists saved wallpapers so the agent can refer to them |

Agent drafts are never applied automatically. They open in `PreviewActivity`, where the user chooses.
The agent app must be signed with the same key, because the skill permission is signature-level.

To develop against a local SDK checkout at `../agent-skill-sdk`, add this to `local.properties`:
```properties
useLocalSkillSdk=true
```

## Tech
Kotlin, Jetpack Compose, OpenGL ES 2.0 on a dedicated EGL thread driven by `Choreographer`, FBO ping-pong for layers
that read the layers below, and AIDL/Binder for the agent skill.
