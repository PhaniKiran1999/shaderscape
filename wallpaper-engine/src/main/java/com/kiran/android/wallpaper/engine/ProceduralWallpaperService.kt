package com.kiran.android.wallpaper.engine

import android.graphics.PixelFormat
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.kiran.android.wallpaper.engine.composition.Composition
import com.kiran.android.wallpaper.engine.composition.CompositionStore
import com.kiran.android.wallpaper.engine.gl.RenderThread

/**
 * The single live-wallpaper service. It shows whatever [WallpaperSelection] points at (a
 * scene or a layered composition) and updates live when the selection changes or the
 * selected composition is edited.
 */
class ProceduralWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SceneEngine()

    private inner class SceneEngine : Engine(), WallpaperSelection.Listener, CompositionStore.Listener {

        private var renderThread: RenderThread? = null
        private var current: Composition? = null
        private var width = 0
        private var height = 0

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            surfaceHolder.setFormat(PixelFormat.RGBA_8888)
            setTouchEventsEnabled(true)
            WallpaperSelection.init(applicationContext)

            renderThread = RenderThread(applicationContext, if (isPreview) "WallpaperPreview" else "Wallpaper")
            show(WallpaperSelection.resolve())
            WallpaperSelection.addListener(this)
            CompositionStore.instance.addListener(this)
        }

        override fun onSelectionChanged(target: WallpaperTarget?) = show(WallpaperSelection.resolve(target))

        override fun onCompositionsChanged() = show(WallpaperSelection.resolve())

        /** Pushes [c] to the render thread only if it actually changed (data-class equality). */
        private fun show(c: Composition?) {
            if (c == current) return
            current = c
            renderThread?.setComposition(c)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderThread?.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            renderThread?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderThread?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderThread?.setVisible(visible)
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (width <= 0 || height <= 0) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    renderThread?.touch(event.x / width, event.y / height)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    renderThread?.touch(-1f, -1f)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            renderThread?.offset(xOffset)
        }

        override fun onDestroy() {
            WallpaperSelection.removeListener(this)
            CompositionStore.instance.removeListener(this)
            renderThread?.quit()
            renderThread = null
            super.onDestroy()
        }
    }
}
