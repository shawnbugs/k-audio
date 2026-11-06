package com.shawnbugs.kaudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * K剪音的配色照 K功课：米色底 + 白卡片 + 赭金色；波形区用暖深色底（同 PC 版暗色波形，看得清）。
 * 数字一律等宽（tnum），播放时时间不跳。
 */
object A {
    val Bg = Color(0xFFF7F3EC)
    val Card = Color(0xFFFFFFFF)
    val Field = Color(0xFFF2EDE4)
    val Line = Color(0xFFECE5DA)
    val Text1 = Color(0xFF2A2118)
    val Text2 = Color(0xFF6B5E4E)
    val Text3 = Color(0xFFA59886)
    val Brand = Color(0xFFC2782A)
    val BrandSoft = Color(0xFFF8EAD8)
    val Red = Color(0xFFD9483B)
    val Green = Color(0xFF3E9A5B)

    val Num = TextStyle(fontFeatureSettings = "tnum")
}

/** 波形区配色 */
object W {
    val Bg = Color(0xFF26201A)
    val Ruler = Color(0xFF1D1813)
    val Wave = Color(0xFFF2B544)
    val WaveDim = Color(0xFF7D6A4F)
    val Clip = Color(0xFFFF5A4E)
    val Sel = Color(0xFFF2B544)
    val Playhead = Color(0xFFFF6B5E)
    val Fade = Color(0xFFFFD678)
    val Tick = Color(0xFFA59886)
    val Grid = Color(0x16FFFFFF)
    val Cut = Color(0xAAFFFFFF)
    val Msg = Color(0xFFCDBFA9)
}

fun Modifier.card(): Modifier = this
    .padding(horizontal = 12.dp, vertical = 5.dp)
    .fillMaxWidth()
    .clip(RoundedCornerShape(16.dp))
    .background(A.Card)

/** 胶囊按钮：primary = 品牌色实心，否则浅底；禁用时半透明 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp),
    onClick: () -> Unit,
) {
    val bg = if (primary) A.Brand else A.Field
    val fg = when {
        primary -> Color.White
        danger -> A.Red
        else -> A.Text1
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(bg)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** 点开可编辑的数值框（真正的输入在弹框里，手机上比直接嵌输入框好按） */
@Composable
fun ValueBox(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, placeholder: String = "—", onClick: () -> Unit) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(A.Field)
            .border(1.dp, A.Line, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.ifEmpty { placeholder },
            color = if (text.isEmpty()) A.Text3 else A.Text1,
            fontSize = 14.sp,
            style = A.Num,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RowScope.Label(text: String) {
    Text(text, color = A.Text2, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = A.Text1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
