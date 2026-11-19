package com.music.bitchord.fork

import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.music.bitchord.data.canvas.ForkClipJudge
import com.music.bitchord.data.canvas.ForkVideoCanvas
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Fork: what the music-video canvas ([ForkVideoCanvas]) needs from Android, as the owner's phone
 * extension does it (`VideoCanvas.board` / `remux`, `ClipFrames`): a storyboard sheet decoded with
 * BitmapFactory, the fetched fragments remuxed with MediaExtractor and MediaMuxer (no re-encode),
 * and a small copy's frames decoded with MediaCodec and read off the brightness plane.
 */
object ForkVideoCanvasAndroid : ForkVideoCanvas.Platform {

    override fun grey(bytes: ByteArray): ForkVideoCanvas.Grey? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            for (i in px.indices) {
                val c = px[i]
                px[i] = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
            }
            return ForkVideoCanvas.Grey(bmp.width, bmp.height, px)
        } finally {
            bmp.recycle()
        }
    }

    override fun remux(raw: File, out: File, startSec: Double, endSec: Double): Long {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(raw.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IOException("no video track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val t = m.addTrack(fmt)
            m.start()
            val buf = ByteBuffer.allocate(
                if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxOf(1 shl 20, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) else 4 shl 20,
            )
            val info = MediaCodec.BufferInfo()
            // from the last key frame at or before the part (the fragments begin on one)
            var first = -1L
            ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var written = 0
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ts = ex.sampleTime
                val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                if (first < 0) {
                    if (!key) {
                        ex.advance()
                        continue
                    }
                    first = ts
                }
                if (ts >= endSec * 1e6) break
                info.set(0, size, ts - first, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(t, buf, info)
                written++
                ex.advance()
            }
            if (written == 0) throw IOException("no frames in the part")
            return first
        } finally {
            ex.release()
            mux?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    override fun frames(file: File): Pair<List<IntArray>, LongArray> {
        val frames = ArrayList<IntArray>()
        val times = ArrayList<Long>()
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val ti = (0 until ex.trackCount).lastOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return frames to LongArray(0)
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)
                        val n = if (b == null) -1 else ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5000)
                if (o < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    c.releaseOutputBuffer(o, false)
                    break
                }
                c.getOutputImage(o)?.let { img ->
                    frames += grid(img)
                    times += info.presentationTimeUs / 1000
                    img.close()
                }
                c.releaseOutputBuffer(o, false)
            }
        } finally {
            ex.release()
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        return frames to times.toLongArray()
    }

    /** The brightness plane averaged to the judge's grid (every other pixel each way is enough). */
    private fun grid(img: Image): IntArray {
        val y = img.planes[0]
        val buf = y.buffer
        val rs = y.rowStride
        val ps = y.pixelStride
        val crop = img.cropRect
        val w = crop.width()
        val h = crop.height()
        val gw = ForkClipJudge.W
        val gh = ForkClipJudge.H
        val out = IntArray(gw * gh)
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val ya = crop.top + cy * h / gh
            val yb = crop.top + maxOf(cy * h / gh + 1, (cy + 1) * h / gh)
            val xa = crop.left + cx * w / gw
            val xb = crop.left + maxOf(cx * w / gw + 1, (cx + 1) * w / gw)
            var sum = 0L
            var n = 0
            var yy = ya
            while (yy < yb) {
                var xx = xa
                while (xx < xb) {
                    sum += buf.get(yy * rs + xx * ps).toInt() and 0xFF
                    n++
                    xx += 2
                }
                yy += 2
            }
            out[cy * gw + cx] = if (n > 0) (sum / n).toInt() else 0
        }
        return out
    }
}
