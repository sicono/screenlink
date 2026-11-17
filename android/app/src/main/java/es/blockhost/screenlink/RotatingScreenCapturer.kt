package es.blockhost.screenlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Capturador de pantalla con MediaProjection que, al girar el móvil, REDIMENSIONA el
 * VirtualDisplay en vez de recrearlo (Android 14+ detiene la proyección si se recrea).
 */
class RotatingScreenCapturer(
    private val permissionData: Intent,
    private val projectionCallback: MediaProjection.Callback,
) : VideoCapturer, VideoSink {

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var ctx: Context? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 320
    private var disposed = false

    override fun initialize(helper: SurfaceTextureHelper, ctx: Context, observer: CapturerObserver) {
        this.helper = helper
        this.ctx = ctx.applicationContext
        this.observer = observer
        dpi = ctx.resources.displayMetrics.densityDpi
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val h = helper ?: return
        this.width = width
        this.height = height
        val mpm = ctx!!.getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(Activity.RESULT_OK, permissionData)
            ?: run { observer?.onCapturerStarted(false); return }
        projection = mp
        // Android 14+: hay que registrar el callback ANTES de crear el VirtualDisplay
        mp.registerCallback(projectionCallback, h.handler)
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) {
            h.setTextureSize(width, height)
            val s = Surface(h.surfaceTexture)
            surface = s
            display = mp.createVirtualDisplay(
                "ScreenLink", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                s, null, null
            )
        }
        observer?.onCapturerStarted(true)
        h.startListening(this)
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val h = helper ?: return
        if (width == this.width && height == this.height) return
        this.width = width
        this.height = height
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) {
            val d = display ?: return@invokeAtFrontUninterruptibly
            h.setTextureSize(width, height)
            d.resize(width, height, dpi)
            // Superficie nueva con el tamaño nuevo
            val old = surface
            val s = Surface(h.surfaceTexture)
            surface = s
            d.surface = s
            old?.release()
        }
    }

    override fun stopCapture() {
        val h = helper ?: return
        ThreadUtils.invokeAtFrontUninterruptibly(h.handler) {
            h.stopListening()
            observer?.onCapturerStopped()
            display?.release(); display = null
            surface?.release(); surface = null
            projection?.let {
                try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
                try { it.stop() } catch (_: Exception) {}
            }
            projection = null
        }
    }

    override fun dispose() { disposed = true }

    override fun isScreencast(): Boolean = true

    override fun onFrame(frame: VideoFrame) {
        observer?.onFrameCaptured(frame)
    }
}
