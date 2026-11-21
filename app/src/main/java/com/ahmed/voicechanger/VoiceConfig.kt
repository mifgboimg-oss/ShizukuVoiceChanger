package com.ahmed.voicechanger

object VoiceConfig {
    @Volatile var semitones: Float = 7f
    @Volatile var running: Boolean = false
    @Volatile var route: String = ""
}
