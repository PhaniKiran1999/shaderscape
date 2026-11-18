# Sequence Flows

The end-to-end flow from process start to an animating home screen, in five stages:

1. [App init and scene registration](#1-app-init-and-scene-registration)
2. [Gallery and in-app preview](#2-gallery-and-in-app-preview)
3. [Setting the wallpaper](#3-setting-the-wallpaper)
4. [Wallpaper service engine and frame loop](#4-wallpaper-service-engine-and-frame-loop)
5. [Hot-swapping the scene](#5-hot-swapping-the-scene)
6. [Composing and saving a layered wallpaper](#6-composing-and-saving-a-layered-wallpaper)
7. [Drawing one layered frame](#7-drawing-one-layered-frame)

---

## 1. App init and scene registration

```mermaid
sequenceDiagram
    autonumber
    participant OS as Android OS
    participant App as WallpapersApp
    participant Reg as WallpaperRegistry
    participant Sel as WallpaperSelection
    participant Act as MainActivity

    OS->>App: create process → Application.onCreate()
    App->>Reg: register("plasma") { AssetShaderScene(ctx, "shaders/plasma.frag") }
    App->>Reg: register("aurora"), register("tunnel"), register("lens-flare")
    App->>Reg: register("fire") { PixelFireScene() }
    App->>Reg: register("life"), register("retro-plasma")
    App->>Sel: init(context) (load SharedPreferences)
    Note over Reg: Factories only, so no GL objects exist yet.<br/>Scenes are created per surface later.
    OS->>Act: launch → onCreate()
    Act->>Reg: all()
    Reg-->>Act: list of SceneInfo(id, title, type)
    Act->>Act: setContent { GalleryScreen(scenes) }
```

> The service process path also runs `Application.onCreate()`, so the registry is always populated before `ProceduralWallpaperService` looks anything up.

---

## 2. Gallery and in-app preview

```mermaid
sequenceDiagram
    autonumber
    participant G as GalleryScreen
    participant SV as SceneView (TextureView)
    participant Reg as WallpaperRegistry
    participant RT as RenderThread
    participant EGL as EglCore
    participant R as Scene renderer

    G->>SV: ScenePreview → AndroidView(SceneView(sceneId, maxFps=15))
    SV->>SV: onSurfaceTextureAvailable(w, h)
    SV->>Reg: create(sceneId)
    Reg-->>SV: new WallpaperScene instance
    SV->>RT: start(surface, scene, fps)
    RT->>EGL: eglCreateContext + eglCreateWindowSurface
    RT->>R: init() (compile shaders, alloc texture/buffer)
    RT->>R: onSurfaceChanged(w, h)
    loop Each Choreographer frame (throttled)
        RT->>R: drawFrame(timeSec)
        RT->>EGL: eglSwapBuffers
    end
    G->>SV: card scrolled off-screen / disposed
    SV->>RT: stop()
    RT->>R: release()
    RT->>EGL: destroy surface + context
```

---

## 3. Setting the wallpaper

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant D as DetailScreen
    participant Sel as WallpaperSelection
    participant Set as WallpaperSetter
    participant WM as WallpaperManager
    participant Pick as System live-wallpaper picker
    participant Svc as ProceduralWallpaperService

    User->>D: tap "Set wallpaper"
    D->>Sel: setSelected("fire")
    D->>Set: launch(context)
    Set->>WM: getWallpaperInfo()
    alt Our service is already the active live wallpaper
        WM-->>Set: component == ProceduralWallpaperService
        Set-->>D: done (no picker needed)
        Note over Sel,Svc: Selection change event hot-swaps the scene (flow 5)
    else Not active yet
        Set->>Pick: startActivity(ACTION_CHANGE_LIVE_WALLPAPER,<br/>EXTRA_LIVE_WALLPAPER_COMPONENT)
        Pick->>Svc: bind → onCreateEngine() (isPreview = true)
        Note over Pick: Picker shows full-screen preview
        User->>Pick: tap "Set wallpaper" (home / lock / both)
        Pick->>WM: setWallpaperComponent(...)
        WM->>Svc: onCreateEngine() for home (isPreview = false)
        Pick->>Svc: destroy preview engine
    end
```

> Fallback: if `ACTION_CHANGE_LIVE_WALLPAPER` isn't resolvable, `WallpaperSetter` opens `ACTION_LIVE_WALLPAPER_CHOOSER`.

---

## 4. Wallpaper service engine and frame loop

```mermaid
sequenceDiagram
    autonumber
    participant Sys as System (WallpaperManagerService)
    participant Eng as Service.Engine
    participant Sel as WallpaperSelection
    participant Reg as WallpaperRegistry
    participant RT as RenderThread
    participant EGL as EglCore
    participant R as Renderer (Shader / Pixel)
    participant S as WallpaperScene

    Sys->>Eng: onCreate(surfaceHolder)
    Eng->>Sel: getSelected() + addListener(this)
    Eng->>Reg: create(selectedId)
    Reg-->>Eng: scene
    Sys->>Eng: onSurfaceCreated(holder)
    Eng->>RT: start(holder.surface, scene)
    RT->>EGL: create context + window surface
    RT->>R: init(scene)
    Sys->>Eng: onSurfaceChanged(w, h)
    Eng->>RT: resize(w, h)
    RT->>R: onSurfaceChanged(w, h)
    Sys->>Eng: onVisibilityChanged(true)
    Eng->>RT: resume()

    loop Choreographer frame, capped at scene.targetFps
        alt ShaderScene
            RT->>R: drawFrame(t)
            R->>R: set uTime, uResolution, uTouch, uOffset
            R->>R: draw fullscreen quad (GPU runs fragment shader)
        else PixelScene
            RT->>R: drawFrame(t)
            R->>S: render(pixels: IntArray, t) on CPU
            R->>R: glTexSubImage2D(pixels)
            R->>R: draw quad with blit shader (GL_NEAREST)
        end
        RT->>EGL: eglSwapBuffers → home screen
    end

    Sys->>Eng: onTouchEvent / onOffsetsChanged
    Eng->>RT: post(input) → scene.onTouch / onOffsetsChanged

    Sys->>Eng: onVisibilityChanged(false) (screen off / app opened)
    Eng->>RT: pause() (stop Choreographer callbacks, 0 frames)

    Sys->>Eng: onSurfaceDestroyed / onDestroy
    Eng->>Sel: removeListener(this)
    Eng->>RT: stop()
    RT->>R: release() (delete programs, textures)
    RT->>EGL: destroy surface + context, quit thread
```

---

## 5. Hot-swapping the scene

When our service is already the active wallpaper and the user picks another scene in the app:

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant D as DetailScreen
    participant Sel as WallpaperSelection
    participant Eng as Service.Engine
    participant Reg as WallpaperRegistry
    participant RT as RenderThread
    participant Old as Old renderer
    participant New as New renderer

    User->>D: tap "Set wallpaper" on "aurora"
    D->>Sel: setSelected("aurora")
    Sel-->>Eng: onSelectionChanged("aurora")<br/>(SharedPreferences listener)
    Eng->>Reg: create("aurora")
    Reg-->>Eng: new scene
    Eng->>RT: post { swapScene(scene) }
    RT->>Old: release()
    RT->>New: init(scene) + onSurfaceChanged(w, h)
    Note over RT: Same EGL context and surface are reused.<br/>Next frame shows the new scene.
    RT->>New: drawFrame(t) ...
```


---

## 6. Composing and saving a layered wallpaper

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant C as ComposerScreen
    participant PP as System Photo Picker
    participant St as CompositionStore
    participant SV as SceneView (preview)
    participant RT as RenderThread
    participant Set as WallpaperSetter
    participant Svc as Service.Engine

    User->>C: + Photo
    C->>PP: PickVisualMedia(ImageOnly)
    PP-->>C: content uri
    C->>St: importImage(uri) on Dispatchers.IO
    St-->>C: files/wallpaper_engine/images/uuid.jpg
    C->>C: draft.layers = [Image] + layers
    C->>SV: composition = draft
    SV->>RT: setComposition(draft)
    RT->>RT: pool: create ImageLayerRenderer (decode + upload once)
    User->>C: + Effect (Ripples, Aurora, Lens Flare)
    C->>SV: composition = draft
    SV->>RT: setComposition(draft)
    Note over RT: Existing layers keep their renderers (same id + source).<br/>Only new layers are created.
    User->>C: drag opacity / change blend / reorder
    C->>SV: composition = draft
    SV->>RT: setComposition(draft)
    Note over RT: No renderer is created: only order and parameters change.
    User->>C: Set wallpaper
    C->>St: save(draft), pruneImages()
    St-->>Svc: onCompositionsChanged() (if it is the active wallpaper)
    C->>Set: apply(WallpaperTarget.Composition(id))
    alt engine already active
        Set-->>C: UPDATED (selection change hot-swaps)
    else
        Set-->>C: PICKER_OPENED (system picker, as in flow 3)
    end
```

## 7. Drawing one layered frame

```mermaid
sequenceDiagram
    autonumber
    participant RT as RenderThread
    participant CR as CompositionRenderer
    participant A as Target A (FBO)
    participant B as Target B (FBO)
    participant L as Layer renderers
    participant W as Window surface

    RT->>CR: draw(t)
    alt no visible layer reads below
        CR->>W: bind window, clear
        loop each visible layer, bottom to top
            CR->>L: glBlendFunc(mode), draw(t, opacity, layered)
        end
    else some layer reads below
        CR->>A: bind, clear
        loop each visible layer, bottom to top
            alt layer.readsBelow
                CR->>B: bind, copy A into B
                CR->>L: draw over B with uBelow = A
                CR->>CR: swap(A, B)
            else
                CR->>A: glBlendFunc(mode), draw(t, opacity, layered)
            end
        end
        CR->>W: bind window, blit A
    end
    RT->>W: eglSwapBuffers
    Note over RT: Animated if any visible layer is a scene.<br/>Photo-only stacks draw once per change.
```
