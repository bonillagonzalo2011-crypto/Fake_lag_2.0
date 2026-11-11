package com.example.freezer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.content.SharedPreferences
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Pantalla de menú. El interruptor "Freezer" muestra u oculta el botón flotante,
 * que vive en su propio servicio y se mueve independiente de esta pantalla.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchFreezer: SwitchCompat
    private lateinit var ivFreezer: ImageView
    private lateinit var seekSize: SeekBar
    private lateinit var tvSizeValue: TextView

    private val sizePrefs by lazy {
        getSharedPreferences(FloatingControlService.PREFS, MODE_PRIVATE)
    }

    // Si el tamaño cambia con el pellizco, la barra del menú lo sigue.
    private val sizeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FloatingControlService.KEY_SCALE) showSize(currentScale())
    }
    private var askedNotif = false
    private var askedOverlay = false
    private var askedVpn = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { nextStep() }
    private val overlaySettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextStep() }
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { nextStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchFreezer = findViewById(R.id.switchFreezer)
        ivFreezer = findViewById(R.id.ivFreezer)
        setupSizeSlider()
        switchFreezer.isChecked = FloatingControlService.isRunning
        updateFreezerIcon(MyVpnService.isFrozen)

        switchFreezer.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                askedNotif = false
                askedOverlay = false
                askedVpn = false
                nextStep()
            } else {
                stopService(Intent(this, FloatingControlService::class.java))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateFreezerIcon(MyVpnService.isFrozen)
        // Mantiene el ícono del menú igual al del botón flotante.
        MyVpnService.onStateChanged = { frozen -> runOnUiThread { updateFreezerIcon(frozen) } }
        showSize(currentScale())
        sizePrefs.registerOnSharedPreferenceChangeListener(sizeListener)
    }

    override fun onStop() {
        MyVpnService.onStateChanged = null
        sizePrefs.unregisterOnSharedPreferenceChangeListener(sizeListener)
        super.onStop()
    }

    private fun currentScale() = sizePrefs
        .getFloat(FloatingControlService.KEY_SCALE, 1f)
        .coerceIn(FloatingControlService.MIN_SCALE, FloatingControlService.MAX_SCALE)

    /** Muestra el tamaño en la barra (de 60% a 250%) y en el texto. */
    private fun showSize(scale: Float) {
        val pct = Math.round(scale * 100)
        seekSize.progress = pct - 60
        tvSizeValue.text = "$pct%"
    }

    private fun setupSizeSlider() {
        seekSize = findViewById(R.id.seekSize)
        tvSizeValue = findViewById(R.id.tvSizeValue)
        seekSize.max = 190 // 60% a 250%
        showSize(currentScale())

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val pct = progress + 60
                tvSizeValue.text = "$pct%"
                // Solo guarda cuando lo mueve la persona; el servicio ajusta el botón al instante.
                if (fromUser) sizePrefs.edit().putFloat(FloatingControlService.KEY_SCALE, pct / 100f).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    /** Congelado: play verde. Normal: pausa roja. */
    private fun updateFreezerIcon(frozen: Boolean) {
        if (frozen) {
            ivFreezer.setImageResource(android.R.drawable.ic_media_play)
            ivFreezer.setColorFilter(Color.parseColor("#00E676"))
        } else {
            ivFreezer.setImageResource(android.R.drawable.ic_media_pause)
            ivFreezer.setColorFilter(Color.parseColor("#FF5252"))
        }
    }

    /** Pide los permisos de a uno y, cuando están todos, muestra el botón flotante. */
    private fun nextStep() {
        if (!switchFreezer.isChecked) return

        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            if (askedOverlay) { switchFreezer.isChecked = false; return }
            askedOverlay = true
            overlaySettings.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            if (askedVpn) { switchFreezer.isChecked = false; return }
            askedVpn = true
            vpnPermission.launch(vpnIntent)
            return
        }

        startForegroundService(Intent(this, FloatingControlService::class.java))
    }
}
