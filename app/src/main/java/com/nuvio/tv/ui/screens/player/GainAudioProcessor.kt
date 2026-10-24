package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Controle de volume do player (substitui a antiga amplificação PCM).
 *
 * Só ATENUA: 100% = som original (processador desligado), 95%, 90%... até 0%.
 * Como o fator nunca passa de 1.0, o sinal nunca estoura (sem clipping).
 */
internal class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var volumePercent: Int = PLAYER_VOLUME_DEFAULT_PERCENT

    @Volatile
    private var gainScale: Float = 1f

    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(PLAYER_VOLUME_MIN_PERCENT, PLAYER_VOLUME_MAX_PERCENT)
        volumePercent = clamped
        gainScale = clamped / 100f
    }

    fun isGainEnabled(): Boolean {
        return volumePercent != PLAYER_VOLUME_DEFAULT_PERCENT
    }

    override fun isActive(): Boolean {
        return super.isActive() && isGainEnabled()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val inputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputSize)
        val scale = gainScale

        if (scale == 1f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, scale)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, scale)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= 2) {
            val sample = inputBuffer.short.toInt()
            val attenuated = (sample * scale)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(attenuated.toShort())
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, scale: Float) {
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= 4) {
            val sample = inputBuffer.float
            val attenuated = (sample * scale).coerceIn(-1f, 1f)
            outputBuffer.putFloat(attenuated)
        }

        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
    }
}
