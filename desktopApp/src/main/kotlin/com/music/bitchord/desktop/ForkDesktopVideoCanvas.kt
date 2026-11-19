package com.music.bitchord.desktop

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.music.bitchord.data.canvas.ForkClipJudge
import com.music.bitchord.data.canvas.ForkVideoCanvas
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import java.io.File
import java.io.IOException

/**
 * Fork: what the music-video canvas ([ForkVideoCanvas]) needs from the PC: a storyboard sheet
 * decoded with Skia, the fetched fragments remuxed with FFmpeg (stream copy, no re-encode, from the
 * key frame at or before the part), and a small copy's frames read through the clip decoder
 * ([DesktopCanvasDecoder], scaled down) into the judge's grids. The phone's is
 * `ForkVideoCanvasAndroid` (MediaExtractor, MediaMuxer, MediaCodec).
 */
internal object ForkDesktopVideoCanvas : ForkVideoCanvas.Platform {

    override fun grey(bytes: ByteArray): ForkVideoCanvas.Grey? = runCatching {
        val bitmap = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(px)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
        }
        ForkVideoCanvas.Grey(bitmap.width, bitmap.height, px)
    }.getOrNull()

    /**
     * Two passes over [raw] (a few MB): the first finds the last key frame at or before [startSec]
     * (the fragments have no index left in them, so a seek would scan anyway), the second copies
     * from it up to [endSec], times moved to start at 0. Times are counted from the file's first
     * packet: FFmpeg reads the fragments' own decode times (`tfdt`), which are the video's, while
     * the caller's seconds are the file's (the phone's MediaExtractor starts the file at 0).
     */
    override fun remux(raw: File, out: File, startSec: Double, endSec: Double): Long {
        DesktopAudioDecoder.ensureNetworkReady()
        var keyDts = AV_NOPTS_VALUE
        var keyPts = AV_NOPTS_VALUE
        var origin = AV_NOPTS_VALUE
        read(raw) { _, stream, pkt ->
            val tb = stream.time_base().num().toDouble() / stream.time_base().den()
            if (origin == AV_NOPTS_VALUE) origin = pkt.dts().takeIf { it != AV_NOPTS_VALUE } ?: pkt.pts()
            val pts = if (pkt.pts() != AV_NOPTS_VALUE) pkt.pts() else pkt.dts()
            if ((pts - origin) * tb > startSec + 1e-6 && keyDts != AV_NOPTS_VALUE) return@read false
            if ((pkt.flags() and AV_PKT_FLAG_KEY) != 0) {
                keyDts = pkt.dts()
                keyPts = pts
            }
            true
        }
        if (keyDts == AV_NOPTS_VALUE) throw IOException("no key frame in the part")

        val output = AVFormatContext(null)
        check(avformat_alloc_output_context2(output, null, "mp4", out.path) >= 0) { "no mp4 muxer" }
        var written = 0
        var firstUs = 0L
        var opened = false
        try {
            var outStream: org.bytedeco.ffmpeg.avformat.AVStream? = null
            var started = false
            read(raw) { _, stream, pkt ->
                val tb = stream.time_base().num().toDouble() / stream.time_base().den()
                if (outStream == null) {
                    val s = avformat_new_stream(output, null)
                    check(avcodec_parameters_copy(s.codecpar(), stream.codecpar()) >= 0) { "bad codec parameters" }
                    s.codecpar().codec_tag(0)
                    s.time_base(stream.time_base())
                    val pb = AVIOContext(null)
                    check(avio_open(pb, out.path, AVIO_FLAG_WRITE) >= 0) { "could not write $out" }
                    output.pb(pb)
                    opened = true
                    check(avformat_write_header(output, null as AVDictionary?) >= 0) { "could not start the mp4" }
                    outStream = s
                    firstUs = Math.round((keyPts - origin) * tb * 1e6)
                }
                if (!started) {
                    if (pkt.dts() != keyDts) return@read true
                    started = true
                }
                val pts = if (pkt.pts() != AV_NOPTS_VALUE) pkt.pts() else pkt.dts()
                if ((pts - origin) * tb >= endSec) return@read false
                pkt.pts(pts - keyDts)
                pkt.dts(pkt.dts() - keyDts)
                av_packet_rescale_ts(pkt, stream.time_base(), outStream!!.time_base())
                pkt.stream_index(0)
                pkt.pos(-1)
                if (av_interleaved_write_frame(output, pkt) >= 0) written++
                true
            }
            if (outStream != null) av_write_trailer(output)
        } finally {
            if (opened) avio_closep(output.pb())
            avformat_free_context(output)
        }
        if (written == 0) throw IOException("no frames in the part")
        return firstUs
    }

    /** Every video packet of [file] in order to [each], until it answers false. */
    private fun read(
        file: File,
        each: (AVFormatContext, org.bytedeco.ffmpeg.avformat.AVStream, AVPacket) -> Boolean,
    ) {
        val input = AVFormatContext(null)
        check(avformat_open_input(input, file.path, null, null as AVDictionary?) >= 0) { "could not open $file" }
        val pkt = av_packet_alloc()
        try {
            check(avformat_find_stream_info(input, null as AVDictionary?) >= 0) { "no stream info" }
            val index = av_find_best_stream(input, AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
            check(index >= 0) { "no video stream" }
            val stream = input.streams(index)
            while (av_read_frame(input, pkt) >= 0) {
                val go = pkt.stream_index() != index || each(input, stream, pkt)
                av_packet_unref(pkt)
                if (!go) break
            }
        } finally {
            av_packet_free(pkt)
            avformat_close_input(input)
        }
    }

    override fun frames(file: File): Pair<List<IntArray>, LongArray> {
        val decoder = DesktopCanvasDecoder(forkMaxEdge = 128)
        val frames = ArrayList<IntArray>()
        val times = ArrayList<Long>()
        try {
            decoder.open(file.absolutePath).getOrThrow()
            val w = decoder.width
            val h = decoder.height
            val bgra = ByteArray(w * h * 4)
            val luma = IntArray(w * h)
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val f = decoder.forkNext(bgra) { true } ?: break
                if (!f.kept || f.ptsMs < 0) continue
                for (i in 0 until w * h) {
                    val b = bgra[i * 4].toInt() and 255
                    val g = bgra[i * 4 + 1].toInt() and 255
                    val r = bgra[i * 4 + 2].toInt() and 255
                    luma[i] = (r * 3 + g * 6 + b) / 10
                }
                frames += ForkClipJudge.grid(luma, w, 0, 0, w, h)
                times += f.ptsMs
            }
        } finally {
            decoder.close()
        }
        return frames to times.toLongArray()
    }
}
