# wallpaper-engine

An Android library for procedural live wallpapers. It ships the `WallpaperService`, the EGL/GL render loop, an in-app preview view, and the "set wallpaper" flow. You supply scenes.

## Adding a wallpaper

**GPU shader**: put a fragment shader in `assets/` and register it:

```kotlin
WallpaperRegistry.register("plasma", "Plasma", "Flowing sine plasma") {
    AssetShaderScene(it, "shaders/plasma.frag")
}
```

The engine prepends a header, so the shader only needs `void main()`. Don't redeclare these, and don't write `#version` or `precision`:

| Name | Type | Meaning |
|---|---|---|
| `vUv` | `vec2` | 0..1, bottom-left origin |
| `uTime` | `float` | seconds (wraps hourly) |
| `uResolution` | `vec2` | surface size in px |
| `uTouch` | `vec2` | touch in px (bottom-left), `-1` when not touching |
| `uOffset` | `float` | launcher scroll 0..1 |
| `uLayered` | `float` | 1.0 when drawn over other layers |
| `uBelow` | `sampler2D` | layers below (only if `readsBelow`) |

For custom uniforms, subclass `ShaderScene` or `AssetShaderScene` and override `onBindUniforms`.

**CPU pixel buffer**: subclass `PixelScene` and write ARGB ints:

```kotlin
class Noise : PixelScene(targetWidth = 90) {
    override fun render(pixels: IntArray, timeSec: Float) {
        for (i in pixels.indices) pixels[i] = Palette.argb(0, 0, (i * 31) and 255)
    }
}
WallpaperRegistry.register("noise", "Noise") { Noise() }
```

The buffer is `targetWidth` wide, and its height follows the screen aspect ratio. Row 0 is the top. The engine scales it up with nearest-neighbour filtering.

## Host app setup

1. Register scenes and call `WallpaperSelection.init(this)` in `Application.onCreate()`.
2. Preview a scene with `SceneView` (set `sceneId`, plus optional `maxFps` and `interactive`).
3. Apply one with `WallpaperSetter.apply(activity, sceneId)`. It opens the system picker the first time. After that, it hot-swaps the running wallpaper.
4. Optionally override the string `wallpaper_engine_label` to rename the entry in the system picker.

## Layered wallpapers

A `Composition` stacks layers bottom to top. Each layer is a registered scene or a photo, with a blend mode (`NORMAL`, `ADD`, `SCREEN`, `MULTIPLY`), an opacity and a visibility flag. There are at most `Composition.MAX_LAYERS` (5).

```kotlin
val store = CompositionStore.instance
val photo = store.importImage(context, pickedUri)          // off the main thread
val c = Composition(id = UUID.randomUUID().toString(), title = "Evening", layers = listOf(
    Layer(LayerSource.Image(photo)),
    Layer(LayerSource.Scene("ripple")),                     // refracts the photo
    Layer(LayerSource.Scene("aurora"), BlendMode.SCREEN, opacity = 0.8f),
))
store.save(c)
sceneView.composition = c                                   // preview
WallpaperSetter.apply(activity, WallpaperTarget.Composition(c.id))
```

**Writing overlay-friendly shaders:**
- Check `uLayered`. When it's 1.0, skip your own background and output alpha. The engine premultiplies and applies opacity for you.
- To distort what's underneath, override `readsBelow = true` and sample `texture2D(uBelow, gl_FragCoord.xy / uResolution)`.
- Pixel scenes can write ARGB alpha.

Editing a composition that keeps layer ids (opacity, blend, order, visibility) reuses the loaded shaders and photos, so live editors stay cheap.

Design docs: [`../design-docs`](../design-docs).
