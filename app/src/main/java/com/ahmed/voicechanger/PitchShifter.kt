package com.ahmed.voicechanger

class PitchShifter(private val maxDelay: Int = 2048) {
    private val buffer = FloatArray(maxDelay * 4)
    private var write = 0
    private val delay = floatArrayOf(0f, maxDelay / 2f)
    private val fade = 256f

    fun process(input: FloatArray, output: FloatArray, ratio: Float) {
        if (ratio < 1.02f && ratio > 0.98f) {
            input.copyInto(output)
            return
        }
        val length = buffer.size
        val span = maxDelay.toFloat()
        for (i in input.indices) {
            buffer[write] = input[i]
            var mixed = 0f
            var weightSum = 0f
            for (tap in 0..1) {
                var d = delay[tap]
                var read = write - d
                while (read < 0f) read += length
                val i0 = read.toInt() % length
                val frac = read - read.toInt()
                val i1 = (i0 + 1) % length
                val sample = buffer[i0] * (1f - frac) + buffer[i1] * frac
                val window = when {
                    d < fade -> d / fade
                    d > span - fade -> (span - d) / fade
                    else -> 1f
                }.coerceIn(0f, 1f)
                mixed += sample * window
                weightSum += window
                d += 1f - ratio
                if (d < 0f) d += span
                if (d >= span) d -= span
                delay[tap] = d
            }
            output[i] = if (weightSum > 0f) mixed / weightSum else 0f
            write = (write + 1) % length
        }
    }
}
