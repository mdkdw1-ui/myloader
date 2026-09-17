package com.myvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startVpn()
            } else {
                statusView.text = "VPN 권한이 거부되었습니다"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusView = TextView(this).apply {
            text = "VPN 중지됨"
            textSize = 18f
        }

        val startBtn = Button(this).apply {
            text = "VPN 시작"
            setOnClickListener { requestVpn() }
        }

        val stopBtn = Button(this).apply {
            text = "VPN 중지"
            setOnClickListener {
                startService(Intent(this@MainActivity, MyVpnService::class.java)
                    .setAction(MyVpnService.ACTION_STOP))
                statusView.text = "VPN 중지됨"
            }
        }

        layout.addView(statusView)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, MyVpnService::class.java)
            .setAction(MyVpnService.ACTION_START))
        statusView.text = "VPN 실행 중"
    }
}
