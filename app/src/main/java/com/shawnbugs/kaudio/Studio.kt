package com.shawnbugs.kaudio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.shawnbugs.kaudio.core.EditState
import com.shawnbugs.kaudio.core.Ffmpeg
import com.shawnbugs.kaudio.core.FormatInfo
import com.shawnbugs.kaudio.core.Formats
import com.shawnbugs.kaudio.core.PREVIEW_RATE
import com.shawnbugs.kaudio.core.PcmPlayer
import com.shawnbugs.kaudio.core.Peaks
import com.shawnbugs.kaudio.core.fmtTime
import com.shawnbugs.kaudio.core.readPeaks
import com.shawnbugs.kaudio.core.writeSilentWav
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 列表里的一个文件。path 是复制到 App 缓存里的副本（ffmpeg 只认真实路径）。
 * audio / video 是 ffmpeg 报的音轨 / 视频流描述，没有就是 null（两个不会同时为 null）。
 */
class FileItem(val id: Long, val name: String, val size: Long, val path: File, val audio: String?, val video: String?) {
    var duration = -1.0                 // 导入时探测，打开波形后换成解码出的精确值
    var edit: EditState? = null         // 各自的编辑结果，没打开过为 null（导出整段）
    val undo = ArrayList<EditState>()
    val redo = ArrayList<EditState>()
    var rev by mutableIntStateOf(0)     // 列表条目刷新用
        private set

    fun touch() { rev++ }
    val edited: Boolean get() = edit?.isOriginal(duration) == false
    val ext: String get() = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()
    val baseName: String get() = name.substringBeforeLast('.').ifEmpty { name }
}

class ExportItem(
    val name: String, val baseName: String, val ext: String, val path: File, val edit: EditState?, val srcDur: Double,
    val audio: String?, val video: String?,
)

/** height / quality / keepAudio 只对视频格式有用（同 PC 版）；merge = 把 items 按顺序合并成一个音频文件（0.4.0） */
data class Job(
    val fmt: FormatInfo, val channels: Int, val rate: String?, val bitrate: String?,
    val height: Int, val quality: Int, val keepAudio: Boolean,
    val items: List<ExportItem>,
    val merge: Boolean = false,
)

class ExportResult(val name: String, val uri: Uri, val mime: String, val where: String)

/*
 * 全 App 共用的状态和操作（对应 PC 版 MainForm 的逻辑部分）。
 * 界面状态只在主线程改；后台线程（导入、解码、导出）通过 main.post 回到主线程。
 */
object Studio {
    const val OUT_DIR = "K剪音"

    lateinit var app: Context
        private set
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var inited = false
    private var nextId = 1L

    val files = mutableStateListOf<FileItem>()
    var current by mutableStateOf<FileItem?>(null)
        private set
    val wave = WaveModel()
    var info by mutableStateOf("")
        private set
    var importing by mutableIntStateOf(0)
        private set
    val logs = mutableStateListOf<String>()

    // 试听
    private val player = PcmPlayer()
    var playing by mutableStateOf(false)
        private set
    private var playFrom = 0.0
    private var tempWav: File? = null
    private var tempOff = 0L
    @Volatile private var loadSeq = 0
    @Volatile private var decodeProc: Process? = null

    // 导出参数（记住上次的选择）
    var fmtIdx by mutableIntStateOf(0)
    var rateIdx by mutableIntStateOf(0)
    var bitrateIdx by mutableIntStateOf(0)
    var chIdx by mutableIntStateOf(0)
    var resIdx by mutableIntStateOf(0)
    var qualityIdx by mutableIntStateOf(1)
    var vAudioIdx by mutableIntStateOf(0)

    // 视频画面（同 PC 版：播放头处取一帧；只保留最新一次请求，播放时自然成了「幻灯片」）
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var bigPreview by mutableStateOf(false)
        private set
    private val frameLock = Object()
    private var framePath: String? = null
    private var frameSrc = -1.0
    private var frameW = 0
    private var frameBusy = false
    private var frameShown: String? = null   // 已显示/正在取的画面（路径|源时间|宽度），相同请求不重复取
    @Volatile private var frameProc: Process? = null
    private var lastSelA = -1.0
    private var lastSelB = -1.0

    // 导出
    var exporting by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var progressText by mutableStateOf("")
        private set
    val results = mutableStateListOf<ExportResult>()
    @Volatile var pendingJob: Job? = null
    @Volatile private var cancelExport = false
    @Volatile private var exportProc: Process? = null

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        // 上次留下的副本、试听缓存、导出中间文件（新进程里不可能有正在用的）
        File(app.cacheDir, "in").deleteRecursively()
        File(app.cacheDir, "out").deleteRecursively()
        app.cacheDir.listFiles { f -> f.name.startsWith("preview_") }?.forEach { it.delete() }
        val sp = prefs()
        fmtIdx = Formats.indexOf(sp.getString("format", null))
        rateIdx = Formats.rates.indexOf(sp.getString("rate", null)).coerceAtLeast(0)
        bitrateIdx = Formats.bitrates.indexOf(sp.getString("bitrate", null)).coerceAtLeast(0)
        chIdx = Formats.channels.indexOf(sp.getString("channels", null)).coerceAtLeast(0)
        resIdx = Formats.resolutions.indexOf(sp.getString("resolution", null)).coerceAtLeast(0)
        qualityIdx = Formats.qualities.indexOf(sp.getString("quality", "标准")).coerceAtLeast(0)
        vAudioIdx = Formats.videoAudio.indexOf(sp.getString("videoaudio", null)).coerceAtLeast(0)
        if (!Ffmpeg.available(app)) log("找不到内置的 ffmpeg，安装包可能不完整")
    }

    private fun prefs() = app.getSharedPreferences("kaudio", Context.MODE_PRIVATE)

    fun saveSettings() {
        prefs().edit()
            .putString("format", Formats.list[fmtIdx].name)
            .putString("rate", Formats.rates[rateIdx])
            .putString("bitrate", Formats.bitrates[bitrateIdx])
            .putString("channels", Formats.channels[chIdx])
            .putString("resolution", Formats.resolutions[resIdx])
            .putString("quality", Formats.qualities[qualityIdx])
            .putString("videoaudio", Formats.videoAudio[vAudioIdx])
            .apply()
    }

    fun log(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { log(msg) }; return }
        logs.add(msg)
        while (logs.size > 300) logs.removeAt(0)
    }

    fun toast(msg: String) {
        main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }

    // ================= 文件列表 =================

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (exporting) { toast("正在导出，等导出完再添加"); return }
        importing += uris.size
        io.execute {
            for (u in uris) {
                var name = "?"
                try {
                    val (n, size) = queryNameSize(u)
                    name = n
                    if (files.toList().any { it.name == name && it.size == size && size >= 0 }) {
                        log("已在列表里：$name")
                        continue
                    }
                    val id = synchronized(this) { nextId++ }
                    val dir = File(app.cacheDir, "in/$id").apply { mkdirs() }
                    val f = File(dir, name.replace('/', '_'))
                    val ins = app.contentResolver.openInputStream(u) ?: throw IllegalStateException("打不开")
                    ins.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    val probe = Ffmpeg.probe(app, f.path)
                    if (probe.audio == null && probe.video == null) {
                        dir.deleteRecursively()
                        log("× $name：没有找到音轨和视频画面")
                        toast("「$name」不是音频或视频文件")
                        continue
                    }
                    val item = FileItem(id, name, f.length(), f, probe.audio, probe.video).apply { duration = probe.duration }
                    main.post {
                        files.add(item)
                        if (current == null) select(item)
                    }
                } catch (e: Exception) {
                    log("× 导入失败：$name  ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    main.post { importing-- }
                }
            }
        }
    }

    private fun queryNameSize(u: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) { }
        if (name.isNullOrBlank()) name = u.lastPathSegment?.substringAfterLast('/')
        if (name.isNullOrBlank()) name = "audio_${System.currentTimeMillis()}"
        return name!! to size
    }

    fun select(item: FileItem) {
        if (item == current) return
        loadWave(item)
    }

    fun remove(item: FileItem) {
        if (exporting) return
        val i = files.indexOf(item)
        if (i < 0) return
        if (item == current) unload(true)
        files.removeAt(i)
        item.path.parentFile?.deleteRecursively()
        if (current == null && files.isNotEmpty()) select(files[min(i, files.size - 1)])
    }

    fun clearAll() {
        if (exporting) return
        unload(true)
        files.forEach { it.path.parentFile?.deleteRecursively() }
        files.clear()
    }

    // ================= 波形加载 =================

    private fun unload(resetUi: Boolean) {
        player.stop()
        playing = false
        loadSeq++  // 让进行中的解码作废
        val p = decodeProc
        decodeProc = null
        Ffmpeg.kill(p)
        tempWav?.delete()
        tempWav = null
        clearFrame()
        if (!resetUi) return
        current = null
        info = ""
        wave.reset(WaveModel.EMPTY_MSG)
    }

    private fun loadWave(item: FileItem) {
        unload(false)
        current = item
        val seq = ++loadSeq
        info = ""
        wave.reset("正在读取音频…")
        if (!Ffmpeg.available(app)) { wave.reset("找不到内置的 ffmpeg"); return }
        if (!item.path.exists()) { wave.reset("文件不存在了，请重新添加"); return }

        val tmp = File(app.cacheDir, "preview_$seq.wav")
        val args = listOf(
            "-hide_banner", "-nostdin", "-y", "-i", item.path.path,
            "-vn", "-sn", "-dn", "-ac", "2", "-ar", "$PREVIEW_RATE", "-c:a", "pcm_s16le", "-map_metadata", "-1",
            "-progress", "pipe:1", "-nostats", tmp.path,
        )
        // 没有声音的视频：生成一段等长的静音当「波形」，照样可以剪（同 PC 版）
        val silent = item.audio == null
        thread(isDaemon = true) {
            var last = ""
            val code = if (silent) {
                try {
                    if (item.duration <= 0) throw IllegalStateException("读不出这个视频的时长")
                    writeSilentWav(tmp, item.duration); 0
                } catch (e: Exception) { last = e.message ?: e.toString(); -1 }
            } else try {
                Ffmpeg.run(
                    app, args,
                    started = { p -> decodeProc = p; if (seq != loadSeq) Ffmpeg.kill(p) },
                    onErr = { line -> if (line.isNotBlank()) last = line.trim() },
                    onTime = { t ->
                        val d = item.duration
                        if (d > 0) {
                            val pct = min(99, (t / d * 100).toInt())
                            main.post { if (seq == loadSeq) wave.reset("正在解码音频 $pct%") }
                        }
                    },
                )
            } catch (e: Exception) { last = e.message ?: e.toString(); -1 }

            var peaks: Peaks? = null
            if (code == 0 && seq == loadSeq) {
                try {
                    peaks = if (silent) {
                        // 全是零，不用真去读几百 MB
                        val frames = (tmp.length() - 44) / 4
                        val nb = ((frames + PREVIEW_RATE / 100 - 1) / (PREVIEW_RATE / 100)).toInt()
                        Peaks(FloatArray(nb), FloatArray(nb), 0.01, frames, 44)
                    } else readPeaks(tmp, PREVIEW_RATE / 100) { seq != loadSeq }
                } catch (e: Exception) { last = e.message ?: e.toString() }
            }
            main.post {
                if (seq != loadSeq) { tmp.delete(); return@post }
                decodeProc = null
                val p = peaks
                if (p == null || p.frames <= 0) {
                    tmp.delete()
                    wave.reset("无法读取这个文件\n$last")
                    return@post
                }
                tempWav = tmp
                tempOff = p.dataOffset
                item.duration = p.frames / PREVIEW_RATE.toDouble()
                if (item.edit == null) item.edit = EditState(item.duration)
                wave.setData(p, item.edit!!)
                lastSelA = -1.0; lastSelB = -1.0
                val desc = if (item.video != null)
                    Ffmpeg.videoSummary(item.video) + "  ·  " + (item.audio?.let { "音频 $it" } ?: "（没有声音）")
                else item.audio
                info = "原始时长 ${fmtTime(item.duration)}" + (desc?.let { "  ·  $it" } ?: "")
                item.touch()
                requestFrame(0.0)
            }
        }
    }

    // ================= 编辑 =================

    private val ready: Boolean get() = wave.loaded && current?.edit != null

    fun pushUndo() {
        val c = current ?: return
        val e = c.edit ?: return
        c.undo.add(e.clone())
        if (c.undo.size > 200) c.undo.removeAt(0)
        c.redo.clear()
        wave.invalidate()
    }

    /** 编辑后刷新界面；结构变化（删除/裁剪/剪辑点/撤销）会先停止试听 */
    private fun afterEdit(structural: Boolean) {
        val c = current ?: return
        if (structural && playing) pausePlay()
        wave.setEdit(c.edit!!)
        c.touch()
        if (structural) refreshFrame()
    }

    /** 在播放头处加剪辑点；有选区时在选区两端各加一个 */
    fun split() {
        if (!ready) return
        val c = current!!
        val e = c.edit!!.clone()
        val any = if (wave.hasSel) e.splitAt(wave.selStart) or e.splitAt(wave.selEnd) else e.splitAt(currentPos())
        if (!any) { toast("这里已经有剪辑点（或在开头/结尾）"); return }
        pushUndo()
        c.edit = e
        afterEdit(true)
    }

    fun deleteSel() {
        if (!ready) return
        if (!wave.hasSel) { toast("先在波形上拖动框选要删除的部分"); return }
        if (wave.selStart <= 0.001 && wave.selEnd >= wave.duration - 0.001) { toast("不能删除全部内容"); return }
        val a = wave.selStart
        pushUndo()
        current!!.edit!!.delete(a, wave.selEnd)
        wave.selStart = -1.0; wave.selEnd = -1.0
        wave.playhead = a
        afterEdit(true)
    }

    fun cropSel() {
        if (!ready || !wave.hasSel) return
        pushUndo()
        current!!.edit!!.crop(wave.selStart, wave.selEnd)
        wave.selStart = -1.0; wave.selEnd = -1.0
        wave.playhead = 0.0
        afterEdit(true)
        wave.zoomAll()
    }

    fun undoRedo(undo: Boolean) {
        if (!ready) return
        val c = current!!
        val from = if (undo) c.undo else c.redo
        val to = if (undo) c.redo else c.undo
        if (from.isEmpty()) return
        to.add(c.edit!!)
        c.edit = from.removeAt(from.size - 1)
        wave.selStart = -1.0; wave.selEnd = -1.0
        afterEdit(true)
    }

    val canUndo: Boolean get() = ready && current!!.undo.isNotEmpty()
    val canRedo: Boolean get() = ready && current!!.redo.isNotEmpty()
    val hasFx: Boolean get() = ready && current!!.edit!!.hasFx

    fun stepGain(db: Int) {
        if (!ready) return
        pushUndo()
        val e = current!!.edit!!
        e.gainDb = (Math.round(e.gainDb) + db).toDouble().coerceIn(-40.0, 20.0)
        afterEdit(false)
    }

    fun resetFx() {
        if (!ready || !hasFx) return
        pushUndo()
        val e = current!!.edit!!
        e.gainDb = 0.0; e.fadeIn = 0.0; e.fadeOut = 0.0
        afterEdit(false)
    }

    /** 输入框改音量/淡入/淡出（null = 不改） */
    fun applyFx(gain: Double?, fadeIn: Double?, fadeOut: Double?) {
        if (!ready) return
        val e = current!!.edit!!
        val g = (gain ?: e.gainDb).coerceIn(-40.0, 20.0)
        val l = e.length
        val fi = (fadeIn ?: e.fadeIn).coerceIn(0.0, l)
        val fo = (fadeOut ?: e.fadeOut).coerceIn(0.0, max(0.0, l - fi))
        if (abs(g - e.gainDb) < 1e-6 && abs(fi - e.fadeIn) < 1e-6 && abs(fo - e.fadeOut) < 1e-6) return
        pushUndo()
        e.gainDb = g; e.fadeIn = fi; e.fadeOut = fo
        afterEdit(false)
    }

    /** 波形上拖淡入淡出方块时调用（开始拖时已经 pushUndo） */
    fun fadeDragged() { current?.touch(); wave.invalidate() }

    /** 输入框改选区；返回错误提示，null = 成功 */
    fun applySel(a0: Double, b0: Double): String? {
        if (!ready) return null
        var a = a0
        var b = b0
        if (a < 0 && b < 0) { wave.clearSelection(); return null }
        if (a < 0) a = 0.0
        if (b < 0 || b > wave.duration) b = wave.duration
        if (b <= a) return "结束时间必须大于开始时间"
        wave.setSelection(a, b)
        wave.follow(a)
        return null
    }

    fun selectAll() { if (ready) wave.setSelection(0.0, wave.duration) }

    private fun currentPos(): Double {
        if (player.playing) { val p = player.position; if (p >= 0) return p }
        return wave.playhead
    }

    /** 标入点：选区开始 = 播放头 */
    fun markIn() {
        if (!ready) return
        val p = currentPos()
        wave.setSelection(p, if (wave.hasSel && wave.selEnd > p + 0.01) wave.selEnd else wave.duration)
    }

    /** 标出点：选区结束 = 播放头 */
    fun markOut() {
        if (!ready) return
        val p = currentPos()
        wave.setSelection(if (wave.hasSel && wave.selStart < p - 0.01) wave.selStart else 0.0, p)
    }

    // ================= 试听 =================

    fun togglePlay() {
        if (player.playing) { pausePlay(); return }
        if (!ready || tempWav == null) return
        var from = wave.playhead
        var to = wave.duration
        if (wave.hasSel) {
            if (from < wave.selStart || from >= wave.selEnd - 0.05) from = wave.selStart
            to = wave.selEnd
        } else if (from >= wave.duration - 0.05) from = 0.0
        startPlay(from, to)
    }

    private fun startPlay(from: Double, to: Double) {
        val f = tempWav ?: return
        if (!player.start(f, tempOff, current!!.edit!!, from, to)) { toast("无法播放"); return }
        playFrom = from
        wave.playhead = from
        wave.follow(from)
        playing = true
    }

    fun pausePlay() {
        val pos = player.position
        player.stop()
        playing = false
        if (pos >= 0) wave.playhead = min(pos, wave.duration)
        wave.invalidate()
    }

    fun stopPlay() {
        player.stop()
        playing = false
        seek(if (wave.hasSel) wave.selStart else 0.0)
    }

    fun seek(t: Double) {
        if (!wave.loaded) return
        wave.playhead = t.coerceIn(0.0, wave.duration)
        wave.follow(wave.playhead)
        onSeek(wave.playhead)
    }

    /** 波形上点了一下（播放中会从这里接着播） */
    fun onSeek(t: Double) {
        wave.invalidate()
        if (!playing) { requestFrame(t); return }
        startPlay(t, if (wave.hasSel && t >= wave.selStart && t < wave.selEnd) wave.selEnd else wave.duration)
    }

    /** 播放中每帧调用：移动播放头；播完回到这次播放的起点，方便反复试听 */
    fun tick() {
        if (!playing) return
        if (!player.playing) {
            playing = false
            wave.playhead = playFrom
            wave.follow(playFrom)
            requestFrame(playFrom)
            return
        }
        val pos = player.position
        if (pos >= 0) { wave.playhead = min(pos, wave.duration); wave.follow(wave.playhead); requestFrame(wave.playhead) }
    }

    // ================= 视频画面 =================

    /** 当前文件是视频且波形已加载 */
    val isVideo: Boolean get() = current?.video != null && wave.loaded

    private fun frameWidth(): Int =
        if (bigPreview) app.resources.displayMetrics.widthPixels.coerceIn(720, 1920) else 720

    fun refreshFrame() { requestFrame(currentPos()) }

    /** 取时间线 t 处的画面。后台一个线程循环取，只取最新的请求（同 PC 版 RequestFrame） */
    fun requestFrame(t: Double) {
        val c = current ?: return
        if (c.video == null || !ready || !Ffmpeg.available(app)) return
        val src = Math.round(c.edit!!.sourceAt(t) * 1000) / 1000.0
        val width = frameWidth()
        val path = c.path.path
        val key = "$path|$src|$width"
        if (key == frameShown) return
        frameShown = key
        synchronized(frameLock) {
            framePath = path
            frameSrc = src
            frameW = width
            if (frameBusy) return
            frameBusy = true
        }
        thread(isDaemon = true, name = "frame") {
            while (true) {
                val req = synchronized(frameLock) {
                    val fp = framePath
                    if (frameSrc < 0 || fp == null) { frameBusy = false; null }
                    else Triple(fp, frameSrc, frameW).also { frameSrc = -1.0 }
                } ?: break
                val (p, s, w) = req
                val bytes = Ffmpeg.grabFrame(app, p, s, w) { frameProc = it }
                frameProc = null
                val bmp = bytes?.let { b -> try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null } }
                if (bmp != null) main.post { if (current?.path?.path == p) frame = bmp.asImageBitmap() }
            }
        }
    }

    private fun clearFrame() {
        synchronized(frameLock) { framePath = null; frameSrc = -1.0 }
        Ffmpeg.kill(frameProc)
        frame = null
        frameShown = null
        bigPreview = false
    }

    /** 拖选区边缘时显示边缘处的画面，方便对准剪辑点（波形手势里每次改了选区都调） */
    fun selChanged() {
        val a = wave.selStart
        val b = wave.selEnd
        val aMoved = abs(a - lastSelA) > 1e-6
        val bMoved = abs(b - lastSelB) > 1e-6
        lastSelA = a; lastSelB = b
        if (!wave.hasSel || playing) return
        if (aMoved) requestFrame(a) else if (bMoved) requestFrame(max(a, b - 0.02))
    }

    /** 逐帧移动播放头：按视频帧率（读不出时按 25 帧，即 PC 版的 0.04 秒） */
    fun stepFrame(dir: Int) {
        if (!ready || playing) return
        val step = current?.video?.let { Ffmpeg.videoFps(it) }?.let { 1.0 / it } ?: 0.04
        seek(wave.playhead + dir * step)
    }

    fun openBigPreview() {
        if (!isVideo) return
        bigPreview = true
        refreshFrame()
    }

    fun closeBigPreview() {
        bigPreview = false
        refreshFrame()   // 换回小尺寸，省点内存
    }

    // ================= 导出 =================

    /** all：导出列表里全部文件；merge：把全部文件按列表顺序合并成一个音频文件 */
    fun startExport(all: Boolean, merge: Boolean = false) {
        if (exporting) return
        if (!Ffmpeg.available(app)) { toast("找不到内置的 ffmpeg"); return }
        val items = if (all || merge) files.toList() else listOfNotNull(current)
        if (items.isEmpty()) { toast(if (all || merge) "请先添加文件" else "请先选择一个文件"); return }
        val fmt = Formats.list[fmtIdx]
        if (merge) {
            if (items.size < 2) { toast("至少添加两个文件才能合并"); return }
            if (fmt.video || fmt.copy) { toast("合并要选一种音频格式（MP3、WAV 等），在上面「格式」里选"); return }
        }
        if (playing) pausePlay()
        val job = Job(
            merge = merge,
            fmt = fmt,
            channels = chIdx,
            rate = if (rateIdx > 0) Formats.rates[rateIdx] else null,
            bitrate = if (bitrateIdx > 0) Formats.bitrates[bitrateIdx] else null,
            height = Formats.resHeights[resIdx],
            quality = qualityIdx,
            keepAudio = vAudioIdx == 0,
            items = items.map {
                ExportItem(it.name, it.baseName, it.ext, it.path, if (it.edited) it.edit!!.clone() else null, it.duration, it.audio, it.video)
            },
        )
        saveSettings()
        pendingJob = job
        cancelExport = false
        exporting = true
        progress = 0f
        progressText = "准备导出…"
        ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java))
    }

    fun stopExport() {
        cancelExport = true
        Ffmpeg.kill(exportProc)
    }

    /** 在导出服务的后台线程里跑；返回给通知用的一句话总结 */
    fun runBatch(job: Job, onProgress: (Float, String) -> Unit): String {
        val t0 = System.currentTimeMillis()
        File(app.cacheDir, "out").mkdirs()
        val report = { frac: Float, text: String ->
            main.post { progress = frac; progressText = text }
            onProgress(frac, text)
        }
        val (ok, fail) = if (job.merge) runMerge(job, report) else runEach(job, report)
        val stopped = cancelExport
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        log(String.format("完成：成功 %d，失败 %d，用时 %.1f 秒%s", ok, fail, sec, if (stopped) "（已手动停止）" else ""))
        val text = when {
            stopped -> "已停止"
            fail > 0 -> "完成，$fail 个失败"
            else -> "完成"
        }
        main.post {
            exporting = false
            if (!stopped) progress = 1f
            progressText = text
        }
        val where = when {
            job.fmt.split -> "「Movies/$OUT_DIR」和「音乐/$OUT_DIR」"
            job.fmt.video -> "「Movies/$OUT_DIR」"
            else -> "「音乐/$OUT_DIR」"
        }
        return when {
            stopped -> "导出已停止，成功 $ok 个"
            fail > 0 -> "导出完成：成功 $ok 个，失败 $fail 个"
            else -> "导出完成：$ok 个文件，在$where"
        }
    }

    /** 第 k 步（共 steps 步）的进度回调：ffmpeg 报 0~1，换算成总进度和「label  45%」 */
    private fun stepProgress(k: Int, steps: Int, label: String, report: (Float, String) -> Unit): (Double) -> Unit {
        var last = -1
        return { frac ->
            val pct = (frac * 100).roundToInt()
            if (pct != last) {
                last = pct
                report(((k + frac) / steps).toFloat(), "$label  $pct%")
            }
        }
    }

    /** 逐个文件导出；音视频分离时一个文件出两个。返回（成功数，失败数），按输出文件算 */
    private fun runEach(job: Job, report: (Float, String) -> Unit): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        val n = job.items.size
        log("—— 开始导出 $n 个文件 ——")
        for (i in 0 until n) {
            if (cancelExport) break
            val item = job.items[i]
            log("[${i + 1}/$n] ${item.name}")
            if (!item.path.exists()) { log("  × 文件不存在"); fail++; continue }
            if (job.fmt.split) {
                if (item.video == null) { log("  × 不是视频文件，没有画面可分离"); fail++; continue }
                if (item.audio == null) { log("  × 这个视频没有声音，不用分离"); fail++; continue }
                // 画面：无损快剪去掉声音（存 Movies）；声音：保持原格式（存音乐）。剪辑和效果两边都照样套用
                val vJob = job.copy(fmt = Formats.videoCopy, keepAudio = false)
                if (exportOne(vJob, item, "_无声视频", stepProgress(2 * i, 2 * n, "${i + 1}/$n 画面", report))) ok++ else fail++
                if (cancelExport) break
                val aJob = job.copy(fmt = Formats.keepAudio)
                if (exportOne(aJob, item, "_音频", stepProgress(2 * i + 1, 2 * n, "${i + 1}/$n 声音", report))) ok++ else fail++
                continue
            }
            if (job.fmt.video && item.video == null) { log("  × 不是视频文件，跳过（音频文件请选 MP3 等音频格式）"); fail++; continue }
            if (!job.fmt.video && item.audio == null) { log("  × 这个视频没有声音，无法导出音频"); fail++; continue }
            if (exportOne(job, item, "", stepProgress(i, n, "${i + 1}/$n", report))) ok++ else fail++
        }
        return ok to fail
    }

    /** 导出一个文件并存进媒体库；suffix 加在文件名后面（扩展名前），成功返回 true */
    private fun exportOne(job: Job, item: ExportItem, suffix: String, onFrac: (Double) -> Unit): Boolean {
        val ext = outExt(job, item)
        val outName = item.baseName + (if (item.edit != null) "_edit" else "") + suffix + ext
        val tmp = File(app.cacheDir, "out/${System.nanoTime()}$ext")
        val err = runFfmpeg(exportArgs(job, item, tmp), item.edit?.length ?: -1.0, onFrac)
        return save(err, tmp, outName, ext, job.fmt.video)
    }

    /**
     * 把列表里的文件按顺序接成一个音频文件（0.4.0）。每个文件先套用自己的剪辑和效果，
     * 再统一采样率和声道（concat 要求一致；没选采样率时用 44100），最后按所选格式编码一次。
     */
    private fun runMerge(job: Job, report: (Float, String) -> Unit): Pair<Int, Int> {
        log("—— 合并 ${job.items.size} 个文件 ——")
        val items = job.items.filter { it.path.exists() && it.audio != null }
        for (it in job.items) if (it !in items) log("  跳过 ${it.name}：" + if (!it.path.exists()) "文件不存在" else "没有声音")
        if (items.size < 2) { log("  × 有声音的文件不到两个，没法合并"); return 0 to 1 }
        val norm = "aformat=sample_fmts=fltp:sample_rates=${job.rate ?: "44100"}:channel_layouts=" +
            (if (job.channels == 1) "mono" else "stereo")
        val f = StringBuilder()
        val a = arrayListOf("-hide_banner", "-nostdin", "-y")
        items.forEachIndexed { k, it ->
            log("  ${k + 1}. ${it.name}")
            a += listOf("-i", it.path.path)
            val e = it.edit
            if (e == null) f.append("[$k:a]")
            else f.append(Ffmpeg.buildFilter(e, false, true, null, k, "f${k}_")).append(";[f${k}_aout]")
            f.append(norm).append("[m$k];")
        }
        for (k in items.indices) f.append("[m$k]")
        f.append("concat=n=").append(items.size).append(":v=0:a=1[aout]")
        a += listOf("-filter_complex", f.toString(), "-map", "[aout]")
        a += encodeArgs(job)
        val ext = job.fmt.ext
        val outName = items[0].baseName + "_等${items.size}个合并" + ext
        val tmp = File(app.cacheDir, "out/${System.nanoTime()}$ext")
        a += listOf("-progress", "pipe:1", "-nostats", tmp.path)
        val lens = items.map { it.edit?.length ?: it.srcDur }
        val total = if (lens.all { it > 0 }) lens.sum() else -1.0
        val err = runFfmpeg(a, total, stepProgress(0, 1, "合并", report))
        return if (save(err, tmp, outName, ext, false)) 1 to 0 else 0 to 1
    }

    /** ffmpeg 跑完后：成功就存进「Movies」或「音乐」并加进结果列表；中间文件都删掉 */
    private fun save(err: String?, tmp: File, outName: String, ext: String, video: Boolean): Boolean {
        try {
            if (err != null) {
                log(if (cancelExport) "  已停止" else "  × 失败：\n$err")
                return false
            }
            val r = if (video) saveToMovies(tmp, outName, ext) else saveToMusic(tmp, outName, ext)
            log("  √ ${r.where}/${r.name}")
            main.post { results.add(0, r) }
            return true
        } catch (e: Exception) {
            log("  × 保存失败：${e.message ?: e}")
            return false
        } finally {
            tmp.delete()
        }
    }

    /** 音频编码参数：编码器、采样率、声道、比特率 */
    private fun encodeArgs(job: Job): List<String> {
        val a = arrayListOf("-c:a", job.fmt.codec)
        job.rate?.let { a += listOf("-ar", it) }
        if (job.channels > 0) a += listOf("-ac", job.channels.toString())
        if (job.bitrate != null && !job.fmt.lossless) a += listOf("-b:a", job.bitrate)
        return a
    }

    private fun outExt(job: Job, item: ExportItem): String = when {
        job.fmt.video && job.fmt.copy -> {
            // 无损快剪沿用原容器；不得不重新编码、原容器又装不了 H.264 时改存 MP4（同 PC 版）
            val ext = item.ext
            when {
                ext.isEmpty() -> if (canStreamCopy(job, item)) ".mkv" else ".mp4"
                !canStreamCopy(job, item) && ext !in Formats.videoContainers -> ".mp4"
                else -> ext
            }
        }
        job.fmt.copy -> Formats.keepExt(item.ext, item.audio, item.edit != null && !item.edit.isPureTrim)
        else -> job.fmt.ext
    }

    /** 视频无损快剪：只截了一段（或没剪）、没加效果，才能不重新编码 */
    private fun canStreamCopy(job: Job, item: ExportItem): Boolean =
        job.fmt.video && job.fmt.copy && (item.edit == null || item.edit.isPureTrim)

    /** 视频导出参数（同 PC 版 BuildVideoArgs），不含进度和输出文件 */
    private fun videoArgs(job: Job, item: ExportItem, output: File): ArrayList<String> {
        val e = item.edit
        val audio = job.keepAudio && item.audio != null
        val mp4 = output.name.endsWith(".mp4", true) || output.name.endsWith(".mov", true)
        val a = arrayListOf("-hide_banner", "-nostdin", "-y")
        val trimOnly = e == null || e.isPureTrim
        if (trimOnly && e != null) {
            // 输入端定位：快，且重新编码时是精确的
            if (e.segs[0].a > 0.0005) a += listOf("-ss", Ffmpeg.t(e.segs[0].a))
            if (e.segs[0].b < item.srcDur - 0.001) a += listOf("-to", Ffmpeg.t(e.segs[0].b))
        }
        a += listOf("-i", item.path.path)

        if (canStreamCopy(job, item)) {
            a += listOf("-map", "0:v:0")
            if (audio) a += listOf("-map", "0:a?") else a += "-an"
            a += listOf("-c", "copy", "-avoid_negative_ts", "make_zero")
            if (mp4) a += listOf("-movflags", "+faststart")
            return a
        }

        // 重新编码。无损快剪模式下被迫重编码时：H.264 高画质、原分辨率
        val copyFallback = job.fmt.copy
        if (copyFallback) log("  删了中间部分或加了效果，无法无损快剪，改为高画质重新编码")
        val codec = if (copyFallback) "libx264" else job.fmt.codec
        val q = if (copyFallback) 0 else job.quality
        val scale = if (copyFallback) null else Ffmpeg.scaleFilter(job.height)
        if (trimOnly) {
            a += listOf("-map", "0:v:0")
            if (audio) a += listOf("-map", "0:a:0?") else a += "-an"
            if (scale != null) a += listOf("-vf", scale)
        } else {
            a += listOf("-filter_complex", Ffmpeg.buildFilter(e!!, true, audio, scale), "-map", "[vout]")
            if (audio) a += listOf("-map", "[aout]") else a += "-an"
        }
        if (codec == "libx265") {
            a += listOf("-c:v", "libx265", "-preset", "fast", "-crf", Formats.crfX265[q].toString(), "-x265-params", "log-level=error")
            if (mp4) a += listOf("-tag:v", "hvc1")   // 苹果设备需要这个标记才能播 H.265
        } else {
            a += listOf("-c:v", "libx264", "-preset", "fast", "-crf", Formats.crfX264[q].toString())
        }
        a += listOf("-pix_fmt", "yuv420p")
        if (audio) a += listOf("-c:a", "aac", "-b:a", "192k")
        if (mp4) a += listOf("-movflags", "+faststart")
        return a
    }

    /** 导出一个文件的完整 ffmpeg 参数（最后一个是输出文件） */
    private fun exportArgs(job: Job, item: ExportItem, output: File): List<String> {
        val e = item.edit
        val filter = e != null && !e.isPureTrim
        // 视频的定位、滤镜、编码参数都在 videoArgs 里拼好
        val a = if (job.fmt.video) videoArgs(job, item, output) else arrayListOf("-hide_banner", "-nostdin", "-y", "-i", item.path.path)
        if (!job.fmt.video) {
            if (filter) {
                a += listOf("-filter_complex", Ffmpeg.buildFilter(e!!, false, true, null), "-map", "[aout]")
            } else {
                if (e != null) {
                    if (e.segs[0].a > 0.0005) a += listOf("-ss", Ffmpeg.t(e.segs[0].a))
                    if (e.segs[0].b < item.srcDur - 0.001) a += listOf("-to", Ffmpeg.t(e.segs[0].b))
                }
                a += listOf("-vn", "-sn", "-dn")
            }
            if (job.fmt.copy) {
                if (!filter) a += listOf("-c:a", "copy")
                else log("  已剪辑/加效果，按原格式重新编码")
            } else {
                a += encodeArgs(job)
            }
        }
        a += listOf("-progress", "pipe:1", "-nostats", output.path)
        return a
    }

    /** 跑一次导出用的 ffmpeg：成功返回 null，失败返回最后几行输出。total = 输出时长，不知道就传负数（改用 ffmpeg 报的输入时长） */
    private fun runFfmpeg(a: List<String>, total: Double, onFrac: (Double) -> Unit): String? {
        val output = File(a.last())
        var duration = -1.0
        val tail = ArrayDeque<String>()
        val code = try {
            Ffmpeg.run(
                app, a,
                started = { exportProc = it; if (cancelExport) Ffmpeg.kill(it) },
                onErr = { line ->
                    synchronized(tail) { tail.addLast(line); if (tail.size > 10) tail.removeFirst() }
                    if (duration < 0) Ffmpeg.parseDuration(line)?.let { duration = it }
                },
                onTime = { t ->
                    val d = if (total > 0) total else duration
                    if (d > 0) onFrac(min(1.0, t / d))
                },
            )
        } catch (ex: Exception) {
            exportProc = null
            return "  无法启动 ffmpeg：${ex.message}"
        }
        exportProc = null
        if (code == 0 && !cancelExport && output.length() > 0) return null
        return synchronized(tail) { "  " + tail.joinToString("\n  ") }
    }

    /** 存进公共「音乐/K剪音」目录（Android 10+ 不需要存储权限）；系统不收的类型改存「下载/K剪音」 */
    private fun saveToMusic(src: File, name: String, ext: String): ExportResult {
        val sysMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.removePrefix("."))
        val mime = if (sysMime != null && sysMime.startsWith("audio/")) sysMime else Formats.mime(ext)
        return try {
            insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/$OUT_DIR", src, name, mime, "音乐/$OUT_DIR")
        } catch (_: Exception) {
            insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Download/$OUT_DIR", src, name, mime, "下载/$OUT_DIR")
        }
    }

    /** 视频存进公共「Movies/K剪音」（相册里能看到）；系统不收的类型改存「下载/K剪音」 */
    private fun saveToMovies(src: File, name: String, ext: String): ExportResult {
        val sysMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.removePrefix("."))
        val mime = if (sysMime != null && sysMime.startsWith("video/")) sysMime else Formats.videoMime(ext)
        return try {
            insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/$OUT_DIR", src, name, mime, "Movies/$OUT_DIR")
        } catch (_: Exception) {
            insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Download/$OUT_DIR", src, name, mime, "下载/$OUT_DIR")
        }
    }

    private fun insert(collection: Uri, relPath: String, src: File, name: String, mime: String, where: String): ExportResult {
        val cr = app.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, v) ?: throw IllegalStateException("系统拒绝创建文件")
        try {
            cr.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
                ?: throw IllegalStateException("无法写入")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        // 系统重名时会自动改名（如「xx (1).mp3」），读回实际名字
        var finalName = name
        try {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0) ?: name
            }
        } catch (_: Exception) { }
        return ExportResult(finalName, uri, mime, where)
    }

    /** App 退到后台时停掉试听 */
    fun onBackground() { if (playing) pausePlay() }
}
