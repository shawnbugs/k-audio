package com.shawnbugs.kaudio.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * 非破坏性编辑模型（照搬 PC 版 Edit.cs）：
 * 时间线 = 源文件的若干片段首尾相接；音量、淡入淡出作用于整条时间线。
 */
class Seg(var a: Double, var b: Double) {   // 源文件中的起止时间（秒）
    val len: Double get() = b - a
}

class EditState() {
    var segs: MutableList<Seg> = mutableListOf()
    // 试听线程会实时读这三个值（拖淡入淡出时能立刻听到）
    @Volatile var fadeIn = 0.0
    @Volatile var fadeOut = 0.0
    @Volatile var gainDb = 0.0

    constructor(srcDur: Double) : this() { segs.add(Seg(0.0, srcDur)) }

    fun clone(): EditState {
        val e = EditState()
        e.fadeIn = fadeIn; e.fadeOut = fadeOut; e.gainDb = gainDb
        segs.forEach { e.segs.add(Seg(it.a, it.b)) }
        return e
    }

    val length: Double get() = segs.sumOf { it.len }

    val hasFx: Boolean get() = fadeIn > 0 || fadeOut > 0 || abs(gainDb) > 0.001

    fun isOriginal(srcDur: Double): Boolean =
        segs.size == 1 && segs[0].a < EPS && segs[0].b > srcDur - EPS && !hasFx

    /** 只是截了一段、没有其它编辑：可以用 -ss/-to，配合「保持原格式」无损复制 */
    val isPureTrim: Boolean get() = segs.size == 1 && !hasFx

    /** 剪辑点（片段之间的边界）在时间线上的位置 */
    fun cuts(): List<Double> {
        val r = ArrayList<Double>()
        var t = 0.0
        for (i in 0 until segs.size - 1) { t += segs[i].len; r.add(t) }
        return r
    }

    /** 时间线 t 所在片段的起止位置 */
    fun segmentAt(t: Double): Pair<Double, Double>? {
        var a = 0.0
        for (i in segs.indices) {
            val e = a + segs[i].len
            if (t < e || i == segs.size - 1) return a to e
            a = e
        }
        return null
    }

    /** 时间线位置 t 对应源文件里的时间（用于取视频画面）；到结尾时退回一点，保证还能取到帧（同 PC 版 SourceAt） */
    fun sourceAt(t: Double): Double {
        var start = 0.0
        for (i in segs.indices) {
            val s = segs[i]
            if (t < start + s.len || i == segs.size - 1) {
                val src = s.a + max(0.0, t - start)
                return max(s.a, min(src, s.b - 0.05))
            }
            start += s.len
        }
        return 0.0
    }

    fun splitAt(t: Double): Boolean {
        var start = 0.0
        for (i in segs.indices) {
            val s = segs[i]
            val end = start + s.len
            if (t > start + EPS && t < end - EPS) {
                val src = s.a + (t - start)
                segs.add(i + 1, Seg(src, s.b))
                s.b = src
                return true
            }
            start = end
        }
        return false
    }

    /** 删除时间线 [a,b]，后面的内容前移接上 */
    fun delete(a: Double, b: Double) { splitAt(a); splitAt(b); filter(a, b, false); clampFades() }

    /** 只保留时间线 [a,b] */
    fun crop(a: Double, b: Double) { splitAt(a); splitAt(b); filter(a, b, true); clampFades() }

    private fun filter(a: Double, b: Double, keepInside: Boolean) {
        val keep = ArrayList<Seg>()
        var start = 0.0
        for (s in segs) {
            val mid = start + s.len / 2
            if ((mid > a && mid < b) == keepInside) keep.add(s)
            start += s.len
        }
        segs = keep
    }

    fun clampFades() {
        val l = length
        fadeIn = fadeIn.coerceIn(0.0, max(0.0, l))
        fadeOut = fadeOut.coerceIn(0.0, max(0.0, l - fadeIn))
    }

    /** 淡入淡出包络（0..1，线性，与 ffmpeg afade 默认曲线一致） */
    fun fade(t: Double, length: Double): Double {
        var g = 1.0
        val fi = fadeIn; val fo = fadeOut
        if (fi > 0 && t < fi) g *= max(0.0, t / fi)
        if (fo > 0 && t > length - fo) g *= max(0.0, (length - t) / fo)
        return min(g, 1.0)
    }

    val baseGain: Double get() = 10.0.pow(gainDb / 20)

    companion object { const val EPS = 0.001 }
}
