package es.blockhost.screenlink

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Cliente mínimo del protocolo del servidor PeerJS (0.peerjs.com),
 * compatible con la librería peerjs 1.5.x que usa la web.
 */
class PeerJsSignaling(
    private val id: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onIdTaken()
        fun onMessage(type: String, src: String, payload: JSONObject?)
        fun onClosed(reason: String)
    }

    companion object {
        private const val TAG = "PeerJS"
        private const val HOST = "0.peerjs.com"
        private const val KEY = "peerjs"
        private const val VERSION = "1.5.4"
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        fun randomToken(len: Int = 10): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val r = SecureRandom()
            return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    @Volatile private var closed = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            ws?.send("{\"type\":\"HEARTBEAT\"}")
            main.postDelayed(this, 5000)
        }
    }

    fun connect() {
        val url = "wss://$HOST:443/peerjs?key=$KEY&id=$id&token=${randomToken()}&version=$VERSION"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.removeCallbacks(heartbeat)
                main.post(heartbeat)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                when (val type = msg.optString("type")) {
                    "OPEN" -> listener.onOpen()
                    "ID-TAKEN" -> listener.onIdTaken()
                    "ERROR", "INVALID-KEY" -> {
                        Log.w(TAG, "Error del servidor: $text")
                        listener.onClosed(msg.optJSONObject("payload")?.optString("msg") ?: type)
                    }
                    "HEARTBEAT" -> Unit
                    else -> listener.onMessage(type, msg.optString("src"), msg.optJSONObject("payload"))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.removeCallbacks(heartbeat)
                if (!closed) listener.onClosed("cerrado ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.removeCallbacks(heartbeat)
                Log.w(TAG, "Fallo WebSocket", t)
                if (!closed) listener.onClosed(t.message ?: "fallo de red")
            }
        })
    }

    fun send(type: String, dst: String, payload: JSONObject?) {
        val msg = JSONObject().put("type", type).put("dst", dst)
        if (payload != null) msg.put("payload", payload)
        ws?.send(msg.toString())
    }

    fun close() {
        closed = true
        main.removeCallbacks(heartbeat)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }
}
