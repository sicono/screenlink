package es.blockhost.screenlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

data class State(
    val running: Boolean = false,
    val code: String = "",
    val status: String = "Listo para compartir",
    val viewers: Int = 0,
)

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    @Volatile var state = State()
        private set

    fun update(f: (State) -> State) = main.post {
        state = f(state)
        listeners.forEach { it(state) }
    }

    fun add(l: (State) -> Unit) { listeners.add(l); l(state) }
    fun remove(l: (State) -> Unit) { listeners.remove(l) }
}

class ScreenShareService : Service() {

    companion object {
        const val ACTION_START = "es.blockhost.screenlink.START"
        const val ACTION_STOP = "es.blockhost.screenlink.STOP"
        const val EXTRA_DATA = "data"
        const val EXTRA_QUALITY = "quality"
        private const val CHANNEL = "screenlink"
        private const val NOTIF_ID = 42
    }

    private val main = Handler(Looper.getMainLooper())
    private var host: ScreenHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> {
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null) { shutdown(); return START_NOT_STICKY }
                val quality = Quality.ALL.getOrElse(intent.getIntExtra(EXTRA_QUALITY, 0)) { Quality.ALL[0] }

                createChannel()
                val n = buildNotification("Iniciando…")
                // En Android 14+ hay que entrar en primer plano ANTES de obtener la MediaProjection
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIF_ID, n)
                }

                host?.stop()
                Bus.update { State(running = true, status = "Iniciando…") }
                host = ScreenHost(this, data, quality, object : ScreenHost.Callback {
                    override fun onCode(code: String) {
                        Bus.update { it.copy(code = code) }
                        main.post { notify("Código: $code") }
                    }
                    override fun onStatus(status: String) { Bus.update { it.copy(status = status) } }
                    override fun onViewers(count: Int) { Bus.update { it.copy(viewers = count) } }
                    override fun onProjectionStopped() { main.post { shutdown() } }
                }).also { it.start() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.onRotation()
    }

    private fun shutdown() {
        host?.stop()
        host = null
        Bus.update { State() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        host?.stop()
        host = null
        Bus.update { State() }
        super.onDestroy()
    }

    // ------------------------------------------------------------ notificación

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Compartir pantalla", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ScreenLink · compartiendo pantalla")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Dejar de compartir", stop).build())
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
