package com.ahmed.voicechanger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class VoiceService : Service() {
    private val shifter = PitchShifter()
    @Volatile private var loop = false
    @Volatile private var isRecording = false
    @Volatile private var currentSampleRate = 16000
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var bluetoothRouted = false

    private val recordLock = Any()
    private var pcmStream: FileOutputStream? = null
    private var tempPcmFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_RECORD -> {
                if (isRecording) stopWavRecording() else startWavRecording()
                updateNotification()
                return START_STICKY
            }
        }
        startInForeground()
        if (!loop) startAudio()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWavRecording()
        stopAudio()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_TOGGLE_RECORD
        }
        val pendingToggle = PendingIntent.getService(
            this,
            101,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val actionTitle = if (isRecording) "⏹️ إيقاف وحفظ التسجيل" else "🔴 تسجيل الصوت المعدل"
        val statusText = if (isRecording) "جاري تسجيل الصوت المعدل..." else "تغيير الصوت شغال."

        @Suppress("DEPRECATION")
        val recordAction = Notification.Action.Builder(
            android.R.drawable.ic_btn_speak_now,
            actionTitle,
            pendingToggle,
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("نبرة")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(recordAction)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "نبرة", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification())
    }

    private fun startWavRecording() {
        synchronized(recordLock) {
            if (isRecording) return
            try {
                val file = File(cacheDir, "nabra_temp.pcm")
                if (file.exists()) file.delete()
                tempPcmFile = file
                pcmStream = FileOutputStream(file)
                isRecording = true
                showToast("بدأ تسجيل الصوت المعدل 🎙️")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
            }
        }
    }

    private fun stopWavRecording() {
        val fileToSave: File?
        val rateToSave: Int
        synchronized(recordLock) {
            if (!isRecording) return
            isRecording = false
            try {
                pcmStream?.flush()
                pcmStream?.close()
            } catch (_: Exception) {
            }
            pcmStream = null
            fileToSave = tempPcmFile
            tempPcmFile = null
            rateToSave = currentSampleRate
        }

        if (fileToSave != null && fileToSave.exists() && fileToSave.length() > 0) {
            Thread {
                savePcmAsWav(fileToSave, rateToSave)
            }.start()
        }
    }

    private fun savePcmAsWav(pcmFile: File, sampleRate: Int) {
        try {
            val pcmSize = pcmFile.length().toInt()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "nabra_$timeStamp.wav"

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Nabra")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(createWavHeader(pcmSize, sampleRate))
                    FileInputStream(pcmFile).use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
                showToast("تم الحفظ في Music/Nabra/$fileName ✅")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV", e)
            showToast("حصل خطأ أثناء حفظ الملف ❌")
        } finally {
            pcmFile.delete()
        }
    }

    private fun createWavHeader(pcmDataSize: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + pcmDataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // Mono
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(2) // Block align
        buffer.putShort(16) // Bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmDataSize)
        return buffer.array()
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
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
            currentSampleRate = rate
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
            // استخدام VOICE_RECOGNITION يسمح بمشاركة المايك مع التطبيقات التانية بدون حظر
            val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

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
            val byteBuffer = ByteArray(frames * 2)
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
                        val sample = (shifted[i] * 0.85f * 32767f)
                            .toInt()
                            .coerceIn(-32768, 32767)
                        output[i] = sample.toShort()
                        byteBuffer[i * 2] = (sample and 0xFF).toByte()
                        byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    playback.write(output, 0, read)

                    if (isRecording) {
                        synchronized(recordLock) {
                            try {
                                pcmStream?.write(byteBuffer, 0, read * 2)
                            } catch (_: Exception) {
                            }
                        }
                    }
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
        // إبقاء الوضع MODE_NORMAL لمنع حظر مسجل الصوت والتطبيقات الأخرى
        manager.mode = AudioManager.MODE_NORMAL
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
        manager.mode = AudioManager.MODE_NORMAL
        manager.startBluetoothSco()
        val connected = latch.await(3, TimeUnit.SECONDS)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        if (!connected) {
            manager.stopBluetoothSco()
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
        const val ACTION_TOGGLE_RECORD = "com.ahmed.voicechanger.TOGGLE_RECORD"
    }
}
