package com.example.byedpiwifi

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.btn_toggle)

        toggleButton.setOnClickListener {
            val intent = Intent(this, VpnService::class.java)
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                startActivityForResult(prepare, 1)
            } else {
                startService(intent)
                Toast.makeText(this, "VPN запущен", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val intent = Intent(this, VpnService::class.java)
            startService(intent)
            Toast.makeText(this, "VPN запущен", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Разрешение VPN не получено", Toast.LENGTH_SHORT).show()
        }
    }
}
