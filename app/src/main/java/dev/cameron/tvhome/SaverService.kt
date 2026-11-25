package dev.cameron.tvhome

import android.graphics.Color
import android.media.MediaPlayer
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The screensaver: app/src/main/assets/saver.mp4, full screen, on a loop.
 *
 * It appears as "DG TV" in the Shield's Settings under
 * Device Preferences > Screen saver. Any button press ends it.
 */
class SaverService : DreamService(), SurfaceHolder.Callback {

    private var player: MediaPlayer? = null
    private var retried = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        open(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        close()
    }

    override fun onDreamingStopped() {
        close()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun open(holder: SurfaceHolder) {
        close()
        try {
            val mp = MediaPlayer()
            assets.openFd(SAVER_VIDEO).use { fd ->
                mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            mp.setDisplay(holder)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { ready ->
                if (player === ready) ready.start()
            }
            mp.setOnErrorListener { _, _, _ ->
                close()
                if (!retried) {
                    retried = true
                    open(holder)
                } else {
                    finish()
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            // No video, or one that will not open: end the screensaver and let the Shield sleep.
            close()
            finish()
        }
    }

    private fun close() {
        val mp = player ?: return
        player = null
        try {
            mp.release()
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private companion object {
        const val SAVER_VIDEO = "saver.mp4"
    }
}
