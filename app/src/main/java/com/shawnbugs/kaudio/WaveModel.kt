package com.shawnbugs.kaudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.shawnbugs.kaudio.core.EditState
import com.shawnbugs.kaudio.core.Peaks
import kotlin.math.max
import kotlin.math.min

/*
 * 波形视图的状态（对应 PC 版 WaveView 的字段部分）。只在主线程改。
 * 字段都是普通变量，改完调 invalidate() 让 rev 变一下，读 rev 的界面就会重画。
 */
class WaveModel {
    var peaks: Peaks? = null
        private set
    var edit: EditState? = null
        private set
    var viewStart = 0.0
    var viewSpan = 0.0
    var selStart = -1.0
    var selEnd = -1.0
    var playhead = 0.0
    var message: String = EMPTY_MSG
        private set
    /** 画布宽度（像素），由画布在布局后写入，用来算最小缩放范围 */
    var widthPx = 1f

    var rev by mutableIntStateOf(0)
        private set

    fun invalidate() { rev++ }

    val loaded: Boolean get() = peaks != null && edit != null
    val hasSel: Boolean get() = selStart >= 0 && selEnd > selStart
    val duration: Double get() = if (loaded) edit!!.length else 0.0

    fun setData(p: Peaks, e: EditState) {
        peaks = p; edit = e
        viewStart = 0.0; viewSpan = duration; selStart = -1.0; selEnd = -1.0; playhead = 0.0; message = ""
        invalidate()
    }

    /** 编辑结构变化后（删除/裁剪/撤销）调用 */
    fun setEdit(e: EditState) {
        edit = e
        val d = duration
        if (selEnd > d) { selStart = -1.0; selEnd = -1.0 }
        playhead = min(playhead, d)
        clampView()
        invalidate()
    }

    fun reset(msg: String) {
        peaks = null; edit = null; selStart = -1.0; selEnd = -1.0; playhead = 0.0; message = msg
        invalidate()
    }

    fun setSelection(a: Double, b: Double) {
        if (!loaded) return
        val d = duration
        val lo = min(a, b).coerceIn(0.0, d)
        val hi = max(a, b).coerceIn(0.0, d)
        if (hi - lo < 0.001) { selStart = -1.0; selEnd = -1.0 } else { selStart = lo; selEnd = hi }
        invalidate()
    }

    fun clearSelection() { selStart = -1.0; selEnd = -1.0; invalidate() }

    fun zoomAll() { if (!loaded) return; viewStart = 0.0; viewSpan = duration; invalidate() }

    /** 播放头跑出可视范围时自动翻页 */
    fun follow(t: Double) {
        if (!loaded) return
        if (t < viewStart || t > viewStart + viewSpan) { viewStart = t - viewSpan * 0.05; clampView() }
        invalidate()
    }

    /** 最多放大到一屏约 0.2 秒（或每 4 个像素一个波形桶） */
    val minSpan: Double
        get() = min(duration, max(0.2, (peaks?.bucketSec ?: 0.01) * widthPx / 4))

    fun clampView() {
        if (!loaded) return
        val d = duration
        viewSpan = viewSpan.coerceIn(minSpan, max(minSpan, d))
        viewStart = viewStart.coerceIn(0.0, max(0.0, d - viewSpan))
    }

    companion object {
        const val EMPTY_MSG = "点上方「添加」选一个音频或视频文件"
    }
}
