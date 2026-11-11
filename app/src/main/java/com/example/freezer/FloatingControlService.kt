package com.example.freezer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * Muestra un único botón flotante (Freezer).
 * - Un dedo: lo arrastra. Un toque: corta la red.
 * - La red vuelve sola a los 3 segundos (con cuenta regresiva 3.0, 2.9, ...)
 *   o antes, si tocás el botón de nuevo.
 * - Dos dedos (pellizco): lo agranda o lo achica. El tamaño queda guardado.
 */
class FloatingControlService : Service() {

    companion object {
        @Volatile
        var isRunning = false
            private set

        const val PREFS = "freezer_prefs"
        const val KEY_SCALE = "button_scale"
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.5f
        private const val BASE_SIZE_DP = 52
        private const val BASE_PADDING_DP = 12

        /** Cuánto dura el corte antes de que la red vuelva sola. */
        private const val FREEZE_DURATION_MS = 3000L
        private const val TICK_MS = 50L
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var scale = 1f

    private val handler = Handler(Looper.getMainLooper())
    private var button: ImageButton? = null
    private var countdownText: TextView? = null
    private var countdownEnd = 0L

    // Cuando la barra del menú (o el pellizco) cambia el tamaño guardado, el botón se ajusta al instante.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_SCALE) {
            val newScale = prefs.getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
            if (abs(newScale - scale) > 0.001f) resizeTo(newScale)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scale = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        setupButton()
        windowManager?.addView(bubbleView, params)

        getSharedPreferences(PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)

        // Deja la VPN conectada (sin cortar nada) desde que aparece el botón.
        sendVpn(MyVpnService.ACTION_START)
    }

    private fun sendVpn(action: String) {
        startService(Intent(this, MyVpnService::class.java).apply { this.action = action })
    }

    /** Tamaño actual del botón en píxeles. */
    private fun currentSizePx() = (dp(BASE_SIZE_DP) * scale).toInt()

    /** Aplica el tamaño (del botón, del padding del ícono y del número) según la escala. */
    private fun applySize(btn: ImageButton) {
        val size = currentSizePx()
        val pad = (dp(BASE_PADDING_DP) * scale).toInt()
        btn.layoutParams = FrameLayout.LayoutParams(size, size)
        btn.setPadding(pad, pad, pad, pad)
        countdownText?.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.30f)
    }

    private fun setupButton() {
        val btn = bubbleView?.findViewById<ImageButton>(R.id.btnFreezer) ?: return
        button = btn
        countdownText = bubbleView?.findViewById(R.id.tvCountdown)
        btn.setColorFilter(Color.parseColor("#FF5252"))
        applySize(btn)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isMoving = false
        var wasScaling = false
        val slop = dp(8)

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val oldSize = currentSizePx()
                    scale = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    applySize(btn)
                    // Mantiene el centro del botón en su lugar mientras crece o se achica.
                    val diff = (currentSizePx() - oldSize) / 2
                    params.x -= diff
                    params.y -= diff
                    windowManager?.updateViewLayout(bubbleView, params)
                    return true
                }
            }
        )

        btn.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isMoving = false
                    wasScaling = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Entró un segundo dedo: es un pellizco, no un arrastre ni un toque.
                    wasScaling = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (wasScaling || event.pointerCount > 1) return@setOnTouchListener true
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) isMoving = true
                    if (isMoving) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (wasScaling) {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putFloat(KEY_SCALE, scale).apply()
                    } else if (!isMoving) {
                        v.performClick()
                        toggleFreezer()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** Cambia el tamaño del botón manteniendo su centro en el mismo lugar. */
    private fun resizeTo(newScale: Float) {
        val btn = button ?: return
        val oldSize = currentSizePx()
        scale = newScale
        applySize(btn)
        val diff = (currentSizePx() - oldSize) / 2
        params.x -= diff
        params.y -= diff
        windowManager?.updateViewLayout(bubbleView, params)
    }

    private fun toggleFreezer() {
        sendVpn(MyVpnService.ACTION_TOGGLE_FREEZER)
        // Pequeña espera para que la VPN cambie de estado antes de actualizar el botón.
        handler.postDelayed({ refreshUi() }, 150)
    }

    private fun refreshUi() {
        if (MyVpnService.isFrozen) showFrozen() else showNormal()
    }

    /** Red cortada: número verde con la cuenta regresiva en lugar del ícono. */
    private fun showFrozen() {
        val btn = button ?: return
        btn.setImageDrawable(null)
        countdownText?.visibility = View.VISIBLE
        countdownEnd = SystemClock.elapsedRealtime() + FREEZE_DURATION_MS
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Red normal: pausa roja. */
    private fun showNormal() {
        val btn = button ?: return
        handler.removeCallbacks(tick)
        countdownText?.visibility = View.GONE
        btn.setImageResource(android.R.drawable.ic_media_pause)
        btn.setColorFilter(Color.parseColor("#FF5252"))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!MyVpnService.isFrozen) {
                showNormal()
                return
            }
            val remaining = countdownEnd - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                // Se acabó el tiempo: la red vuelve sola.
                sendVpn(MyVpnService.ACTION_UNFREEZE)
                handler.postDelayed({ refreshUi() }, 150)
                return
            }
            countdownText?.text = String.format(Locale.US, "%.1f", remaining / 1000.0)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startAsForeground() {
        val channelId = "floating_control"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Botón flotante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Botón Freezer activo")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        // Al ocultar el botón se apaga la VPN, y con eso la red vuelve a la normalidad.
        sendVpn(MyVpnService.ACTION_STOP)
        bubbleView?.let { windowManager?.removeView(it) }
        isRunning = false
        super.onDestroy()
    }
}
