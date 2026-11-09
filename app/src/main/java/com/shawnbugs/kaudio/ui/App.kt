package com.shawnbugs.kaudio.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.shawnbugs.kaudio.BuildConfig
import com.shawnbugs.kaudio.ExportResult
import com.shawnbugs.kaudio.FileItem
import com.shawnbugs.kaudio.Studio
import com.shawnbugs.kaudio.core.Formats
import com.shawnbugs.kaudio.core.fmtTime
import com.shawnbugs.kaudio.core.parseTime
import java.util.Locale

const val AUTHOR = "司徒王允"

/** 输入弹框：onOk 返回错误提示（null = 成功，关闭弹框） */
private class InputSpec(
    val title: String,
    val initial: String,
    val hint: String,
    val keyboard: KeyboardType,
    val onOk: (String) -> String?,
)

@Composable
fun App() {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Studio.importUris(uris)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    var input by remember { mutableStateOf<InputSpec?>(null) }
    var removing by remember { mutableStateOf<FileItem?>(null) }
    var about by remember { mutableStateOf(false) }

    // 播放中每帧移动播放头
    val playing = Studio.playing
    LaunchedEffect(playing) {
        if (playing) while (true) { withFrameNanos { }; Studio.tick() }
    }

    val addFiles = { picker.launch(Formats.pickMimes) }
    val export = { all: Boolean, merge: Boolean ->
        // 第一次导出时要通知权限（显示导出进度；拒绝也照样导出）
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Studio.startExport(all, merge)
    }

    Column(Modifier.fillMaxSize().background(A.Bg).systemBarsPadding()) {
        TopBar(onAbout = { about = true })
        FileStrip(onAdd = addFiles, onRemove = { removing = it })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (Studio.files.isEmpty() && Studio.importing == 0) EmptyCard(addFiles)
            else {
                WaveCard()
                EditCard(onInput = { input = it })
            }
            ParamCard()
            ResultsCard(ctx)
            LogCard()
            Spacer(Modifier.height(8.dp))
        }
        ExportBar(export)
    }

    if (Studio.bigPreview && Studio.current?.video != null) BigPreview()
    input?.let { spec -> InputDialog(spec) { input = null } }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除文件") },
            text = { Text("从列表移除「${item.name}」？" + if (item.edited) "\n这个文件的剪辑会丢掉。" else "") },
            confirmButton = { TextButton(onClick = { Studio.remove(item); removing = null }) { Text("移除", color = A.Red) } },
            dismissButton = {
                Row {
                    if (Studio.files.size > 1) TextButton(onClick = { Studio.clearAll(); removing = null }) { Text("清空全部") }
                    TextButton(onClick = { removing = null }) { Text("取消") }
                }
            },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("K剪音 ${BuildConfig.VERSION_NAME}") },
            text = {
                Text(
                    "作者：$AUTHOR\n\n" +
                        "由 PC 版「音视频转换剪辑工具」改编：剪掉不要的部分、调音量、加淡入淡出，再转成想要的格式；视频可以剪完导出成视频，也可以只提取声音，或者把画面和声音分离成两个文件；多个文件可以合并成一个音频。\n\n" +
                        "导出的音频在手机存储的 Music/K剪音 目录（音乐 App 里也能看到），视频在 Movies/K剪音（相册里能看到）。\n\n" +
                        "内置 FFmpeg 7.1（GPL），带 x264 / x265 / LAME / Vorbis / Opus 编码器。不联网。",
                    fontSize = 14.sp,
                )
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun TopBar(onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("K剪音", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = A.Text1)
            Text("剪音频 / 视频 · 加淡入淡出 · 转格式", fontSize = 12.sp, color = A.Text2)
        }
        TextButton(onClick = onAbout) { Text("关于", color = A.Text2) }
    }
}

// ———————————————————————————————— 文件条

@Composable
private fun FileStrip(onAdd: () -> Unit, onRemove: (FileItem) -> Unit) {
    if (Studio.files.isEmpty() && Studio.importing == 0) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Studio.files, key = { it.id }) { FileChip(it, onRemove) }
        if (Studio.importing > 0) item { ImportingChip(Studio.importing) }
        item {
            Box(
                Modifier.height(54.dp).clip(RoundedCornerShape(12.dp)).background(A.BrandSoft)
                    .clickable(enabled = !Studio.exporting, onClick = onAdd).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("＋ 添加", color = A.Brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FileChip(item: FileItem, onRemove: (FileItem) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val r = item.rev
    val selected = Studio.current == item
    val e = item.edit
    val sub: String
    val subColor: Color
    if (item.edited && e != null) {
        sub = "已编辑 · " + fmtTime(e.length) + (if (e.segs.size > 1) " · ${e.segs.size}段" else "") + (if (e.hasFx) " · 效果" else "")
        subColor = A.Brand
    } else {
        sub = (if (item.video != null) "视频 · " else "") + (if (item.duration > 0) "时长 " + fmtTime(item.duration) else " ")
        subColor = A.Text3
    }
    Row(
        Modifier.width(168.dp).height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) A.BrandSoft else A.Card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) A.Brand else A.Line, RoundedCornerShape(12.dp))
            .clickable { Studio.select(item) }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = A.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 11.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = A.Num)
        }
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = !Studio.exporting) { onRemove(item) },
            contentAlignment = Alignment.Center,
        ) { Text("×", fontSize = 18.sp, color = A.Text3) }
    }
}

@Composable
private fun ImportingChip(n: Int) {
    Row(
        Modifier.height(54.dp).clip(RoundedCornerShape(12.dp)).background(A.Card)
            .border(1.dp, A.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = A.Brand, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("正在导入 $n 个…", fontSize = 13.sp, color = A.Text2)
    }
}

@Composable
private fun EmptyCard(onAdd: () -> Unit) {
    Column(
        Modifier.card().clickable(onClick = onAdd).padding(vertical = 40.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("＋", fontSize = 40.sp, color = A.Brand)
        Spacer(Modifier.height(8.dp))
        Text("点这里添加音频 / 视频文件", fontSize = 16.sp, color = A.Text1, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "可以一次选多个。也可以在文件管理、微信里把文件「分享」或「用其他应用打开」到 K剪音。",
            fontSize = 13.sp, color = A.Text2,
        )
    }
}

// ———————————————————————————————— 波形

@Composable
private fun WaveCard() {
    val w = Studio.wave
    @Suppress("UNUSED_VARIABLE") val r = w.rev
    val cur = Studio.current
    val loaded = w.loaded
    Column(Modifier.card().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cur?.name ?: "未选择文件", Modifier.weight(1f),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = A.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text("${fmtTime(w.playhead)} / ${fmtTime(w.duration)}", fontSize = 13.sp, color = A.Text1, style = A.Num)
        }
        if (Studio.info.isNotEmpty()) {
            Text(Studio.info, fontSize = 11.sp, color = A.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        val video = loaded && Studio.isVideo
        if (video) {
            VideoFrame(
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).clickable { Studio.openBigPreview() },
                hint = "点画面全屏",
            )
            Spacer(Modifier.height(8.dp))
        }
        WaveCanvas(w, Modifier.fillMaxWidth().height(if (video) 190.dp else 230.dp).clip(RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(10.dp))
        val tight = PaddingValues(horizontal = 4.dp)
        if (video) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("◀ 上一帧", Modifier.weight(1f), enabled = !Studio.playing, padding = tight) { Studio.stepFrame(-1) }
                Pill("下一帧 ▶", Modifier.weight(1f), enabled = !Studio.playing, padding = tight) { Studio.stepFrame(1) }
                Pill("全屏画面", Modifier.weight(1f), padding = tight) { Studio.openBigPreview() }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (Studio.playing) "暂停" else "播放", Modifier.weight(1.4f), primary = true, enabled = loaded, padding = tight) { Studio.togglePlay() }
            Pill("停止", Modifier.weight(1f), enabled = loaded, padding = tight) { Studio.stopPlay() }
            Pill("全览", Modifier.weight(1f), enabled = loaded, padding = tight) { w.zoomAll() }
            Pill("全选", Modifier.weight(1f), enabled = loaded, padding = tight) { Studio.selectAll() }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("剪辑点", Modifier.weight(1f), enabled = loaded, padding = tight) { Studio.split() }
            Pill("删除", Modifier.weight(1f), danger = true, enabled = loaded && w.hasSel, padding = tight) { Studio.deleteSel() }
            Pill("仅保留", Modifier.weight(1f), enabled = loaded && w.hasSel, padding = tight) { Studio.cropSel() }
            Pill("撤销", Modifier.weight(1f), enabled = Studio.canUndo, padding = tight) { Studio.undoRedo(true) }
            Pill("重做", Modifier.weight(1f), enabled = Studio.canRedo, padding = tight) { Studio.undoRedo(false) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "拖动框选 · 拖底部圆柄调选区 · 双指缩放 · 点按定位 · 双击选中一段 · 拖左右上角小方块调淡入淡出" +
                if (video) "\n画面显示播放头处的一帧；拖选区边线时显示边线处的画面，方便对准剪辑点" else "",
            fontSize = 11.sp, color = A.Text3,
        )
    }
}

/** 视频画面：播放头处的一帧（播放时像幻灯片一样刷新） */
@Composable
private fun VideoFrame(modifier: Modifier, hint: String?) {
    val f = Studio.frame
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (f != null) Image(f, contentDescription = "视频画面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("正在取画面…", fontSize = 12.sp, color = A.Text3)
        if (hint != null) {
            Text(
                hint,
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                fontSize = 11.sp, color = Color(0xCCFFFFFF),
            )
        }
    }
}

/** 全屏看画面（同 PC 版的大窗预览）：逐帧、播放、加剪辑点都能在这里做 */
@Composable
private fun BigPreview() {
    val w = Studio.wave
    @Suppress("UNUSED_VARIABLE") val r = w.rev
    Dialog(
        onDismissRequest = { Studio.closeBigPreview() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            VideoFrame(Modifier.fillMaxWidth().weight(1f), hint = null)
            Column(Modifier.fillMaxWidth().background(A.Card).padding(12.dp)) {
                Text(
                    "${fmtTime(w.playhead)} / ${fmtTime(w.duration)}" + if (w.hasSel) "    选区 ${fmtTime(w.selStart)} – ${fmtTime(w.selEnd)}" else "",
                    fontSize = 13.sp, color = A.Text1, style = A.Num,
                )
                Spacer(Modifier.height(8.dp))
                val tight = PaddingValues(horizontal = 4.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("◀ 上一帧", Modifier.weight(1f), enabled = !Studio.playing, padding = tight) { Studio.stepFrame(-1) }
                    Pill(if (Studio.playing) "暂停" else "播放", Modifier.weight(1f), primary = true, padding = tight) { Studio.togglePlay() }
                    Pill("下一帧 ▶", Modifier.weight(1f), enabled = !Studio.playing, padding = tight) { Studio.stepFrame(1) }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("播放头→开始", Modifier.weight(1f), padding = tight) { Studio.markIn() }
                    Pill("播放头→结束", Modifier.weight(1f), padding = tight) { Studio.markOut() }
                    Pill("剪辑点", Modifier.weight(1f), padding = tight) { Studio.split() }
                }
                Spacer(Modifier.height(6.dp))
                Pill("关闭全屏", Modifier.fillMaxWidth(), padding = tight) { Studio.closeBigPreview() }
            }
        }
    }
}

// ———————————————————————————————— 选区与效果

@Composable
private fun EditCard(onInput: (InputSpec) -> Unit) {
    val w = Studio.wave
    @Suppress("UNUSED_VARIABLE") val r = w.rev
    val loaded = w.loaded
    val e = if (loaded) Studio.current?.edit else null
    val timeHint = "可输入 90、1:30、00:01:30.5；留空 = 不限"
    val timeErr = "时间格式不对，示例：90、1:30、00:01:30.5"
    Column(Modifier.card().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("选区")
            Spacer(Modifier.width(10.dp))
            Text(
                if (w.hasSel) "选中 ${fmtTime(w.selEnd - w.selStart)}" else if (loaded) "在波形上拖动框选" else "",
                fontSize = 12.sp, color = if (w.hasSel) A.Brand else A.Text3, style = A.Num,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("开始")
            ValueBox(if (w.hasSel) fmtTime(w.selStart) else "", Modifier.weight(1f), enabled = loaded) {
                onInput(InputSpec("选区开始", if (w.hasSel) fmtTime(w.selStart) else "", timeHint, KeyboardType.Text) { s ->
                    val a = parseTime(s) ?: return@InputSpec timeErr
                    Studio.applySel(a, if (w.hasSel) w.selEnd else -1.0)
                })
            }
            Label("结束")
            ValueBox(if (w.hasSel) fmtTime(w.selEnd) else "", Modifier.weight(1f), enabled = loaded) {
                onInput(InputSpec("选区结束", if (w.hasSel) fmtTime(w.selEnd) else "", timeHint, KeyboardType.Text) { s ->
                    val b = parseTime(s) ?: return@InputSpec timeErr
                    Studio.applySel(if (w.hasSel) w.selStart else -1.0, b)
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        val tight = PaddingValues(horizontal = 4.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("播放头→开始", Modifier.weight(1f), enabled = loaded, height = 36.dp, padding = tight) { Studio.markIn() }
            Pill("播放头→结束", Modifier.weight(1f), enabled = loaded, height = 36.dp, padding = tight) { Studio.markOut() }
            Pill("取消选区", Modifier.weight(1f), enabled = loaded && w.hasSel, height = 36.dp, padding = tight) { w.clearSelection() }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = A.Line)

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("效果", Modifier.weight(1f))
            Pill("效果归零", enabled = Studio.hasFx, height = 32.dp) { Studio.resetFx() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("音量")
            Pill("−", Modifier.width(44.dp), enabled = loaded, height = 36.dp, padding = PaddingValues(0.dp)) { Studio.stepGain(-1) }
            val g = e?.gainDb ?: 0.0
            ValueBox(if (e == null) "" else (if (g > 0) "+" else "") + trimNum(g, 1), Modifier.width(72.dp), enabled = loaded) {
                onInput(InputSpec("音量（dB）", trimNum(g, 1), "-40 ~ +20。+6 约响一倍，-6 约小一半；波形变红表示削波", KeyboardType.Text) { s ->
                    val v = num(s) ?: return@InputSpec "请输入数字"
                    Studio.applyFx(v, null, null); null
                })
            }
            Pill("+", Modifier.width(44.dp), enabled = loaded, height = 36.dp, padding = PaddingValues(0.dp)) { Studio.stepGain(1) }
            Label("dB")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("淡入")
            ValueBox(e?.let { String.format(Locale.US, "%.2f", it.fadeIn) } ?: "", Modifier.weight(1f), enabled = loaded) {
                onInput(InputSpec("淡入（秒）", trimNum(e?.fadeIn ?: 0.0, 2), "也可以直接拖波形左上角的小方块", KeyboardType.Decimal) { s ->
                    val v = num(s) ?: return@InputSpec "请输入数字"
                    Studio.applyFx(null, v, null); null
                })
            }
            Label("秒")
            Spacer(Modifier.width(4.dp))
            Label("淡出")
            ValueBox(e?.let { String.format(Locale.US, "%.2f", it.fadeOut) } ?: "", Modifier.weight(1f), enabled = loaded) {
                onInput(InputSpec("淡出（秒）", trimNum(e?.fadeOut ?: 0.0, 2), "也可以直接拖波形右上角的小方块", KeyboardType.Decimal) { s ->
                    val v = num(s) ?: return@InputSpec "请输入数字"
                    Studio.applyFx(null, null, v); null
                })
            }
            Label("秒")
        }
    }
}

private fun trimNum(v: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

private fun num(s: String): Double? = s.trim().replace('＋', '+').replace('－', '-').replace('−', '-')
    .removeSuffix("dB").removeSuffix("db").trim().toDoubleOrNull()

@Composable
private fun InputDialog(spec: InputSpec, onClose: () -> Unit) {
    var tf by remember { mutableStateOf(TextFieldValue(spec.initial, TextRange(0, spec.initial.length))) }
    var err by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(spec.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = tf,
                    onValueChange = { tf = it; err = null },
                    singleLine = true,
                    isError = err != null,
                    keyboardOptions = KeyboardOptions(keyboardType = spec.keyboard),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(6.dp))
                Text(err ?: spec.hint, fontSize = 12.sp, color = if (err != null) A.Red else A.Text3)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = spec.onOk(tf.text)
                if (e == null) onClose() else err = e
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

// ———————————————————————————————— 导出参数

@Composable
private fun ParamCard() {
    val fmt = Formats.list[Studio.fmtIdx]
    val busy = Studio.exporting
    Column(Modifier.card().padding(12.dp)) {
        SectionTitle("导出格式")
        Spacer(Modifier.height(8.dp))
        // 视频格式时换成「分辨率 / 画质 / 声音」一组，位置相同（同 PC 版）；音视频分离两边都不重新编码，没有参数可选
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dropdown("格式", Formats.list.map { it.name }, Studio.fmtIdx, Modifier.weight(1f), !busy) { Studio.fmtIdx = it; Studio.saveSettings() }
            if (fmt.split) Spacer(Modifier.weight(1f))
            else if (fmt.video) Dropdown("分辨率", Formats.resolutions, Studio.resIdx, Modifier.weight(1f), !busy && !fmt.copy) { Studio.resIdx = it; Studio.saveSettings() }
            else Dropdown("采样率 (Hz)", Formats.rates, Studio.rateIdx, Modifier.weight(1f), !busy && !fmt.copy) { Studio.rateIdx = it; Studio.saveSettings() }
        }
        Spacer(Modifier.height(8.dp))
        if (!fmt.split) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fmt.video) {
                Dropdown("画质", Formats.qualities, Studio.qualityIdx, Modifier.weight(1f), !busy && !fmt.copy) { Studio.qualityIdx = it; Studio.saveSettings() }
                Dropdown("声音", Formats.videoAudio, Studio.vAudioIdx, Modifier.weight(1f), !busy) { Studio.vAudioIdx = it; Studio.saveSettings() }
            } else {
                Dropdown("比特率", Formats.bitrates, Studio.bitrateIdx, Modifier.weight(1f), !busy && !fmt.copy && !fmt.lossless) { Studio.bitrateIdx = it; Studio.saveSettings() }
                Dropdown("声道", Formats.channels, Studio.chIdx, Modifier.weight(1f), !busy && !fmt.copy) { Studio.chIdx = it; Studio.saveSettings() }
            }
        }
        if (!fmt.split) Spacer(Modifier.height(8.dp))
        val tip = when {
            fmt.split -> "一个视频拆成两个文件：无声视频存 Movies/${Studio.OUT_DIR}，原来的声音存 Music/${Studio.OUT_DIR}。\n" +
                "两边都不重新编码，很快、质量不变；剪过的按剪辑结果拆。删了中间部分或加了效果时，" +
                "画面改成高画质 H.264 重新编码，声音按原格式重新编码"
            fmt.video && fmt.copy -> "\n无损快剪：不重新编码，几秒完成、画质不变；但开头会对齐到最近的关键帧（可能早零点几秒到几秒）。" +
                "删了中间部分或加了效果时会自动改成高画质 H.264 重新编码"
            fmt.codec == "libx265" -> "\nH.265：同样画质文件约小一半，但用手机 CPU 编码比 H.264 慢好几倍；老手机 / 老电脑可能播不了。分辨率只缩小不放大"
            fmt.video -> "\nH.264：兼容性最好，哪里都能播；用手机 CPU 编码，长视频要等一会儿。分辨率只缩小不放大（竖屏按短边算）"
            fmt.copy -> "\n保持原格式：只截一段时不重新编码（无损、很快）；视频文件会直接提取原音轨（如 aac → m4a）"
            else -> "\n视频文件也能选音频格式，直接把声音提取出来。「合并成一个」按列表顺序把全部文件接成一个"
        }
        Text(
            if (fmt.split) tip else "保存到手机存储的 " + (if (fmt.video) "Movies" else "Music") + "/${Studio.OUT_DIR}" + tip,
            fontSize = 11.sp, color = A.Text3,
        )
    }
}

@Composable
private fun Dropdown(label: String, items: List<String>, index: Int, modifier: Modifier, enabled: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = A.Text3)
        Spacer(Modifier.height(3.dp))
        Box {
            ValueBox(items[index] + "  ▾", Modifier.fillMaxWidth(), enabled = enabled) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEachIndexed { i, s ->
                    DropdownMenuItem(
                        text = { Text(s, color = if (i == index) A.Brand else A.Text1, fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal) },
                        onClick = { onSelect(i); open = false },
                    )
                }
            }
        }
    }
}

// ———————————————————————————————— 导出结果、日志

@Composable
private fun ResultsCard(ctx: Context) {
    if (Studio.results.isEmpty()) return
    Column(Modifier.card().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("已导出", Modifier.weight(1f))
            TextButton(onClick = { Studio.results.clear() }) { Text("清空记录", color = A.Text3, fontSize = 13.sp) }
        }
        Studio.results.take(30).forEach { res ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(res.name, fontSize = 13.sp, color = A.Text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(res.where, fontSize = 11.sp, color = A.Text3)
                }
                Spacer(Modifier.width(6.dp))
                Pill("播放", height = 32.dp) { openResult(ctx, res) }
                Spacer(Modifier.width(6.dp))
                Pill("分享", height = 32.dp) { shareResult(ctx, res) }
            }
        }
    }
}

private fun openResult(ctx: Context, r: ExportResult) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { Studio.toast("没有能播放这个文件的 App") }
}

private fun shareResult(ctx: Context, r: ExportResult) {
    val i = Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, "分享 ${r.name}")) } catch (_: ActivityNotFoundException) { }
}

@Composable
private fun LogCard() {
    if (Studio.logs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.card()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日志", fontSize = 13.sp, color = A.Text2, modifier = Modifier.weight(1f))
            Text(if (open) "收起 ▴" else "展开 ▾", fontSize = 12.sp, color = A.Text3)
        }
        if (open) {
            Text(
                Studio.logs.takeLast(80).joinToString("\n"),
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontSize = 11.sp, color = A.Text2, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            )
        }
    }
}

// ———————————————————————————————— 底部导出栏

@Composable
private fun ExportBar(onExport: (all: Boolean, merge: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().background(A.Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (Studio.exporting) {
            LinearProgressIndicator(
                progress = { Studio.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = A.Brand, trackColor = A.Field,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在导出  " + Studio.progressText, Modifier.weight(1f), fontSize = 13.sp, color = A.Text1, style = A.Num)
                Pill("停止", danger = true) { Studio.stopExport() }
            }
        } else {
            if (Studio.progressText.isNotEmpty()) {
                Text(Studio.progressText, fontSize = 12.sp, color = A.Text2, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pad = PaddingValues(horizontal = 4.dp)
                Pill("导出当前", Modifier.weight(1f), enabled = Studio.current != null, height = 46.dp, padding = pad) { onExport(false, false) }
                // 合并：只对音频格式有用，选了别的格式点了会提示
                Pill("合并成一个", Modifier.weight(1f), enabled = Studio.files.size >= 2, height = 46.dp, padding = pad) { onExport(true, true) }
                Pill("全部导出 (${Studio.files.size})", Modifier.weight(1f), primary = true, enabled = Studio.files.isNotEmpty(), height = 46.dp, padding = pad) { onExport(true, false) }
            }
        }
    }
}
