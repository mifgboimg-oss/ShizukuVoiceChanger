package com.ahmed.voicechanger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var pitchText: TextView
    private lateinit var toggleButton: Button

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQ_SHIZUKU) return@OnRequestPermissionResultListener
            statusText.text = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Shizuku متصل."
            } else {
                "إذن Shizuku اترفض."
            }
        }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        statusText.text = "Shizuku وقف. افتح التطبيق تاني."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            setPadding(48, 72, 48, 48)
        }
        val title = TextView(this).apply {
            text = "نبرة"
            textSize = 32f
        }
        statusText = TextView(this).apply {
            text = "جاري فحص Shizuku..."
            textSize = 16f
        }
        pitchText = TextView(this).apply { textSize = 16f }
        val slider = SeekBar(this).apply {
            max = 12
            progress = VoiceConfig.semitones.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    VoiceConfig.semitones = progress.toFloat()
                    renderPitch()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val shizukuButton = Button(this).apply {
            text = "ربط Shizuku"
            setOnClickListener { refreshShizuku() }
        }
        toggleButton = Button(this).apply { setOnClickListener { toggleService() } }
        val hint = TextView(this).apply {
            text = "الصوت المتغير يطلع على السماعة. البس سماعة رأس عشان مفيش صفير. مكالمة الشبكة العادية مش بتتغير من هنا، حتى لو Shizuku متصل."
            textSize = 14f
        }
        val gap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 28 }
        listOf(title, statusText, pitchText, slider, shizukuButton, toggleButton, hint).forEach {
            root.addView(it, gap)
        }
        setContentView(root)
        renderPitch()
        syncToggle()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        refreshShizuku()
    }

    override fun onResume() {
        super.onResume()
        syncToggle()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        super.onDestroy()
    }

    private fun renderPitch() {
        pitchText.text = "رفع الطبقة: +${VoiceConfig.semitones.toInt()}"
    }

    private fun syncToggle() {
        toggleButton.text = if (VoiceConfig.running) "إيقاف الصوت" else "تشغيل تغيير الصوت"
    }

    private fun refreshShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                statusText.text = "Shizuku مش شغال. افتحه وشغّله بالتصحيح اللاسلكي."
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                statusText.text = "Shizuku متصل."
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
        } catch (_: Throwable) {
            statusText.text = "مش قادر أوصل لـ Shizuku."
        }
    }

    private fun toggleService() {
        if (VoiceConfig.running) {
            stopService(Intent(this, VoiceService::class.java))
            VoiceConfig.running = false
            syncToggle()
            return
        }
        if (!hasAudioPermission()) {
            requestNeededPermissions()
            Toast.makeText(this, "اقبل إذن المايك الأول", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, VoiceService::class.java))
        VoiceConfig.running = true
        syncToggle()
    }

    private fun hasAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_AUDIO)
    }

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_AUDIO = 1002
    }
}
