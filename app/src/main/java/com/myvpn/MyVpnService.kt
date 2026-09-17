package com.myvpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class MyVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return

        // VPN 인터페이스 구성 (예시: 로컬 터널 주소)
        val builder = Builder()
            .setSession("MyVPN")
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .setMtu(1500)

        tunInterface = builder.establish() ?: return
        running = true

        val fd = tunInterface!!.fileDescriptor
        worker = Thread {
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buffer = ByteArray(32767)
            // TODO: 여기서 실제 패킷을 원격 서버로 포워딩 (WireGuard/OpenVPN 등)
            while (running) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        // 예시: 패킷을 그대로 되돌려보냄 (실제로는 터널로 전송)
                        output.write(buffer, 0, len)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.also { it.start() }
    }

    private fun stopVpn() {
        running = false
        worker?.interrupt()
        worker = null
        tunInterface?.close()
        tunInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.myvpn.START"
        const val ACTION_STOP  = "com.myvpn.STOP"
    }
}
