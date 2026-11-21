package com.ahmed.voicechanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.math.pow

class VoiceService : Service() {
    private val shifter = PitchShifter()
    @Volatile private var loop = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

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
            .setContentText("تغيير الصوت شغال. البس سماعة رأس.")
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
        val rate = pickRate()
        val min = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            Log.e(TAG, "No input rate")
            stopSelf()
            return
        }
        val frames = 512
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
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
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            stopSelf()
            return
        }
        val playMin = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val playback = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        loop = true
        VoiceConfig.running = true
        worker = Thread({
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
            }
        }, "nabra-audio").also { it.start() }
    }

    private fun stopAudio() {
        loop = false
        VoiceConfig.running = false
        try {
            worker?.join(800)
        } catch (_: InterruptedException) {
        }
        worker = null
        recorder = null
        player = null
    }

    private fun pickRate(): Int {
        for (rate in intArrayOf(48000, 44100, 16000)) {
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
