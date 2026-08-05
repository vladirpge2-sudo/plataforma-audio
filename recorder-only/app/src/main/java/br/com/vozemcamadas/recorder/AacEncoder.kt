package br.com.vozemcamadas.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AacEncoder(
    output: File,
    private val sampleRate: Int,
    private val channelCount: Int = 1,
    bitRate: Int = 96_000,
) : AutoCloseable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false
    private var totalSamples = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(samples: ShortArray) {
        check(!closed) { "O codificador já foi encerrado" }
        var offset = 0
        while (offset < samples.size) {
            val inputIndex = codec.dequeueInputBuffer(20_000)
            if (inputIndex < 0) {
                drain(false)
                continue
            }
            val input = codec.getInputBuffer(inputIndex) ?: continue
            input.clear()
            val sampleCapacity = input.remaining() / 2
            val count = minOf(sampleCapacity, samples.size - offset)
            val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { bytes.putShort(samples[offset + it]) }
            bytes.flip()
            input.put(bytes)
            val ptsUs = totalSamples * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, count * 2, ptsUs, 0)
            totalSamples += count / channelCount
            offset += count
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idleCount = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000 else 0)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleCount > 50) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "O formato do áudio mudou duas vezes" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && bufferInfo.size > 0 && muxerStarted) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val inputIndex = codec.dequeueInputBuffer(20_000)
        if (inputIndex >= 0) {
            val ptsUs = totalSamples * 1_000_000L / sampleRate
            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(true)
        runCatching { codec.stop() }
        codec.release()
        if (muxerStarted) runCatching { muxer.stop() }
        muxer.release()
    }
}
