package es.blockhost.screenlink

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame
import android.os.Handler
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

data class Quality(val label: String, val maxLong: Int, val fps: Int, val bitrate: Int) {
    companion object {
        val ALL = listOf(
            Quality("Fluido · 720p · 60 fps", 1280, 60, 5_000_000),
            Quality("Equilibrado · 1080p · 30 fps", 1920, 30, 6_000_000),
            Quality("Máxima · 1080p · 60 fps", 1920, 60, 10_000_000),
        )
    }
}

/**
 * Captura la pantalla con MediaProjection y la emite por WebRTC a los espectadores
 * de la web (peerjs). Flujo por espectador:
 *   web → OFFER (data)  ⇒  respondemos ANSWER (data)
 *   nosotros → OFFER (media, sólo envío)  ⇒  web responde ANSWER
 */
class ScreenHost(
    private val ctx: Context,
    private val projectionData: Intent,
    private val q: Quality,
    private val cb: Callback,
) {
    interface Callback {
        fun onCode(code: String)
        fun onStatus(status: String)
        fun onViewers(count: Int)
        fun onProjectionStopped()
    }

    companion object {
        private const val TAG = "ScreenHost"
        const val ID_PREFIX = "screenlink-v1-"
        private const val MIN_BITRATE = 1_200_000
        const val CONFIG_URL = "https://sicono.github.io/screenlink/config.json"
        val DEFAULT_ICE: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder(listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun.cloudflare.com:3478",
            )).createIceServer(),
        )

        private fun parseIce(arr: JSONArray?): List<PeerConnection.IceServer> {
            if (arr == null) return emptyList()
            val out = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urls = mutableListOf<String>()
                when (val u = o.opt("urls") ?: o.opt("url")) {
                    is String -> urls.add(u)
                    is JSONArray -> for (j in 0 until u.length()) urls.add(u.optString(j))
                }
                if (urls.isEmpty()) continue
                val b = PeerConnection.IceServer.builder(urls)
                o.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
                o.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
                out.add(b.createIceServer())
            }
            return out
        }
    }

    private class Viewer(val peerId: String, val name: String) {
        var dataConnId = ""
        var dataPc: PeerConnection? = null
        var channel: DataChannel? = null
        var mediaConnId = ""
        var mediaPc: PeerConnection? = null
        var connected = false
        val pending = HashMap<String, MutableList<IceCandidate>>()
    }

    private val rtc = Executors.newSingleThreadScheduledExecutor()
    private val rnd = SecureRandom()

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: RotatingScreenCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var signaling: PeerJsSignaling? = null
    private val viewers = HashMap<String, Viewer>()
    private var code = ""
    private var stopped = false
    private var iceServers: List<PeerConnection.IceServer> = DEFAULT_ICE
    private var capW = 0
    private var capH = 0

    private fun post(block: () -> Unit) {
        try { rtc.execute { try { block() } catch (e: Exception) { Log.e(TAG, "error", e) } } }
        catch (_: RejectedExecutionException) {}
    }

    private fun postDelayed(ms: Long, block: () -> Unit) {
        try { rtc.schedule({ try { block() } catch (e: Exception) { Log.e(TAG, "error", e) } }, ms, TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) {}
    }

    // ------------------------------------------------------------------ inicio

    fun start() = post {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .createInitializationOptions()
        )
        val eglBase = EglBase.create()
        egl = eglBase
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val f = factory!!
        helper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
        source = f.createVideoSource(true)
        val cap = RotatingScreenCapturer(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { cb.onProjectionStopped() }
        })
        // Repite el último fotograma cuando la pantalla está quieta: Android sólo emite
        // fotogramas cuando algo cambia y el codificador nunca refinaría la imagen.
        val repeater = RepeatingObserver(source!!.capturerObserver, helper!!.handler)
        cap.initialize(helper!!, ctx.applicationContext, repeater)
        val (w, h) = captureSize()
        capW = w; capH = h
        cap.startCapture(w, h, q.fps)
        capturer = cap
        track = f.createVideoTrack("screen0", source)
        track?.setEnabled(true)

        cb.onStatus("Conectando con el servidor…")
        iceServers = loadIceServers()
        openSignaling(newCode = true)
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val long = maxOf(w, h)
        val scale = minOf(1.0, q.maxLong.toDouble() / long)
        fun even(v: Double) = (v.toInt() / 2) * 2
        return even(w * scale) to even(h * scale)
    }

    /** Lee config.json (el mismo que usa la web) y, si hay, las credenciales TURN. */
    private fun loadIceServers(): List<PeerConnection.IceServer> {
        val http = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build()
        fun get(url: String): String? = try {
            http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) { Log.w(TAG, "GET $url", e); null }

        val cfgText = get(CONFIG_URL + "?t=" + System.currentTimeMillis()) ?: return DEFAULT_ICE
        return try {
            val cfg = JSONObject(cfgText)
            val list = parseIce(cfg.optJSONArray("iceServers")).ifEmpty { DEFAULT_ICE }.toMutableList()
            val turnUrl = cfg.optString("turnCredentialsUrl")
            if (turnUrl.isNotEmpty()) {
                get(turnUrl)?.trim()?.let { t ->
                    val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("iceServers")
                    list.addAll(parseIce(arr))
                }
            }
            Log.i(TAG, "ICE servers: ${list.size}")
            list
        } catch (e: Exception) {
            Log.w(TAG, "config.json inválido", e); DEFAULT_ICE
        }
    }

    fun onRotation() = post {
        val c = capturer ?: return@post
        val (w, h) = captureSize()
        if (w != capW || h != capH) {
            capW = w; capH = h
            c.changeCaptureFormat(w, h, q.fps)
        }
    }

    // ------------------------------------------------------------- señalización

    private fun openSignaling(newCode: Boolean) {
        if (stopped) return
        if (newCode || code.isEmpty()) code = String.format("%06d", rnd.nextInt(1_000_000))
        signaling?.close()
        val s = PeerJsSignaling(ID_PREFIX + code, object : PeerJsSignaling.Listener {
            override fun onOpen() = post {
                cb.onCode(code)
                cb.onStatus(if (viewers.isEmpty()) "Esperando espectadores" else "Compartiendo")
            }
            override fun onIdTaken() = post { openSignaling(newCode = true) }
            override fun onMessage(type: String, src: String, payload: JSONObject?) = post { handle(type, src, payload) }
            override fun onClosed(reason: String) = post {
                if (stopped) return@post
                cb.onStatus("Reconectando con el servidor…")
                postDelayed(2500) { openSignaling(newCode = false) }
            }
        })
        signaling = s
        s.connect()
    }

    private fun handle(type: String, src: String, payload: JSONObject?) {
        if (stopped || src.isEmpty()) return
        when (type) {
            "OFFER" -> if (payload?.optString("type") == "data") onDataOffer(src, payload)
            "ANSWER" -> if (payload != null) onAnswer(src, payload)
            "CANDIDATE" -> if (payload != null) onCandidate(src, payload)
            "LEAVE", "EXPIRE" -> removeViewer(src)
        }
    }

    private fun onDataOffer(src: String, payload: JSONObject) {
        val connId = payload.optString("connectionId")
        val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
        val name = payload.optJSONObject("metadata")?.optString("name")?.takeIf { it.isNotBlank() } ?: "Dispositivo"
        removeViewer(src)

        val v = Viewer(src, name)
        v.dataConnId = connId
        viewers[src] = v
        val pc = createPc(v, connId, "data") ?: return
        v.dataPc = pc
        updateViewers()

        pc.setRemoteDescription(object : SdpAdapter("data-remote") {
            override fun onSetSuccess() = post {
                flushPending(v, connId, pc)
                pc.createAnswer(object : SdpAdapter("data-answer") {
                    override fun onCreateSuccess(desc: SessionDescription) = post {
                        pc.setLocalDescription(SdpAdapter("data-local"), desc)
                        signaling?.send("ANSWER", src, JSONObject()
                            .put("sdp", JSONObject().put("type", "answer").put("sdp", desc.description))
                            .put("type", "data")
                            .put("connectionId", connId)
                            .put("browser", "android"))
                        startMedia(v)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun startMedia(v: Viewer) {
        if (stopped || viewers[v.peerId] !== v) return
        val f = factory ?: return
        val t = track ?: return
        val connId = "mc_" + PeerJsSignaling.randomToken(12)
        v.mediaConnId = connId
        val pc = createPc(v, connId, "media") ?: return
        v.mediaPc = pc

        val tr = pc.addTransceiver(t, RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("screenlink")))
        // H.264 primero: codificación por hardware en casi todos los móviles
        try {
            val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val sorted = caps.sortedBy {
                when (it.mimeType.lowercase()) {
                    "video/h264" -> 0
                    "video/vp8" -> 1
                    "video/vp9" -> 2
                    else -> 3
                }
            }
            tr.setCodecPreferences(sorted)
        } catch (e: Throwable) {
            Log.w(TAG, "setCodecPreferences no disponible", e)
        }

        pc.createOffer(object : SdpAdapter("media-offer") {
            override fun onCreateSuccess(desc: SessionDescription) = post {
                pc.setLocalDescription(SdpAdapter("media-local"), desc)
                signaling?.send("OFFER", v.peerId, JSONObject()
                    .put("sdp", JSONObject().put("type", "offer").put("sdp", desc.description))
                    .put("type", "media")
                    .put("connectionId", connId)
                    .put("metadata", JSONObject().put("kind", "screen"))
                    .put("browser", "android"))
            }
        }, MediaConstraints())
    }

    private fun onAnswer(src: String, payload: JSONObject) {
        val v = viewers[src] ?: return
        val connId = payload.optString("connectionId")
        if (connId != v.mediaConnId) return
        val pc = v.mediaPc ?: return
        val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
        pc.setRemoteDescription(object : SdpAdapter("media-remote") {
            override fun onSetSuccess() = post { flushPending(v, connId, pc) }
        }, SessionDescription(SessionDescription.Type.ANSWER, mungeBitrate(sdp)))
    }

    private fun onCandidate(src: String, payload: JSONObject) {
        val v = viewers[src] ?: return
        val connId = payload.optString("connectionId")
        val c = payload.optJSONObject("candidate") ?: return
        val cand = c.optString("candidate")
        if (cand.isEmpty()) return
        val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), cand)
        val pc = when (connId) {
            v.dataConnId -> v.dataPc
            v.mediaConnId -> v.mediaPc
            else -> null
        }
        if (pc == null || pc.remoteDescription == null) {
            v.pending.getOrPut(connId) { mutableListOf() }.add(ice)
        } else {
            pc.addIceCandidate(ice)
        }
    }

    private fun flushPending(v: Viewer, connId: String, pc: PeerConnection) {
        v.pending.remove(connId)?.forEach { pc.addIceCandidate(it) }
    }

    private fun createPc(v: Viewer, connId: String, kind: String): PeerConnection? {
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        return factory?.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = post {
                signaling?.send("CANDIDATE", v.peerId, JSONObject()
                    .put("candidate", JSONObject()
                        .put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex))
                    .put("type", kind)
                    .put("connectionId", connId))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = post {
                if (kind != "media" || viewers[v.peerId] !== v) return@post
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        v.connected = true
                        v.mediaPc?.let { tuneSender(it) }
                        updateViewers()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        cb.onStatus("No se pudo conectar con ${v.name} (¿falta TURN?)")
                        removeViewer(v.peerId)
                    }
                    PeerConnection.PeerConnectionState.CLOSED -> removeViewer(v.peerId)
                    else -> Unit
                }
            }

            override fun onDataChannel(dc: DataChannel) = post { v.channel = dc }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        })
    }

    /**
     * Añade x-google-start/min/max-bitrate a los códecs de vídeo de la respuesta remota
     * para que el codificador arranque con buena calidad en vez de ~300 kbps.
     */
    private fun mungeBitrate(sdp: String): String {
        val maxK = q.bitrate / 1000
        val startK = maxOf(MIN_BITRATE / 1000, maxK / 2)
        val minK = MIN_BITRATE / 1000
        val extra = "x-google-start-bitrate=$startK;x-google-min-bitrate=$minK;x-google-max-bitrate=$maxK"
        val lines = sdp.split("\r\n").toMutableList()
        val videoPts = mutableSetOf<String>()
        val withFmtp = mutableSetOf<String>()
        var inVideo = false
        for (l in lines) {
            if (l.startsWith("m=")) inVideo = l.startsWith("m=video")
            if (!inVideo) continue
            if (l.startsWith("a=rtpmap:")) {
                val pt = l.substringAfter("a=rtpmap:").substringBefore(' ')
                val codec = l.substringAfter(' ').substringBefore('/').uppercase()
                if (codec in setOf("H264", "VP8", "VP9", "AV1", "H265")) videoPts.add(pt)
            } else if (l.startsWith("a=fmtp:")) {
                withFmtp.add(l.substringAfter("a=fmtp:").substringBefore(' '))
            }
        }
        val out = ArrayList<String>(lines.size + 8)
        for (l in lines) {
            if (l.startsWith("a=fmtp:")) {
                val pt = l.substringAfter("a=fmtp:").substringBefore(' ')
                out.add(if (pt in videoPts && !l.contains("x-google-")) "$l;$extra" else l)
                continue
            }
            out.add(l)
            if (l.startsWith("a=rtpmap:")) {
                val pt = l.substringAfter("a=rtpmap:").substringBefore(' ')
                if (pt in videoPts && pt !in withFmtp) out.add("a=fmtp:$pt $extra")
            }
        }
        return out.joinToString("\r\n")
    }

    private fun tuneSender(pc: PeerConnection) {
        for (s in pc.senders) {
            if (s.track()?.kind() != "video") continue
            try {
                val p = s.parameters
                // Nunca bajar la resolución: en pantalla compartida se prefiere perder fps
                p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                for (e in p.encodings) {
                    e.maxBitrateBps = q.bitrate
                    e.minBitrateBps = MIN_BITRATE
                    e.maxFramerate = q.fps
                    e.scaleResolutionDownBy = 1.0
                }
                s.parameters = p
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron ajustar los parámetros", e)
            }
        }
    }

    private fun sendJson(v: Viewer, obj: JSONObject) {
        val dc = v.channel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        // peerjs con serialization 'json' espera bytes UTF-8
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray(Charsets.UTF_8)), true))
    }

    private fun removeViewer(peerId: String) {
        val v = viewers.remove(peerId) ?: return
        try { v.channel?.close() } catch (_: Exception) {}
        try { v.mediaPc?.close() } catch (_: Exception) {}
        try { v.dataPc?.close() } catch (_: Exception) {}
        try { v.channel?.dispose() } catch (_: Exception) {}
        try { v.mediaPc?.dispose() } catch (_: Exception) {}
        try { v.dataPc?.dispose() } catch (_: Exception) {}
        updateViewers()
    }

    private fun updateViewers() {
        val n = viewers.values.count { it.connected }
        cb.onViewers(n)
        if (signaling != null && code.isNotEmpty()) {
            cb.onStatus(if (n > 0) "Compartiendo con ${viewers.values.filter { it.connected }.joinToString { it.name }}" else "Esperando espectadores")
        }
    }

    // -------------------------------------------------------------------- parar

    fun stop() = post {
        if (stopped) return@post
        stopped = true
        for (v in viewers.values) {
            sendJson(v, JSONObject().put("t", "ended"))
            signaling?.send("LEAVE", v.peerId, null)
        }
        Thread.sleep(200)
        for (id in viewers.keys.toList()) removeViewer(id)
        signaling?.close(); signaling = null
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { track?.dispose() } catch (_: Exception) {}
        try { source?.dispose() } catch (_: Exception) {}
        try { helper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        capturer = null; track = null; source = null; helper = null; factory = null; egl = null
        rtc.shutdown()
    }
}

/**
 * Pasa los fotogramas al VideoSource convertidos a I420 y, si la pantalla lleva
 * quieta [idleMs], reenvía el último para que el codificador vaya afinando la calidad.
 */
private class RepeatingObserver(
    private val target: CapturerObserver,
    private val handler: Handler,
    private val idleMs: Long = 150,
) : CapturerObserver {
    private var last: VideoFrame.I420Buffer? = null
    private var lastRotation = 0
    private var lastNs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val b = last
            val now = System.nanoTime()
            if (b != null && now - lastNs >= idleMs * 1_000_000L) {
                b.retain()
                val f = VideoFrame(b, lastRotation, now)
                target.onFrameCaptured(f)
                f.release()
            }
            handler.postDelayed(this, idleMs)
        }
    }

    override fun onCapturerStarted(success: Boolean) {
        target.onCapturerStarted(success)
        running = success
        handler.removeCallbacks(tick)
        if (success) handler.postDelayed(tick, idleMs)
    }

    override fun onCapturerStopped() {
        running = false
        handler.removeCallbacks(tick)
        last?.release(); last = null
        target.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val i420 = frame.buffer.toI420() ?: return
        val f = VideoFrame(i420, frame.rotation, frame.timestampNs)
        target.onFrameCaptured(f)
        last?.release()
        last = i420            // nos quedamos con la referencia de toI420()
        lastRotation = frame.rotation
        lastNs = System.nanoTime()
    }
}

open class SdpAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.e("SDP", "$tag create: $error") }
    override fun onSetFailure(error: String?) { Log.e("SDP", "$tag set: $error") }
}
