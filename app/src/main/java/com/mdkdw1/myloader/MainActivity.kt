package com.mdkdw1.myloader

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private var vpnReady = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vpnReady = result.resultCode == Activity.RESULT_OK
        statusText.text = if (vpnReady) "VPN 권한 승인됨" else "VPN 권한 거부됨"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        findViewById<Button>(R.id.startBtn).setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) launcher.launch(intent)
            else { vpnReady = true; startVpn() }
            if (vpnReady) startVpn()
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            stopService(Intent(this, MyVpnService::class.java))
            statusText.text = "VPN 중지됨"
        }
    }

    private fun startVpn() {
        startForegroundService(Intent(this, MyVpnService::class.java))
        statusText.text = "VPN 시작됨"
    }
}
