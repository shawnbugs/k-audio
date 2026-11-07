package com.shawnbugs.kaudio.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shawnbugs.kaudio.Studio
import com.shawnbugs.kaudio.WaveModel
import com.shawnbugs.kaudio.core.fmtTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * 波形视图（对应 PC 版 WaveView.cs，鼠标操作改成触摸）：
 *   单指拖动 = 框选；拖选区两边（底部圆柄）= 调整选区；点一下 = 定位播放头；双击 = 选中两个剪辑点之间的整段；
 *   拖顶部两角的小方块 = 淡入 / 淡出；双指 = 缩放和平移；拖底部概览条 = 平移。
 */

private enum class Mode { None, New, Left, Right, Pan, FadeIn, FadeOut }

private class GestureUi {
    var mode by mutableStateOf(Mode.None)
    var lastTap = 0L
    var lastTapX = 0f
}

/** 尺寸（dp）统一放这里，画和点击判断用同一套 */
private object Dim {
    val ruler = 20.dp
    val over = 22.dp
    val handleOff = 14.dp      // 淡入淡出方块中心离刻度尺下沿
    val handle = 13.dp
    val handleHit = 26.dp
    val edgeHit = 22.dp
    val grip = 7.dp
    val tickMin = 64.dp        // 两个刻度标签之间至少这么宽
}

private val STEPS = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

private fun tickLabel(t: Double, step: Double): String {
    val ms = Math.round(t * 1000)
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms % 60_000 / 1000.0
    val sec = when {
        step >= 1 -> String.format(Locale.US, "%02d", s.toInt())
        step >= 0.1 -> String.format(Locale.US, "%04.1f", s)
        else -> String.format(Locale.US, "%05.2f", s)
    }
    return if (h > 0) String.format(Locale.US, "%d:%02d:%s", h, m, sec) else "$m:$sec"
}

private fun xToT(w: WaveModel, x: Float, width: Float) = w.viewStart + x * w.viewSpan / max(1f, width)
private fun tToX(w: WaveModel, t: Double, width: Float) = ((t - w.viewStart) / w.viewSpan * width).toFloat()

/** 淡入淡出控制块的横坐标（为 0 时贴在两端） */
private fun fadeInX(w: WaveModel, width: Float, d: Density): Float = with(d) {
    val e = w.edit!!
    if (e.fadeIn <= 0 && w.viewStart <= 0) Dim.handleHit.toPx() / 2 else tToX(w, e.fadeIn, width)
}

private fun fadeOutX(w: WaveModel, width: Float, d: Density): Float = with(d) {
    val e = w.edit!!
    if (e.fadeOut <= 0 && w.viewStart + w.viewSpan >= w.duration - 1e-6) width - Dim.handleHit.toPx() / 2
    else tToX(w, w.duration - e.fadeOut, width)
}

@Composable
fun WaveCanvas(w: WaveModel, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val gs = remember { GestureUi() }
    val lines = remember { LineBuf() }
    Canvas(
        modifier.pointerInput(Unit) { gestures(w, gs) }
    ) {
        @Suppress("UNUSED_VARIABLE") val r = w.rev   // 读一下：rev 变了就重画
        w.widthPx = size.width
        drawWaveView(w, gs, tm, lines)
    }
}

// ———————————————————————————————— 手势

private suspend fun PointerInputScope.gestures(w: WaveModel, gs: GestureUi) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!w.loaded || w.viewSpan <= 0) return@awaitEachGesture
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val d = w.duration
        val e = w.edit!!
        val p0 = down.position
        val handleY = (Dim.ruler + Dim.handleOff).toPx()
        val hh = Dim.handleHit.toPx() / 2
        fun hit(hx: Float) = abs(p0.x - hx) <= hh && abs(p0.y - handleY) <= hh
        val edgeHit = Dim.edgeHit.toPx()
        val dl = if (w.hasSel) abs(p0.x - tToX(w, w.selStart, width)) else Float.MAX_VALUE
        val dr = if (w.hasSel) abs(p0.x - tToX(w, w.selEnd, width)) else Float.MAX_VALUE

        val mode = when {
            p0.y >= height - Dim.over.toPx() -> Mode.Pan
            hit(fadeInX(w, width, this)) -> Mode.FadeIn
            hit(fadeOutX(w, width, this)) -> Mode.FadeOut
            dl <= edgeHit && dl <= dr -> Mode.Left
            dr <= edgeHit -> Mode.Right
            else -> Mode.New
        }
        if (mode == Mode.FadeIn || mode == Mode.FadeOut) Studio.pushUndo()
        val anchor = xToT(w, p0.x, width).coerceIn(0.0, d)
        val sel0 = w.selStart to w.selEnd
        var moved = false
        var pinch = false
        fun panTo(x: Float) {
            w.viewStart = x / width * d - w.viewSpan / 2
            w.clampView()
            w.invalidate()
        }
        if (mode == Mode.Pan) panTo(p0.x)
        gs.mode = mode
        w.invalidate()
        down.consume()

        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2 || pinch) {
                if (!pinch) {
                    // 第二根手指放下：改成缩放，刚才那一下框选作废
                    pinch = true
                    if (mode == Mode.New || mode == Mode.Left || mode == Mode.Right) { w.selStart = sel0.first; w.selEnd = sel0.second }
                    gs.mode = Mode.None
                }
                if (pressed >= 2) {
                    val prev = ev.calculateCentroid(useCurrent = false)
                    val cur = ev.calculateCentroid(useCurrent = true)
                    val zoom = ev.calculateZoom()
                    if (prev != Offset.Unspecified && cur != Offset.Unspecified && zoom > 0f) {
                        // 手指下面的那个时间点跟着手指走
                        val tPrev = xToT(w, prev.x, width)
                        w.viewSpan = w.viewSpan / zoom
                        w.clampView()
                        w.viewStart = tPrev - cur.x / width * w.viewSpan
                        w.clampView()
                        w.invalidate()
                    }
                }
                ev.changes.forEach { it.consume() }
                continue
            }
            val ch = ev.changes.first { it.pressed }
            val x = ch.position.x
            val t = xToT(w, x, width).coerceIn(0.0, d)
            when (mode) {
                Mode.Pan -> panTo(x)
                Mode.FadeIn -> { e.fadeIn = t.coerceIn(0.0, max(0.0, d - e.fadeOut)); Studio.fadeDragged() }
                Mode.FadeOut -> { e.fadeOut = (d - t).coerceIn(0.0, max(0.0, d - e.fadeIn)); Studio.fadeDragged() }
                Mode.New -> if (moved || abs(x - p0.x) >= viewConfiguration.touchSlop) {
                    moved = true
                    w.selStart = min(anchor, t); w.selEnd = max(anchor, t)
                    w.invalidate()
                    Studio.selChanged()
                }
                Mode.Left -> { moved = true; w.selStart = min(t, w.selEnd - 0.01); w.invalidate(); Studio.selChanged() }
                Mode.Right -> { moved = true; w.selEnd = max(t, w.selStart + 0.01); w.invalidate(); Studio.selChanged() }
                Mode.None -> {}
            }
            ch.consume()
        }
        gs.mode = Mode.None

        if (!pinch && mode == Mode.New) {
            if (!moved) {
                val now = down.uptimeMillis
                if (now - gs.lastTap < 320 && abs(p0.x - gs.lastTapX) < 40.dp.toPx()) {
                    // 双击：选中两个剪辑点之间的整段
                    e.segmentAt(xToT(w, p0.x, width))?.let { (a, b) -> w.setSelection(a, b) }
                    gs.lastTap = 0
                } else {
                    // 单击：选区外点击会取消选区；都会把播放头移到这里
                    val tt = xToT(w, p0.x, width).coerceIn(0.0, d)
                    if (w.hasSel && (tt < w.selStart || tt > w.selEnd)) w.clearSelection()
                    w.playhead = tt
                    Studio.onSeek(tt)
                    gs.lastTap = now
                    gs.lastTapX = p0.x
                }
            } else if (w.selEnd - w.selStart < 0.001) {
                w.clearSelection()
            }
        }
        w.invalidate()
    }
}

// ———————————————————————————————— 绘制

/** 波形竖线用原生 Canvas.drawLines 一次画完，比一根根画快得多 */
private class LineBuf {
    var on = FloatArray(0)
    var dim = FloatArray(0)
    var clip = FloatArray(0)
    val paint = Paint().apply { strokeWidth = 1f; isAntiAlias = false }
    fun ensure(n: Int) {
        if (on.size < n) { on = FloatArray(n); dim = FloatArray(n); clip = FloatArray(n) }
    }
}

private fun DrawScope.drawWaveView(w: WaveModel, gs: GestureUi, tm: TextMeasurer, lines: LineBuf) {
    val width = size.width
    val height = size.height
    val ruler = Dim.ruler.toPx()
    val over = Dim.over.toPx()
    drawRect(W.Bg)
    drawRect(W.Ruler, size = Size(width, ruler))
    if (!w.loaded || w.duration <= 0 || w.viewSpan <= 0) {
        val layout = tm.measure(
            w.message,
            TextStyle(color = W.Msg, fontSize = 14.sp, textAlign = TextAlign.Center),
            constraints = Constraints(maxWidth = max(1, (width - 32.dp.toPx()).toInt())),
        )
        drawText(layout, topLeft = Offset((width - layout.size.width) / 2, (height - layout.size.height) / 2))
        return
    }
    val e = w.edit!!
    val len = w.duration
    val waveTop = ruler
    val waveBottom = height - over - 2.dp.toPx()

    // 选区底色
    var x1 = 0f
    var x2 = 0f
    if (w.hasSel) {
        x1 = tToX(w, w.selStart, width); x2 = tToX(w, w.selEnd, width)
        drawRect(W.Sel.copy(alpha = 0.16f), topLeft = Offset(x1, waveTop), size = Size(x2 - x1, waveBottom - waveTop))
    }

    // 刻度间隔：保证两个标签之间至少约 64dp
    val pxPerSec = width / w.viewSpan
    var step = STEPS.last()
    for (s in STEPS) if (s * pxPerSec >= Dim.tickMin.toPx()) { step = s; break }
    val k0 = ceil(w.viewStart / step).toLong()
    val k1 = floor((w.viewStart + w.viewSpan) / step).toLong()
    for (k in k0..k1) {
        val x = tToX(w, k * step, width)
        drawLine(W.Grid, Offset(x, waveTop), Offset(x, waveBottom))
    }

    drawWave(w, lines, waveTop, waveBottom, len)

    val tickStyle = TextStyle(color = W.Tick, fontSize = 10.sp, fontFeatureSettings = "tnum")
    for (k in k0..k1) {
        val t = k * step
        val x = tToX(w, t, width)
        drawLine(W.Tick, Offset(x, ruler - 5.dp.toPx()), Offset(x, ruler))
        drawText(tm, tickLabel(t, step), topLeft = Offset(x + 3.dp.toPx(), 2.dp.toPx()), style = tickStyle)
    }

    // 剪辑点：虚线 + 刻度尺上的小三角
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    val tri = 5.dp.toPx()
    for (c in e.cuts()) {
        val x = tToX(w, c, width)
        if (x < -2 || x > width + 2) continue
        drawLine(W.Cut, Offset(x, waveTop), Offset(x, waveBottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        drawPath(Path().apply { moveTo(x - tri, ruler - tri * 1.3f); lineTo(x + tri, ruler - tri * 1.3f); lineTo(x, ruler); close() }, Color.White)
    }

    if (w.hasSel) drawSelection(w, tm, x1, x2, waveTop, waveBottom)
    drawFades(w, gs, tm, waveBottom, len)

    // 播放头
    val px = tToX(w, w.playhead, width)
    if (px >= -2 && px <= width + 2) {
        drawLine(W.Playhead, Offset(px, 0f), Offset(px, waveBottom), strokeWidth = 1.5.dp.toPx())
        val a = 5.dp.toPx()
        drawPath(Path().apply { moveTo(px - a, 0f); lineTo(px + a, 0f); lineTo(px, a * 1.4f); close() }, W.Playhead)
    }

    // 底部概览条：整段时长，亮块 = 当前可视范围，可拖动
    val oTop = height - over
    drawRect(W.Ruler, topLeft = Offset(0f, oTop), size = Size(width, over))
    if (w.hasSel) {
        drawRect(
            W.Sel.copy(alpha = 0.45f),
            topLeft = Offset((w.selStart / len * width).toFloat(), oTop),
            size = Size(((w.selEnd - w.selStart) / len * width).toFloat(), over),
        )
    }
    val thumbX = (w.viewStart / len * width).toFloat()
    val thumbW = max(12.dp.toPx(), (w.viewSpan / len * width).toFloat())
    drawRoundRect(
        Color.White.copy(alpha = 0.28f),
        topLeft = Offset(thumbX, oTop + 4.dp.toPx()),
        size = Size(thumbW, over - 8.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    val op = (w.playhead / len * width).toFloat()
    drawLine(W.Playhead, Offset(op, oTop), Offset(op, height), strokeWidth = 1.dp.toPx())
}

/** 每个像素列：找出覆盖的片段 → 源文件桶的最小/最大值 → 乘上音量和淡入淡出（同 PC 版） */
private fun DrawScope.drawWave(w: WaveModel, lines: LineBuf, top: Float, bottom: Float, len: Double) {
    val p = w.peaks ?: return
    val e = w.edit ?: return
    val pmin = p.min
    val pmax = p.max
    val bucket = p.bucketSec
    val cols = size.width.toInt()
    val mid = (top + bottom) / 2
    val half = (bottom - top) / 2 - 3.dp.toPx()
    drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid))
    val segs = e.segs
    val baseGain = e.baseGain
    val per = w.viewSpan / max(1, cols)
    var si = 0
    var segT0 = 0.0
    lines.ensure(cols * 4)
    var nOn = 0
    var nDim = 0
    var nClip = 0
    for (x in 0 until cols) {
        val t0 = w.viewStart + x * per
        val t1 = min(t0 + per, len)
        if (t0 >= len) break
        while (si < segs.size - 1 && segT0 + segs[si].len <= t0) { segT0 += segs[si].len; si++ }
        var lo = 0f
        var hi = 0f
        var any = false
        var sT = segT0
        var sj = si
        while (sj < segs.size && sT < t1) {
            val s = segs[sj]
            val a = max(t0, sT)
            val b = min(t1, sT + s.len)
            val segStart = sT
            sT += s.len
            sj++
            if (b <= a) continue
            var i0 = ((s.a + a - segStart) / bucket).toInt()
            var i1 = ceil((s.a + b - segStart) / bucket).toInt()
            if (i0 >= pmin.size) continue
            if (i0 < 0) i0 = 0
            if (i1 <= i0) i1 = i0 + 1
            if (i1 > pmin.size) i1 = pmin.size
            for (i in i0 until i1) {
                if (!any) { lo = pmin[i]; hi = pmax[i]; any = true; continue }
                if (pmin[i] < lo) lo = pmin[i]
                if (pmax[i] > hi) hi = pmax[i]
            }
        }
        if (!any) continue
        val gain = (baseGain * e.fade((t0 + t1) / 2, len)).toFloat()
        lo *= gain; hi *= gain
        val clip = hi > 1 || lo < -1
        hi = min(hi, 1f); lo = max(lo, -1f)
        val y1 = mid - hi * half
        var y2 = mid - lo * half
        if (y2 - y1 < 1) y2 = y1 + 1
        val dim = w.hasSel && (t1 < w.selStart || t0 > w.selEnd)
        val fx = x + 0.5f
        when {
            clip -> { lines.clip[nClip++] = fx; lines.clip[nClip++] = y1; lines.clip[nClip++] = fx; lines.clip[nClip++] = y2 }
            dim -> { lines.dim[nDim++] = fx; lines.dim[nDim++] = y1; lines.dim[nDim++] = fx; lines.dim[nDim++] = y2 }
            else -> { lines.on[nOn++] = fx; lines.on[nOn++] = y1; lines.on[nOn++] = fx; lines.on[nOn++] = y2 }
        }
    }
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        val paint = lines.paint
        if (nOn > 0) { paint.color = W.Wave.toArgb(); nc.drawLines(lines.on, 0, nOn, paint) }
        if (nDim > 0) { paint.color = W.WaveDim.toArgb(); nc.drawLines(lines.dim, 0, nDim, paint) }
        if (nClip > 0) { paint.color = W.Clip.toArgb(); nc.drawLines(lines.clip, 0, nClip, paint) }
    }
}

private fun DrawScope.drawSelection(w: WaveModel, tm: TextMeasurer, x1: Float, x2: Float, top: Float, bottom: Float) {
    val sw = 2.dp.toPx()
    drawLine(W.Sel, Offset(x1, top), Offset(x1, bottom), strokeWidth = sw)
    drawLine(W.Sel, Offset(x2, top), Offset(x2, bottom), strokeWidth = sw)
    // 底部圆柄：提示两边可以拖
    val gy = bottom - Dim.grip.toPx() - 4.dp.toPx()
    drawCircle(W.Sel, Dim.grip.toPx(), Offset(x1, gy))
    drawCircle(W.Sel, Dim.grip.toPx(), Offset(x2, gy))
    val tag = fmtTime(w.selEnd - w.selStart)
    val layout = tm.measure(tag, TextStyle(color = W.OnSel, fontSize = 11.sp, fontFeatureSettings = "tnum"))
    val tw = layout.size.width + 10.dp.toPx()
    val th = layout.size.height + 2.dp.toPx()
    if (x2 - x1 > tw + 2 * Dim.grip.toPx() + 12.dp.toPx()) {
        val tx = (x1 + x2 - tw) / 2
        val ty = gy - th / 2
        drawRoundRect(W.Sel, topLeft = Offset(tx, ty), size = Size(tw, th), cornerRadius = CornerRadius(4.dp.toPx()))
        drawText(layout, topLeft = Offset(tx + 5.dp.toPx(), ty + 1.dp.toPx()))
    }
}

/** 淡入淡出斜线和两角的控制块（同 Audition） */
private fun DrawScope.drawFades(w: WaveModel, gs: GestureUi, tm: TextMeasurer, bottom: Float, len: Double) {
    val e = w.edit!!
    val width = size.width
    val top = (Dim.ruler + Dim.handleOff).toPx()
    val sw = 1.5.dp.toPx()
    if (e.fadeIn > 0) drawLine(W.Fade, Offset(tToX(w, 0.0, width), bottom), Offset(tToX(w, e.fadeIn, width), top), strokeWidth = sw)
    if (e.fadeOut > 0) drawLine(W.Fade, Offset(tToX(w, len - e.fadeOut, width), top), Offset(tToX(w, len, width), bottom), strokeWidth = sw)
    drawHandle(tm, fadeInX(w, width, this), top, gs.mode == Mode.FadeIn, String.format(Locale.US, "淡入 %.2f 秒", e.fadeIn), true)
    drawHandle(tm, fadeOutX(w, width, this), top, gs.mode == Mode.FadeOut, String.format(Locale.US, "淡出 %.2f 秒", e.fadeOut), false)
}

private fun DrawScope.drawHandle(tm: TextMeasurer, x: Float, y: Float, active: Boolean, label: String, labelRight: Boolean) {
    val s = Dim.handle.toPx()
    if (x < -s || x > size.width + s) return
    drawRoundRect(
        if (active) W.Fade else W.Handle,
        topLeft = Offset(x - s / 2, y - s / 2),
        size = Size(s, s),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    if (!active) return
    val layout = tm.measure(label, TextStyle(color = W.Fade, fontSize = 12.sp, fontFeatureSettings = "tnum"))
    val pad = 6.dp.toPx()
    val bw = layout.size.width + pad * 2
    val bh = layout.size.height + 4.dp.toPx()
    val bx = (if (labelRight) x + s else x - s - bw).coerceIn(0f, max(0f, size.width - bw))
    val by = y + s
    drawRoundRect(W.Tip, topLeft = Offset(bx, by), size = Size(bw, bh), cornerRadius = CornerRadius(5.dp.toPx()))
    drawText(layout, topLeft = Offset(bx + pad, by + 2.dp.toPx()))
}
