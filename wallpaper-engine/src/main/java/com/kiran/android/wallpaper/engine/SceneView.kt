package com.kiran.android.wallpaper.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.gl.RenderThread

/**
 * Renders a scene or a layered [Composition] inside an app, for gallery thumbnails, previews
 * and the editor. It uses the same render thread and renderers as the live wallpaper.
 *
 * Set [maxFps] and [interactive] before the view is attached. [composition] (or the
 * [sceneId] shortcut) can change at any time. Edits that keep layer ids reuse the loaded
 * shaders and photos, so slider-driven updates are cheap.
 */
class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    var composition: Composition? = null
        set(value) {
            if (field == value) return
            field = value
            renderThread?.setComposition(value)
        }

    /** Shortcut that shows a single registered scene. */
    var sceneId: String?
        get() = (composition?.layers?.singleOrNull()?.source as? com.kiran.android.wallpaper.engine.composition.LayerSource.Scene)?.sceneId
        set(value) {
            composition = value?.let { id -> Composition.ofScene(id, WallpaperRegistry[id]?.title ?: id) }
        }

    /** Extra frame-rate cap on top of the scenes' own, e.g. 15 for thumbnails. */
    var maxFps: Int = Int.MAX_VALUE

    /** When true, touches go to the scenes the same way they do on the home screen. */
    var interactive: Boolean = false

    private var renderThread: RenderThread? = null
    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val rt = RenderThread(context, "SceneView-${composition?.id}", maxFps)
        renderThread = rt
        rt.setComposition(composition)
        val s = Surface(st).also { surface = it }
        rt.surfaceCreated(s)
        rt.surfaceChanged(width, height)
        rt.setVisible(windowVisibility == VISIBLE)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        renderThread?.surfaceChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderThread?.let {
            it.surfaceDestroyed()
            it.quit()
        }
        renderThread = null
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        renderThread?.setVisible(visibility == VISIBLE)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive || width == 0 || height == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                renderThread?.touch(event.x / width, event.y / height)
            }
            MotionEvent.ACTION_MOVE -> renderThread?.touch(event.x / width, event.y / height)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderThread?.touch(-1f, -1f)
        }
        return true
    }
}
