package com.ghosttyxr.app.terminal.windowmirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.ghosttyxr.app.util.GxrLog

private const val TAG = "WindowVideoDecoder"
private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L

/**
 * One complete access unit, reassembled from the slices of a single frame by
 * [WindowMirrorMediaClient].
 *
 * [hadGap] is set when the media client saw a slice sequence discontinuity, which
 * means the reference chain is broken and only a keyframe can restore it.
 */
class WindowMirrorVideoFrame(
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val keyFrame: Boolean,
    val hadGap: Boolean,
    val data: ByteArray,
)

class WindowVideoDecoder {
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
    private var awaitingKeyFrame = false

    /** Frames handed to the codec since the last reset, for the fps report. */
    var decodedFrameCount = 0L
        private set

    /** True while the decoder is discarding frames until the next keyframe. */
    val needsKeyFrame: Boolean
        @Synchronized get() = awaitingKeyFrame

    /**
     * Attach the render target. The surface is owned by the caller (a
     * SurfaceView's holder) — never released here.
     */
    @Synchronized
    fun setSurface(surface: Surface?) {
        if (this.surface == surface) return
        releaseCodec()
        this.surface = surface
        width = 0
        height = 0
    }

    /** Select the stream codec ("hevc" or "h264") from mirror metadata. */
    @Synchronized
    fun setCodec(codecName: String) {
        val mime = if (codecName.equals("hevc", ignoreCase = true)) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        if (mime == mimeType) return
        mimeType = mime
        releaseCodec()
        width = 0
        height = 0
    }

    @Synchronized
    fun queue(frame: WindowMirrorVideoFrame) {
        val targetSurface = surface ?: return
        if (frame.hadGap) {
            awaitingKeyFrame = true
        }
        if (codec == null || frame.width != width || frame.height != height) {
            if (!frame.keyFrame) {
                // No configured codec and no keyframe to configure one from. The
                // GOP is infinite, so nothing arrives on its own — say we need a
                // keyframe so the media client asks for one.
                awaitingKeyFrame = true
                return
            }
            configure(targetSurface, frame.width, frame.height)
        }
        val codec = codec ?: return
        // Drain BEFORE the keyframe gate. Skipping P-frames without releasing
        // output buffers stalls the codec (outputs full → inputs never freed),
        // which livelocked the stream at ~1fps: every keyframe hit "no input
        // buffer" because nothing had drained since the previous GOP.
        drain(codec)
        // A dropped P-frame breaks the decoder's reference chain; once any frame
        // is skipped, keep skipping until the next keyframe resyncs the stream.
        if (awaitingKeyFrame && !frame.keyFrame) return
        var inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0 && frame.keyFrame) {
            // Resync point: reclaim every in-flight buffer rather than dropping
            // the keyframe, which would stall the stream for another full GOP.
            if (runCatching { codec.flush() }.isFailure) {
                awaitingKeyFrame = true
                return
            }
            inputIndex = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
        }
        if (inputIndex < 0) {
            awaitingKeyFrame = true
            GxrLog.d(TAG) { "no input buffer; waiting for next keyframe" }
            return
        }
        val buffer = codec.getInputBuffer(inputIndex)
        if (buffer == null || buffer.capacity() < frame.data.size) {
            codec.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
            awaitingKeyFrame = true
            return
        }
        buffer.clear()
        buffer.put(frame.data)
        codec.queueInputBuffer(
            inputIndex,
            0,
            frame.data.size,
            frame.ptsUs,
            if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
        )
        awaitingKeyFrame = false
        decodedFrameCount++
        drain(codec)
    }

    @Synchronized
    fun release() {
        releaseCodec()
        surface = null
    }

    private fun configure(surface: Surface, width: Int, height: Int) {
        releaseCodec()
        runCatching {
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            codec = MediaCodec.createDecoderByType(mimeType).also { decoder ->
                runCatching {
                    decoder.configure(format, surface, null, 0)
                }.recover {
                    // Some decoders reject KEY_LOW_LATENCY outright; retry without it.
                    decoder.reset()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                    }
                    decoder.configure(format, surface, null, 0)
                }.getOrThrow()
                decoder.start()
            }
            this.width = width
            this.height = height
            awaitingKeyFrame = false
        }.onFailure { error ->
            GxrLog.w(TAG, "decoder configure failed: ${error.message}")
            releaseCodec()
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                in 0..Int.MAX_VALUE -> codec.releaseOutputBuffer(outputIndex, info.size > 0)
            }
        }
    }

    private fun releaseCodec() {
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
    }
}
