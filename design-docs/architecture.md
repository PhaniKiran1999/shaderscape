# Shaderscape architecture: procedural live wallpapers

Two Gradle modules:

| Module | Type | Responsibility |
|---|---|---|
| `:wallpaper-engine` | Android library | Scene API, registry, selection store, EGL/GL render thread, renderers, `WallpaperService`, in-app preview view |
| `:app` | Android application | Registers concrete scenes (shader + pixel), Compose gallery/detail UI, "Set wallpaper" action |

## High-level component diagram

```mermaid
flowchart TB
    subgraph APP[":app (application)"]
        AppInit["ShaderscapeApp<br/>(Application.onCreate)"]
        subgraph Scenes["Concrete scenes"]
            SS["Shader scenes<br/>plasma, aurora (AssetShaderScene),<br/>StarfieldTunnelScene, LensFlareScene<br/>(custom uniforms)<br/>(GLSL in assets/shaders/*.frag)"]
            PS["Pixel scenes<br/>GameOfLifeScene, PixelFireScene, RetroPlasmaScene<br/>(Kotlin writes IntArray)"]
        end
        subgraph UI["Compose UI"]
            Main["MainActivity"]
            Preview["ScenePreview<br/>(Compose AndroidView wrapper)"]
            Gallery["GalleryScreen<br/>(grid of live previews)"]
            Detail["DetailScreen<br/>(full preview + Set button)"]
        end
    end

    subgraph LIB[":wallpaper-engine (library)"]
        subgraph API["Public API"]
            Scene["WallpaperScene (sealed)<br/>├ ShaderScene<br/>└ PixelScene"]
            Registry["WallpaperRegistry<br/>(id → scene factory)"]
            Selection["WallpaperSelection<br/>(SharedPreferences + listener)"]
            Setter["WallpaperSetter<br/>(launch system picker)"]
            SceneView["SceneView<br/>(TextureView preview)"]
        end
        subgraph Core["Rendering core"]
            RT["RenderThread<br/>(Choreographer loop, FPS cap,<br/>pause when invisible)"]
            EGL["EglCore<br/>(EGL14, OpenGL ES 2.0)"]
            SR["ShaderSceneRenderer<br/>(uTime, uResolution, uTouch, uOffset)"]
            PR["PixelSceneRenderer<br/>(IntArray → glTexSubImage2D,<br/>GL_NEAREST upscale)"]
            GLU["ShaderProgram + FullscreenQuad"]
        end
        Service["ProceduralWallpaperService<br/>(WallpaperService.Engine)"]
    end

    subgraph OS["Android OS"]
        WM["WallpaperManager /<br/>Live wallpaper picker"]
        Home["Home / Lock screen surface"]
        GPU["GPU (OpenGL ES)"]
    end

    AppInit -->|register| Registry
    SS -. implements .-> Scene
    PS -. implements .-> Scene
    Main --> Gallery --> Detail
    Gallery -->|embeds| Preview
    Detail -->|embeds| Preview
    Preview --> SceneView
    Detail -->|save id| Selection
    Detail -->|launch| Setter
    Setter -->|ACTION_CHANGE_LIVE_WALLPAPER| WM
    WM -->|binds| Service
    Service -->|reads id| Selection
    Service -->|lookup| Registry
    Selection -. change event .-> Service
    Service -->|owns 1 per engine| RT
    SceneView -->|owns| RT
    RT --> EGL
    RT -->|ShaderScene| SR
    RT -->|PixelScene| PR
    SR --> GLU
    PR --> GLU
    GLU --> GPU
    EGL -->|eglSwapBuffers| Home
```

## Rendering paths

```mermaid
flowchart LR
    subgraph GPUPath["ShaderScene: GPU path"]
        U["CPU: set uniforms<br/>(time, resolution, touch)"] --> FS["GPU: fragment shader<br/>runs per pixel, full res"] --> S1["Surface"]
    end
    subgraph CPUPath["PixelScene: CPU path"]
        R["CPU: scene.render(pixels, t)<br/>low-res: targetWidth wide,<br/>height from screen aspect"] --> T["glTexSubImage2D<br/>(upload texture)"] --> B["GPU: blit shader<br/>nearest-neighbor upscale"] --> S2["Surface"]
    end
```

## Key design rules
- **One pipeline:** both scene types draw through the same EGL surface and full-screen quad.
- **One service, many scenes:** a single `ProceduralWallpaperService` hosts every scene and composition. It shows whatever `WallpaperTarget` `WallpaperSelection` holds, and a change (or an edit to the active composition) swaps it live.
- **One `RenderThread` per surface:** home, lock and system preview engines and each in-app preview get their own thread and GL context.
- **Battery:** render only while visible, cap the FPS (30 by default, ~15 for gallery thumbnails), draw photo-only stacks once instead of continuously, and release GL resources on surface destroy.
- **Extensibility:** adding a wallpaper means subclassing `ShaderScene` or `PixelScene` and registering it. No engine changes are needed.

## Layered compositions

Everything the engine renders is a **`Composition`**: layers ordered bottom to top, each with a source, a blend mode, an opacity and a visibility flag. A plain scene is just a one-layer composition (`Composition.ofScene`), so the single-scene flows above go through the same path.

```mermaid
flowchart TB
    subgraph Model["composition/ (pure Kotlin + org.json)"]
        Comp["Composition<br/>id, title, layers (bottom → top)"]
        Layer["Layer<br/>source · blend · opacity · visible"]
        Src["LayerSource<br/>├ Scene(sceneId)<br/>└ Image(path, parallax)"]
        Store["CompositionStore<br/>compositions.json + images/<br/>importImage(uri): EXIF-rotate, downsample, copy"]
        Target["WallpaperTarget<br/>scene:id or comp:id"]
    end
    Comp --> Layer --> Src
    Store --> Comp
    Sel["WallpaperSelection.resolve(target)"] --> Comp
    Target --> Sel

    subgraph RT["RenderThread (one per surface)"]
        Pool["Renderer pool<br/>keyed by layer id + source<br/>(reused across edits)"]
        CR["CompositionRenderer"]
    end
    Comp -->|setComposition| Pool
    Pool --> CR
    CR --> SR["ShaderSceneRenderer"]
    CR --> PR["PixelSceneRenderer"]
    CR --> IR["ImageLayerRenderer<br/>cover-fit + parallax"]
    CR -->|readsBelow layers| FBO["2 × RenderTarget (FBO)<br/>ping-pong"]
```

### Blending
Each layer's output is **premultiplied alpha with opacity applied**. For shader scenes, the engine wraps the author's `main()` with a footer that does this, so blend modes are fixed GL blend functions with no per-scene code:

| Mode | `glBlendFunc` |
|---|---|
| Normal | `ONE, ONE_MINUS_SRC_ALPHA` |
| Add | `ONE, ONE` |
| Screen | `ONE, ONE_MINUS_SRC_COLOR` |
| Multiply | `DST_COLOR, ONE_MINUS_SRC_ALPHA` |

Scenes get `uLayered = 1` when they aren't the bottom layer. Aurora and Lens Flare then drop their sky and output brightness as alpha, so they overlay a photo cleanly.

### Two compositing paths
- **Fast path** (no layer reads below): every layer blends straight onto the window surface.
- **Read-below path** (any visible layer has `readsBelow = true`, e.g. Ripples): layers composite into offscreen target A. A read-below layer first copies A into B, then draws over B, sampling A as `uBelow`. Then A and B swap. A final blit puts A on screen. This costs one extra full-screen copy per read-below layer.

### Limits
`Composition.MAX_LAYERS = 5`, enforced by the store and the editor. Every visible layer is a full-screen pass, so fill-rate is the cost to watch. Debug builds log the average CPU draw time every 5 s (`RenderThread … avg draw`).

## Agent skill provider

The app is also a **skill provider** for on-device agents, through the [Agent Skill SDK](../../agent-skill-sdk/README.md):
- **`skill/WallpaperSkillService`** exposes three tools: `create_shader_wallpaper`, `revise_shader_wallpaper` and `list_my_wallpapers`. It's guarded by the SDK's signature permission.
- **`ShaderTidier`** fixes formatting mistakes before checking. **`engine/validation/ShaderValidator`** then checks agent-written GLSL on an offscreen GPU context, using the same header, footer and renderer as the live wallpaper. It returns fixable errors with the author's line numbers.
- **Valid shaders become drafts** (`CompositionStore` `drafts.json`): a single `LayerSource.Shader` layer, i.e. inline GLSL with no registry entry.
- **The agent receives a preview artifact**, a PendingIntent to `skill/PreviewActivity`. There the user tries the wallpaper and chooses Save, Set as wallpaper, Edit layers (composer) or Discard. The agent can never save or apply anything itself.

Full design: `agent-skill-sdk/design-docs/`.
