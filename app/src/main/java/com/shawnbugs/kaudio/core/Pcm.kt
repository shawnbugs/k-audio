package com.shawnbugs.kaudio.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** 试听/波形用的临时 WAV 规格：44100Hz / 16bit / 立体声（同 PC 版） */
const val PREVIEW_RATE = 44100

class Peaks(val min: FloatArray, val max: FloatArray, val bucketSec: Double, val frames: Long, val dataOffset: Long)

/** 读取 16bit 立体声 WAV，按 bucketFrames 帧一组算出最小/最大值 */
fun readPeaks(wav: File, bucketFrames: Int, cancelled: () -> Boolean = { false }): Peaks? {
    RandomAccessFile(wav, "r").use { f ->
        val head = ByteArray(12)
        f.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var dataOff = -1L
        var dataLen = -1L
        val ch = ByteArray(8)
        while (f.filePointer + 8 <= f.length()) {
            f.readFully(ch)
            val id = String(ch, 0, 4, Charsets.US_ASCII)
            val size = (ch[4].toLong() and 0xff) or ((ch[5].toLong() and 0xff) shl 8) or
                ((ch[6].toLong() and 0xff) shl 16) or ((ch[7].toLong() and 0xff) shl 24)
            if (id == "data") {
                dataOff = f.filePointer
                // ffmpeg 写管道/超大文件时 size 可能是 0xFFFFFFFF，以实际文件长度为准
                dataLen = min(size, f.length() - dataOff)
                break
            }
            f.seek(f.filePointer + size + (size and 1))
        }
        if (dataLen <= 0) return null

        val frames = dataLen / 4
        val nb = ((frames + bucketFrames - 1) / bucketFrames).toInt()
        val mn = FloatArray(nb)
        val mx = FloatArray(nb)
        val buf = ByteArray(bucketFrames * 4 * 512)
        var left = frames * 4
        var b = 0
        var inBucket = 0
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        f.seek(dataOff)
        while (left > 0) {
            if (cancelled()) return null
            val want = min(buf.size.toLong(), left).toInt()
            var n = 0
            while (n < want) {
                val r = f.read(buf, n, want - n)
                if (r <= 0) break
                n += r
            }
            if (n <= 0) break
            n -= n % 4
            left -= n
            var i = 0
            while (i < n) {
                val l = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                val r = ((buf[i + 2].toInt() and 0xff) or (buf[i + 3].toInt() shl 8)).toShort().toInt()
                if (l < lo) lo = l
                if (l > hi) hi = l
                if (r < lo) lo = r
                if (r > hi) hi = r
                if (++inBucket == bucketFrames) {
                    mn[b] = lo / 32768f; mx[b] = hi / 32768f; b++
                    inBucket = 0; lo = Int.MAX_VALUE; hi = Int.MIN_VALUE
                }
                i += 4
            }
        }
        if (inBucket > 0 && b < nb) { mn[b] = lo / 32768f; mx[b] = hi / 32768f }
        return Peaks(mn, mx, bucketFrames / PREVIEW_RATE.toDouble(), frames, dataOff)
    }
}

/**
 * 按时间线顺序读出 PCM：片段结构取开始播放时的快照，音量/淡入淡出读实时值（拖动时能立刻听到）。
 * 与 PC 版 EditReader 一致。
 */
class EditReader(wav: File, private val dataOff: Long, private val live: EditState, from: Double, to: Double) : AutoCloseable {
    private val f = RandomAccessFile(wav, "r")
    private val tl0: LongArray
    private val src0: LongArray
    private val len: LongArray
    private val length = live.length
    private var pos: Long
    private val end: Long

    init {
        val n = live.segs.size
        tl0 = LongArray(n); src0 = LongArray(n); len = LongArray(n)
        var t = 0L
        for (i in 0 until n) {
            src0[i] = (live.segs[i].a * PREVIEW_RATE).roundToLong()
            len[i] = max(0L, (live.segs[i].b * PREVIEW_RATE).roundToLong() - src0[i])
            tl0[i] = t
            t += len[i]
        }
        pos = max(0L, (from * PREVIEW_RATE).roundToLong())
        end = min(t, (to * PREVIEW_RATE).roundToLong())
    }

    /** 填满 buf（16bit 立体声交错），返回实际字节数；0 = 读完 */
    fun read(buf: ByteArray): Int {
        var filled = 0
        var seg = 0
        val baseGain = live.baseGain
        val fi = live.fadeIn
        val fo = live.fadeOut
        while (filled < buf.size && pos < end) {
            while (seg < tl0.size - 1 && pos >= tl0[seg] + len[seg]) seg++
            val inSeg = pos - tl0[seg]
            val n = min(min(len[seg] - inSeg, end - pos), ((buf.size - filled) / 4).toLong())
            if (n <= 0) break
            f.seek(dataOff + (src0[seg] + inSeg) * 4)
            val want = n.toInt() * 4
            var got = 0
            while (got < want) {
                val r = f.read(buf, filled + got, want - got)
                if (r <= 0) break
                got += r
            }
            if (got < want) buf.fill(0, filled + got, filled + want)
            for (k in 0 until n.toInt()) {
                val t = (pos + k) / PREVIEW_RATE.toDouble()
                var g = baseGain
                if (fi > 0 && t < fi) g *= t / fi
                if (fo > 0 && t > length - fo) g *= max(0.0, (length - t) / fo)
                if (g == 1.0) continue
                val o = filled + k * 4
                scale(buf, o, g)
                scale(buf, o + 2, g)
            }
            filled += want
            pos += n
        }
        return filled
    }

    private fun scale(b: ByteArray, o: Int, g: Double) {
        val s = ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort()
        val v = Math.round(s * g).toInt().coerceIn(-32768, 32767)
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    override fun close() { f.close() }
}

/** 试听播放器：AudioTrack 流模式，后台线程按 EditReader 喂数据 */
class PcmPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var stop = false
    @Volatile private var done = true
    private var from = 0.0

    val playing: Boolean get() = !done

    fun start(wav: File, dataOffset: Long, edit: EditState, fromT: Double, toT: Double): Boolean {
        stop()
        val reader = try { EditReader(wav, dataOffset, edit, fromT, toT) } catch (_: Exception) { return false }
        val minBuf = AudioTrack.getMinBufferSize(PREVIEW_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PREVIEW_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, PREVIEW_RATE * 4 / 5))  // 约 0.2 秒，拖动音量反应快
                .build()
        } catch (_: Exception) { reader.close(); return false }
        synchronized(lock) { track = t }
        from = fromT
        stop = false
        done = false
        t.play()
        worker = Thread {
            val buf = ByteArray(4410 * 4)
            var written = 0L
            try {
                while (!stop) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && !stop) {
                        val w = t.write(buf, off, n - off)
                        if (w < 0) { stop = true; break }
                        off += w
                    }
                    written += off / 4
                }
                // 等缓冲里剩下的放完再结束（否则结尾会被吃掉零点几秒）
                var guard = 0
                while (!stop && (t.playbackHeadPosition.toLong() and 0xffffffffL) < written && guard++ < 300) Thread.sleep(10)
            } catch (_: Exception) {
            } finally {
                synchronized(lock) {
                    try { t.pause(); t.flush() } catch (_: Exception) { }
                    t.release()
                    track = null
                }
                reader.close()
                done = true
            }
        }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stop() {
        val w = worker ?: return
        stop = true
        synchronized(lock) { try { track?.pause(); track?.flush() } catch (_: Exception) { } }
        w.join(2000)
        worker = null
    }

    /** 当前播放到时间线的哪个位置（秒），未播放返回 -1 */
    val position: Double
        get() = synchronized(lock) {
            val t = track ?: return -1.0
            from + (t.playbackHeadPosition.toLong() and 0xffffffffL) / PREVIEW_RATE.toDouble()
        }
}
