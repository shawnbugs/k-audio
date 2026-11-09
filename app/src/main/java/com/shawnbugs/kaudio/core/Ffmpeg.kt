package com.shawnbugs.kaudio.core

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/*
 * 调用随 APK 一起装的 ffmpeg（jniLibs/arm64-v8a/libffmpeg.so，其实是可执行文件）。
 * 参数用列表传，不经过 shell，文件名里有空格、引号都没关系。
 */
object Ffmpeg {
    private val DUR_RE = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
    private val AUDIO_RE = Regex("""Stream #.*?Audio:\s*(.+)$""")

    fun exe(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    fun available(ctx: Context): Boolean = exe(ctx).canExecute()

    /** 一次运行：started 拿到进程（用来中途取消），onErr 收 stderr 每行，onTime 收处理进度（秒） */
    fun run(
        ctx: Context,
        args: List<String>,
        started: ((Process) -> Unit)? = null,
        onErr: ((String) -> Unit)? = null,
        onTime: ((Double) -> Unit)? = null,
    ): Int {
        val cmd = ArrayList<String>(args.size + 1)
        cmd.add(exe(ctx).absolutePath)
        cmd.addAll(args)
        val p = ProcessBuilder(cmd).directory(ctx.cacheDir).start()
        started?.invoke(p)
        p.outputStream.close()
        // stdout 是 -progress pipe:1 的进度，stderr 是日志，两边都要读，否则缓冲区满了 ffmpeg 会卡住
        val outT = thread(isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { line ->
                // out_time_us / out_time_ms 实际单位都是微秒
                if (onTime != null && (line.startsWith("out_time_us=") || line.startsWith("out_time_ms="))) {
                    line.substringAfter('=').toLongOrNull()?.let { if (it >= 0) onTime(it / 1e6) }
                }
            }
        }
        p.errorStream.bufferedReader().forEachLine { onErr?.invoke(it) }
        val code = p.waitFor()
        outT.join(2000)
        return code
    }

    fun kill(p: Process?) {
        try { p?.destroy() } catch (_: Exception) { }
    }

    fun parseDuration(line: String): Double? = DUR_RE.find(line)?.let {
        it.groupValues[1].toInt() * 3600 + it.groupValues[2].toInt() * 60 + it.groupValues[3].toDouble()
    }

    fun parseAudio(line: String): String? = AUDIO_RE.find(line)?.groupValues?.get(1)?.trim()

    /** 视频流描述；排除 MP3/M4A 里的封面图（attached pic），同 PC 版 */
    fun parseVideo(line: String): String? {
        if (line.contains("attached pic")) return null
        return VIDEO_RE.find(line)?.groupValues?.get(1)?.trim()
    }

    class Probe(val duration: Double, val audio: String?, val video: String?)

    /**
     * 只读文件头：时长 + 第一条音轨的描述（如「aac (LC), 44100 Hz, stereo, fltp, 128 kb/s」）+ 第一条视频流的描述。
     * 手机竖着拍的视频常是横的画面带「旋转 90 度」标记（ffmpeg 滤镜里会自动转正），这时在视频描述后面加「, rotate=90」，
     * 合并视频时按转正后的尺寸算（0.5.0）。
     */
    fun probe(ctx: Context, path: String): Probe {
        var dur = -1.0
        var audio: String? = null
        var video: String? = null
        var inVideo = false   // 正在读第一条视频流下面的附加信息（旋转标记在那里）
        var rot = 0
        run(ctx, listOf("-hide_banner", "-nostdin", "-i", path), onErr = { line ->
            if (dur < 0) parseDuration(line)?.let { dur = it }
            if (line.contains("Stream #")) {
                inVideo = false
                if (audio == null) parseAudio(line)?.let { audio = it }
                if (video == null) parseVideo(line)?.let { video = it; inVideo = true }
            } else if (inVideo && rot == 0) {
                ROT_RE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { rot = Math.round(abs(it)).toInt() % 360 }
            }
        })
        if (rot == 90 || rot == 270) video = video?.let { "$it, rotate=90" }
        return Probe(dur, audio, video)
    }

    private val ROT_RE = Regex("""rotation of (-?[\d.]+) degrees""")

    /** 从视频描述里取出「视频 1920x1080 · 30 fps · h264」 */
    fun videoSummary(desc: String): String {
        val codec = desc.split(' ', ',').firstOrNull { it.isNotEmpty() } ?: ""
        val s = SIZE_RE.find(desc)?.value
        val f = FPS_RE.find(desc)?.groupValues?.get(1)
        return "视频 " + (s?.let { "$it · " } ?: "") + (f?.let { "$it fps · " } ?: "") + codec
    }

    /** 帧率（逐帧按钮用）；取不到返回 null */
    fun videoFps(desc: String): Double? = FPS_RE.find(desc)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 1.0..240.0 }

    /** 画面显示出来的宽高（带旋转标记的已对调）；取不到返回 null */
    fun videoSize(desc: String): Pair<Int, Int>? {
        val m = SIZE_RE.find(desc) ?: return null
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        return if (desc.contains("rotate=90")) h to w else w to h
    }

    /** 判断几个视频能不能直接拼（无损快剪合并）用：编码、像素格式、尺寸、帧率、旋转都一样才算同一种 */
    fun videoKey(desc: String): String {
        val codec = desc.split(' ', ',').firstOrNull { it.isNotEmpty() } ?: ""
        val pix = PIX_RE.find(desc)?.value ?: ""
        return listOf(codec, pix, SIZE_RE.find(desc)?.value ?: "", FPS_RE.find(desc)?.groupValues?.get(1) ?: "", desc.contains("rotate=90")).joinToString("|")
    }

    /** 同上，声音：编码、采样率、声道一样才算同一种（比特率不管） */
    fun audioKey(desc: String): String {
        val p = desc.split(", ")
        val i = p.indexOfFirst { it.endsWith(" Hz") }
        return p[0].substringBefore(' ') + "|" + (if (i >= 0) p.subList(i, minOf(p.size, i + 2)).joinToString(",") else "")
    }

    /** 合并视频时统一用的帧率参数：29.97 之类写成分数，接近整数的取整（手机录像常是 29.9x 的可变帧率） */
    fun fpsArg(f: Double?): String {
        if (f == null) return "30"
        for ((v, s) in listOf(23.976 to "24000/1001", 29.97 to "30000/1001", 59.94 to "60000/1001")) if (abs(f - v) < 0.02) return s
        val n = Math.round(f)
        return if (abs(f - n) < 0.15) n.toString() else t(f)
    }

    private val PIX_RE = Regex("""\b(yuvj?\d{3}p\w*|nv\d{2}|gray\w*|rgb\w+|bgr\w+)""")

    private val SIZE_RE = Regex("""\b(\d{2,5})x(\d{2,5})\b""")
    private val FPS_RE = Regex("""([\d.]+)\s*fps""")
    private val VIDEO_RE = Regex("""Stream #.*?Video:\s*(.+)$""")

    /**
     * 取源文件 srcT 秒处的一帧画面（BMP 字节），宽度缩到 width（不放大），同 PC 版 GrabFrame。
     * 输入端 -ss：先跳到前一个关键帧再解码到准确位置。失败返回 null。
     */
    fun grabFrame(ctx: Context, path: String, srcT: Double, width: Int, started: ((Process) -> Unit)? = null): ByteArray? {
        val args = listOf(
            "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", t(maxOf(0.0, srcT)), "-i", path,
            "-an", "-sn", "-dn", "-frames:v", "1", "-vf", "scale='min($width,iw)':-2",
            "-f", "image2pipe", "-c:v", "bmp", "pipe:1",
        )
        return try {
            val p = ProcessBuilder(listOf(exe(ctx).absolutePath) + args).directory(ctx.cacheDir).start()
            started?.invoke(p)
            p.outputStream.close()
            val errT = thread(isDaemon = true) { p.errorStream.use { it.readBytes() } }
            val bytes = p.inputStream.use { it.readBytes() }
            p.waitFor()
            errT.join(500)
            if (bytes.size < 64) null else bytes
        } catch (_: Exception) { null }
    }

    /** 秒数写成 ffmpeg 参数（不用科学计数法、不带千分位） */
    fun t(sec: Double): String =
        String.format(Locale.US, "%.6f", sec).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /**
     * 导出用滤镜：拆出各片段 → 拼接 → 音量 → 淡入淡出（与 PC 版 BuildFilter 完全一致）。
     * 输出标签 [vout]（有视频时）、[aout]（有音频时）。vpost：拼接后给画面加的处理（缩放等），可为 null；
     * 画面也跟着声音一起淡入淡出（从黑场进、到黑场出）。
     * input：第几个输入文件；tag：所有中间标签和输出标签的前缀（合并多个文件时每个文件一套，互不冲突，输出变成 [<tag>aout]）。
     */
    fun buildFilter(e: EditState, video: Boolean, audio: Boolean, vpost: String?, input: Int = 0, tag: String = ""): String {
        val sb = StringBuilder()
        val n = e.segs.size
        val inV = "[$input:v]"
        val inA = "[$input:a]"
        if (video) split(sb, inV, "split", "${tag}v", n)
        if (audio) split(sb, inA, "asplit", "${tag}i", n)
        for (i in 0 until n) {
            val a = t(e.segs[i].a)
            val b = t(e.segs[i].b)
            if (video) sb.append(if (n > 1) "[${tag}v$i]" else inV)
                .append("trim=start=").append(a).append(":end=").append(b)
                .append(",setpts=PTS-STARTPTS[${tag}w").append(i).append("];")
            if (audio) sb.append(if (n > 1) "[${tag}i$i]" else inA)
                .append("atrim=start=").append(a).append(":end=").append(b)
                .append(",asetpts=PTS-STARTPTS[${tag}s").append(i).append("];")
        }
        var vc = "[${tag}w0]"
        var ac = "[${tag}s0]"
        if (n > 1) {
            for (i in 0 until n) {
                if (video) sb.append("[${tag}w").append(i).append(']')
                if (audio) sb.append("[${tag}s").append(i).append(']')
            }
            sb.append("concat=n=").append(n).append(":v=").append(if (video) 1 else 0).append(":a=").append(if (audio) 1 else 0)
            if (video) sb.append("[${tag}vc]")
            if (audio) sb.append("[${tag}ac]")
            sb.append(';')
            vc = "[${tag}vc]"; ac = "[${tag}ac]"
        }
        if (video) {
            val v = ArrayList<String>()
            if (!vpost.isNullOrEmpty()) v.add(vpost)
            if (e.fadeIn > 0) v.add("fade=t=in:st=0:d=" + t(e.fadeIn))
            if (e.fadeOut > 0) v.add("fade=t=out:st=" + t(e.length - e.fadeOut) + ":d=" + t(e.fadeOut))
            if (v.isEmpty()) v.add("null")
            sb.append(vc).append(v.joinToString(",")).append("[${tag}vout]")
            if (audio) sb.append(';')
        }
        if (audio) {
            val f = ArrayList<String>()
            if (abs(e.gainDb) > 0.001) f.add("volume=" + t(e.gainDb) + "dB")
            if (e.fadeIn > 0) f.add("afade=t=in:st=0:d=" + t(e.fadeIn))
            if (e.fadeOut > 0) f.add("afade=t=out:st=" + t(e.length - e.fadeOut) + ":d=" + t(e.fadeOut))
            if (f.isEmpty()) f.add("anull")
            sb.append(ac).append(f.joinToString(",")).append("[${tag}aout]")
        }
        return sb.toString()
    }

    private fun split(sb: StringBuilder, src: String, filter: String, prefix: String, n: Int) {
        if (n < 2) return
        sb.append(src).append(filter).append('=').append(n)
        for (i in 0 until n) sb.append('[').append(prefix).append(i).append(']')
        sb.append(';')
    }

    /** 视频缩放：只缩小不放大；横屏按高度、竖屏按宽度（短边），同 PC 版 */
    fun scaleFilter(h: Int): String? =
        if (h <= 0) null
        else "scale='if(gt(iw,ih),-2,min(iw,$h))':'if(gt(iw,ih),min(ih,$h),-2)',setsar=1"
}

/**
 * 写一个指定时长的静音 WAV（44.1kHz/16bit/立体声），给没有声音的视频当波形和试听源。
 * PC 版用 ffmpeg 的 anullsrc 生成；手机上的 ffmpeg 没编 lavfi 设备，直接写：文件头 + setLength 补零（稀疏文件，很快）。
 */
fun writeSilentWav(f: File, seconds: Double): Long {
    val frames = Math.round(seconds * PREVIEW_RATE).coerceAtLeast(1L)
    val dataLen = frames * 4
    val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt((36 + dataLen).coerceAtMost(0xFFFFFFFFL).toInt()).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(PREVIEW_RATE).putInt(PREVIEW_RATE * 4).putShort(4).putShort(16)
    h.put("data".toByteArray()).putInt(dataLen.coerceAtMost(0xFFFFFFFFL).toInt())
    java.io.RandomAccessFile(f, "rw").use { raf ->
        raf.setLength(0)
        raf.write(h.array())
        raf.setLength(44 + dataLen)
    }
    return frames
}

/** 时间显示：1:23.45 / 1:02:03.45 */
fun fmtTime(sec: Double): String {
    val ms = Math.round(maxOf(0.0, sec) * 1000)
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms % 60_000 / 1000.0
    return if (h > 0) String.format(Locale.US, "%d:%02d:%05.2f", h, m, s)
    else String.format(Locale.US, "%d:%05.2f", m, s)
}

/** 支持 "90" "12.5" "1:30" "00:01:30.500"；空 = -1（未设置）；格式不对 = null */
fun parseTime(input: String): Double? {
    val s = input.trim().replace('：', ':')
    if (s.isEmpty()) return -1.0
    val parts = s.split(':')
    if (parts.size > 3) return null
    var total = 0.0
    for (p in parts) {
        val v = p.trim().toDoubleOrNull() ?: return null
        if (v < 0) return null
        total = total * 60 + v
    }
    return total
}
