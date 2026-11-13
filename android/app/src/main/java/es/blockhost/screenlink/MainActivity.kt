package es.blockhost.screenlink

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        const val WEB_URL = "https://sicono.github.io/screenlink/"
        private val BG = Color.parseColor("#0B0D12")
        private val PANEL = Color.parseColor("#141821")
        private val LINE = Color.parseColor("#262C3D")
        private val TEXT = Color.parseColor("#E8EBF2")
        private val MUTED = Color.parseColor("#8A93A8")
        private val ACCENT = Color.parseColor("#3DD68C")
        private val DANGER = Color.parseColor("#FF5D6C")
    }

    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var viewers: TextView
    private lateinit var spinner: Spinner
    private lateinit var startBtn: Button
    private lateinit var shareBtn: Button

    private val listener: (State) -> Unit = { render(it) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "ScreenLink"
            setTextColor(TEXT); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Comparte la pantalla de este móvil con cualquier PC, desde cualquier red."
            setTextColor(MUTED); textSize = 14f
            setPadding(0, dp(4), 0, dp(20))
        })

        // Tarjeta del código
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable().apply {
                setColor(PANEL); cornerRadius = dp(16).toFloat(); setStroke(dp(1), LINE)
            }
        }
        status = TextView(this).apply { setTextColor(MUTED); textSize = 14f; gravity = Gravity.CENTER }
        code = TextView(this).apply {
            setTextColor(ACCENT); textSize = 52f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { copyCode() }
        }
        viewers = TextView(this).apply { setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER }
        val hint = TextView(this).apply {
            text = "En el PC abre  sicono.github.io/screenlink  y escribe el código."
            setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(status); card.addView(code); card.addView(viewers); card.addView(hint)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Calidad"
            setTextColor(MUTED); textSize = 13f
            setPadding(dp(2), dp(22), 0, dp(6))
        })
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Quality.ALL.map { it.label })
            background = GradientDrawable().apply {
                setColor(PANEL); cornerRadius = dp(12).toFloat(); setStroke(dp(1), LINE)
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setSelection(0)
        }
        root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        startBtn = makeButton("Empezar a compartir", ACCENT, Color.parseColor("#06210F")).apply {
            setOnClickListener { if (Bus.state.running) stopSharing() else requestStart() }
        }
        root.addView(startBtn, btnParams(18))

        shareBtn = makeButton("Enviar enlace al PC", PANEL, TEXT).apply {
            setOnClickListener { shareLink() }
        }
        root.addView(shareBtn, btnParams(10))

        root.addView(TextView(this).apply {
            text = "El vídeo va directo entre los dispositivos (P2P, cifrado). " +
                "Si no conecta usando datos móviles, puede que tu operadora necesite un servidor TURN."
            setTextColor(MUTED); textSize = 12f
            setPadding(dp(2), dp(20), dp(2), 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        })
    }

    private fun makeButton(label: String, bg: Int, fg: Int) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(14).toFloat(); setStroke(dp(1), LINE)
        }
        stateListAnimator = null
    }

    private fun btnParams(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
        topMargin = dp(top)
    }

    override fun onResume() { super.onResume(); Bus.add(listener) }
    override fun onPause() { Bus.remove(listener); super.onPause() }

    private fun render(s: State) {
        status.text = s.status
        code.text = if (s.code.isEmpty()) "— — —" else s.code.substring(0, 3) + " " + s.code.substring(3)
        viewers.text = if (s.running) "Espectadores conectados: ${s.viewers}" else "Pulsa «Empezar a compartir» para generar un código"
        spinner.isEnabled = !s.running
        startBtn.text = if (s.running) "Dejar de compartir" else "Empezar a compartir"
        (startBtn.background as? GradientDrawable)?.setColor(if (s.running) PANEL else ACCENT)
        startBtn.setTextColor(if (s.running) DANGER else Color.parseColor("#06210F"))
        shareBtn.visibility = if (s.code.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ acciones

    private fun requestStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) launchCapture() // la notificación es opcional
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, ScreenShareService::class.java)
            .setAction(ScreenShareService.ACTION_START)
            .putExtra(ScreenShareService.EXTRA_DATA, data)
            .putExtra(ScreenShareService.EXTRA_QUALITY, spinner.selectedItemPosition)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun stopSharing() {
        startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
    }

    private fun copyCode() {
        val c = Bus.state.code
        if (c.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Código", c))
        Toast.makeText(this, "Código copiado", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val c = Bus.state.code
        if (c.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Mira mi pantalla: $WEB_URL?c=$c")
        }
        startActivity(Intent.createChooser(send, "Enviar enlace"))
    }
}
