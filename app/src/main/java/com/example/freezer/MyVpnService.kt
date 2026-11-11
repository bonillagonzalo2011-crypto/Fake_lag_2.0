package com.example.freezer

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * VPN que queda siempre encendida mientras el botón flotante está visible.
 *
 * - Normal: la VPN está conectada pero no captura tráfico (solo una ruta
 *   de relleno), así que la red funciona con total normalidad.
 * - Congelado: la VPN captura todo el tráfico y no lo reenvía, así que
 *   nada sale a internet.
 *
 * Cada toque reemplaza el túnel por el otro, sin apagar la VPN.
 */
class MyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.freezer.START_VPN"
        const val ACTION_STOP = "com.example.freezer.STOP_VPN"
        const val ACTION_TOGGLE_FREEZER = "com.example.freezer.TOGGLE_FREEZER"

        /** Devuelve la red solo si estaba cortada (no hace nada si ya está normal). */
        const val ACTION_UNFREEZE = "com.example.freezer.UNFREEZE"

        @Volatile
        var isFrozen = false
            private set

        /** Avisa a la pantalla del menú cuando cambia el estado del Freezer. */
        @Volatile
        var onStateChanged: ((Boolean) -> Unit)? = null
    }

    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (tunnel == null) openTunnel(frozen = false)
            ACTION_TOGGLE_FREEZER -> {
                if (tunnel == null) openTunnel(frozen = true) else openTunnel(frozen = !isFrozen)
            }
            ACTION_UNFREEZE -> if (isFrozen) openTunnel(frozen = false)
            ACTION_STOP -> {
                closeTunnel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Abre el túnel nuevo y recién después cierra el anterior, para que no haya hueco. */
    private fun openTunnel(frozen: Boolean) {
        val builder = Builder()
            .setSession("Freezer")
            .addAddress("10.0.0.2", 32)

        if (frozen) {
            builder.addRoute("0.0.0.0", 0)   // todo el IPv4 entra al túnel y se descarta
            builder.addAddress("fd00::2", 128)
            builder.addRoute("::", 0)        // todo el IPv6 entra al túnel y se descarta
        } else {
            builder.addRoute("192.0.2.1", 32) // ruta de relleno: el tráfico real no pasa por acá
        }

        val newTunnel = builder.establish() ?: return // null si falta el permiso de VPN
        val oldTunnel = tunnel
        tunnel = newTunnel
        oldTunnel?.close()

        isFrozen = frozen
        onStateChanged?.invoke(frozen)
    }

    private fun closeTunnel() {
        tunnel?.close()
        tunnel = null
        isFrozen = false
        onStateChanged?.invoke(false)
    }

    override fun onRevoke() {
        closeTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }
}
