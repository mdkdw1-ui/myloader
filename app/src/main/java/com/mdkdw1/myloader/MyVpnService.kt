package com.mdkdw1.myloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification())
        if (vpnInterface == null) vpnInterface = establishVpn()
        return START_STICKY
    }

    private fun establishVpn(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("MyLoader VPN")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .establish()
    } catch (e: Exception) { e.printStackTrace(); null }

    private fun buildNotification(): Notification {
        val ch = "myloader_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(ch, "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, ch) else Notification.Builder(this)
        return b.setContentTitle("MyLoader VPN")
            .setContentText("VPN 연결 활성화됨")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
