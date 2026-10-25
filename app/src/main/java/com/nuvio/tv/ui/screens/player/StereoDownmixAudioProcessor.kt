package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lightweight multichannel (3..8 ch) to stereo downmix that runs on PCM already decoded by the
 * device's hardware/platform decoder. This lets downmix work with "Prefer device decoders", so
 * audio is not forced through FFmpeg in software (which also disabled tunneling and pushed AV1
 * video to the libgav1 software decoder, causing stutter on low-power devices like Fire TV).
 *
 * Channel orders follow Android/Media3 (WAVE) layouts. LFE is dropped. The center level can be
 * changed live. Normalization is computed with the center at its default level, so raising the
 * center boosts dialogue without lowering music/effects (samples are clamped).
 */
internal class StereoDownmixAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var normalize: Boolean = true

    @Volatile
    private var centerGain: Float = 1f

    fun configure(enabled: Boolean, normalize: Boolean) {
        this.enabled = enabled
        this.normalize = normalize
    }

    fun isEnabled(): Boolean = enabled

    fun setCenterMixLevelDb(db: Int) {
        centerGain = 10.0.pow(db / 20.0).toFloat()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!enabled) return AudioProcessor.AudioFormat.NOT_SET
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val channels = inputAudioFormat.channelCount
        if (channels !in 3..8) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val inChannels = inputAudioFormat.channelCount
        val inFrameBytes = inputAudioFormat.bytesPerFrame
        val frames = inputBuffer.remaining() / inFrameBytes
        val outputBuffer = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        val (left, right) = buildCoefficients(inChannels)

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                val samples = FloatArray(inChannels)
                repeat(frames) {
                    for (c in 0 until inChannels) samples[c] = inputBuffer.short.toFloat()
                    outputBuffer.putShort(mix(samples, left).toPcm16())
                    outputBuffer.putShort(mix(samples, right).toPcm16())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val samples = FloatArray(inChannels)
                repeat(frames) {
                    for (c in 0 until inChannels) samples[c] = inputBuffer.float
                    outputBuffer.putFloat(mix(samples, left).coerceIn(-1f, 1f))
                    outputBuffer.putFloat(mix(samples, right).coerceIn(-1f, 1f))
                }
            }
        }
        // Skip any trailing partial frame.
        inputBuffer.position(inputBuffer.limit())
        outputBuffer.flip()
    }

    private fun mix(samples: FloatArray, coefficients: FloatArray): Float {
        var sum = 0f
        for (c in samples.indices) sum += samples[c] * coefficients[c]
        return sum
    }

    private fun Float.toPcm16(): Short =
        roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    /** Returns left/right coefficient rows for the given input channel count. */
    private fun buildCoefficients(channels: Int): Pair<FloatArray, FloatArray> {
        val left = FloatArray(channels)
        val right = FloatArray(channels)
        val center = MINUS_3_DB
        val surround = MINUS_3_DB

        fun front() { left[0] = 1f; right[1] = 1f }
        fun centerAt(index: Int, gain: Float) { left[index] = gain; right[index] = gain }
        fun pairAt(leftIndex: Int, rightIndex: Int, gain: Float) {
            left[leftIndex] = gain
            right[rightIndex] = gain
        }

        // Defaults (center at its standard level) used only for normalization.
        front()
        when (channels) {
            3 -> centerAt(2, center)                                  // FL FR FC
            4 -> pairAt(2, 3, surround)                               // FL FR BL BR
            5 -> { centerAt(2, center); pairAt(3, 4, surround) }      // FL FR FC BL BR
            6 -> { centerAt(2, center); pairAt(4, 5, surround) }      // FL FR FC LFE BL BR
            7 -> {                                                    // FL FR FC LFE BC SL SR
                centerAt(2, center)
                centerAt(4, 0.5f)
                pairAt(5, 6, surround)
            }
            else -> {                                                 // FL FR FC LFE BL BR SL SR
                centerAt(2, center)
                pairAt(4, 5, surround)
                pairAt(6, 7, surround)
            }
        }
        val scale = if (normalize) 1f / maxOf(left.sum(), right.sum()) else 1f

        // Apply the user's center level after computing normalization.
        if (channels != 4) centerAt(2, center * centerGain)
        for (i in 0 until channels) {
            left[i] *= scale
            right[i] *= scale
        }
        return left to right
    }

    private companion object {
        const val MINUS_3_DB = 0.70710677f
    }
}
