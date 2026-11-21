package com.ahmed.voicechanger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnShizuku: Button
    private lateinit var btnToggleService: Button
    private var isServiceRunning = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                statusText.text = "حالة Shizuku: متصل ومفعل بنجاح ✅"
            } else {
                statusText.text = "حالة Shizuku: تم رفض الإذن ❌"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // إنشاء واجهة برمجية بسيطة وسريعة بدون ملفات XML معقدة
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            text = "جاري فحص Shizuku..."
            textSize = 18f
            setPadding(0, 0, 0, 32)
        }

        btnShizuku = Button(this).apply {
            text = "1. ربط صلاحيات Shizuku"
            setOnClickListener { checkShizuku() }
        }

        btnToggleService = Button(this).apply {
            text = "2. تشغيل تغيير الصوت"
            setOnClickListener { toggleVoiceService() }
        }

        layout.addView(statusText)
        layout.addView(btnShizuku)
        layout.addView(btnToggleService)
        setContentView(layout)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        checkMicPermission()
        checkShizuku()
    }

    private fun checkMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
        }
    }

    private fun checkShizuku() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                statusText.text = "حالة Shizuku: متصل ومفعل بنجاح ✅"
            } else {
                Shizuku.requestPermission(1001)
            }
        } else {
            statusText.text = "حالة Shizuku: غير شغال في الخلفية ⚠️"
        }
    }

    private fun toggleVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (!isServiceRunning) {
            startService(intent)
            isServiceRunning = true
            btnToggleService.text = "إيقاف تغيير الصوت ⏹️"
            Toast.makeText(this, "تم تشغيل معالجة الصوت", Toast.LENGTH_SHORT).show()
        } else {
            stopService(intent)
            isServiceRunning = false
            btnToggleService.text = "2. تشغيل تغيير الصوت ▶️"
            Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }
}
