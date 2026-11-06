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

    class Probe(val duration: Double, val audio: String?)

    /** 只读文件头：时长 + 第一条音轨的描述（如「aac (LC), 44100 Hz, stereo, fltp, 128 kb/s」） */
    fun probe(ctx: Context, path: String): Probe {
        var dur = -1.0
        var audio: String? = null
        run(ctx, listOf("-hide_banner", "-nostdin", "-i", path), onErr = { line ->
            if (dur < 0) parseDuration(line)?.let { dur = it }
            if (audio == null) parseAudio(line)?.let { audio = it }
        })
        return Probe(dur, audio)
    }

    /** 秒数写成 ffmpeg 参数（不用科学计数法、不带千分位） */
    fun t(sec: Double): String =
        String.format(Locale.US, "%.6f", sec).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /** 导出用滤镜：拆出各片段 → 拼接 → 音量 → 淡入淡出（与 PC 版完全一致） */
    fun buildFilter(e: EditState): String {
        val sb = StringBuilder()
        val n = e.segs.size
        if (n > 1) {
            sb.append("[0:a]asplit=").append(n)
            for (i in 0 until n) sb.append("[i").append(i).append(']')
            sb.append(';')
        }
        for (i in 0 until n) {
            sb.append(if (n > 1) "[i$i]" else "[0:a]")
                .append("atrim=start=").append(t(e.segs[i].a)).append(":end=").append(t(e.segs[i].b))
                .append(",asetpts=PTS-STARTPTS[s").append(i).append("];")
        }
        for (i in 0 until n) sb.append("[s").append(i).append(']')
        sb.append(if (n > 1) "concat=n=$n:v=0:a=1" else "anull")
        if (abs(e.gainDb) > 0.001) sb.append(",volume=").append(t(e.gainDb)).append("dB")
        if (e.fadeIn > 0) sb.append(",afade=t=in:st=0:d=").append(t(e.fadeIn))
        if (e.fadeOut > 0) sb.append(",afade=t=out:st=").append(t(e.length - e.fadeOut)).append(":d=").append(t(e.fadeOut))
        sb.append("[out]")
        return sb.toString()
    }
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
