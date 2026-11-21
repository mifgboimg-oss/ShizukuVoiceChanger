package com.ahmed.voicechanger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.app.Service
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class VoiceService : Service() {
    private val shifter = PitchShifter()
    @Volatile private var loop = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var bluetoothRouted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!loop) startAudio()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "نبرة", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("نبرة")
            .setContentText("تغيير الصوت شغال.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startAudio() {
        loop = true
        VoiceConfig.running = true
        worker = Thread({
            val bluetooth = openBluetoothRoute()
            VoiceConfig.route = if (bluetooth) {
                "السماعة البلوتوث متصلة."
            } else {
                "مايك التليفون. وصّل السماعة قبل التشغيل لو عايزها."
            }
            val rate = pickRate(bluetooth)
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) {
                Log.e(TAG, "No input rate")
                stopSelf()
                return@Thread
            }
            val frames = 512
            val source = if (bluetooth) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            }
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, frames * 4))
                    .build()
            } catch (error: SecurityException) {
                Log.e(TAG, "Mic permission missing", error)
                stopSelf()
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                closeBluetoothRoute()
                stopSelf()
                return@Thread
            }
            val playMin = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val usage = if (bluetooth) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val playback = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(playMin, frames * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            recorder = record
            player = playback
            val input = ShortArray(frames)
            val floats = FloatArray(frames)
            val shifted = FloatArray(frames)
            val output = ShortArray(frames)
            try {
                record.startRecording()
                playback.play()
                while (loop) {
                    val read = record.read(input, 0, frames)
                    if (read <= 0) continue
                    for (i in 0 until read) floats[i] = input[i] / 32768f
                    val ratio = 2.0.pow(VoiceConfig.semitones / 12.0).toFloat()
                    shifter.process(floats, shifted, ratio)
                    for (i in 0 until read) {
                        output[i] = (shifted[i] * 0.85f * 32767f)
                            .toInt()
                            .coerceIn(-32768, 32767)
                            .toShort()
                    }
                    playback.write(output, 0, read)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Audio loop stopped", error)
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                try {
                    playback.stop()
                } catch (_: Exception) {
                }
                playback.release()
                closeBluetoothRoute()
            }
        }, "nabra-audio").also { it.start() }
    }

    private fun openBluetoothRoute(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        return if (Build.VERSION.SDK_INT >= 31) openModernBluetooth(manager) else openLegacyBluetooth(manager)
    }

    private fun openModernBluetooth(manager: AudioManager): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val sco = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: return false
        val latch = CountDownLatch(1)
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device != null && device.id == sco.id) latch.countDown()
        }
        manager.addOnCommunicationDeviceChangedListener(mainExecutor, listener)
        val accepted = try {
            manager.setCommunicationDevice(sco)
        } catch (error: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", error)
            false
        }
        if (!accepted) {
            manager.removeOnCommunicationDeviceChangedListener(listener)
            return false
        }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        latch.await(2, TimeUnit.SECONDS)
        manager.removeOnCommunicationDeviceChangedListener(listener)
        bluetoothRouted = true
        return true
    }

    @Suppress("DEPRECATION")
    private fun openLegacyBluetooth(manager: AudioManager): Boolean {
        if (!manager.isBluetoothScoAvailableOffCall) return false
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) latch.countDown()
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        registerReceiver(receiver, filter)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.startBluetoothSco()
        val connected = latch.await(3, TimeUnit.SECONDS)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        if (!connected) {
            manager.stopBluetoothSco()
            manager.mode = AudioManager.MODE_NORMAL
            return false
        }
        manager.isBluetoothScoOn = true
        bluetoothRouted = true
        return true
    }

    private fun closeBluetoothRoute() {
        val manager = audioManager ?: return
        if (!bluetoothRouted) return
        bluetoothRouted = false
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
            }
            manager.mode = AudioManager.MODE_NORMAL
        } catch (error: Exception) {
            Log.e(TAG, "Could not close Bluetooth route", error)
        }
    }

    private fun stopAudio() {
        loop = false
        VoiceConfig.running = false
        try {
            worker?.join(2500)
        } catch (_: InterruptedException) {
        }
        worker = null
        recorder = null
        player = null
        closeBluetoothRoute()
    }

    private fun pickRate(bluetooth: Boolean): Int {
        val candidates = if (bluetooth) intArrayOf(16000, 8000) else intArrayOf(48000, 44100, 16000)
        for (rate in candidates) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min > 0) return rate
        }
        return 16000
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val CHANNEL = "nabra"
        private const val NOTIF_ID = 7
    }
}
