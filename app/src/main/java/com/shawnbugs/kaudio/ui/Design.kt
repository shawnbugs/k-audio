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
 * K剪音自己的风格（0.2.0 起不再照 K功课）：深色「录音棚」——墨黑底 + 深灰卡片 + 薄荷青主色，紫色做第二色（淡入淡出）。
 * 卡片小圆角加细边，按钮是方圆角而不是胶囊，偏专业工具的感觉。
 * 数字一律等宽（tnum），播放时时间不跳。
 */
object A {
    val Bg = Color(0xFF0F1115)
    val Card = Color(0xFF171A20)
    val Field = Color(0xFF22262E)
    val Line = Color(0xFF2B3039)
    val Text1 = Color(0xFFECEFF4)
    val Text2 = Color(0xFFA3AAB7)
    val Text3 = Color(0xFF6B7380)
    val Brand = Color(0xFF36D6B0)
    /** 薄荷青底上的字（深色才看得清） */
    val OnBrand = Color(0xFF06231C)
    val BrandSoft = Color(0xFF14332D)
    val Violet = Color(0xFF9B8CFF)
    val Red = Color(0xFFFF6B6B)
    val Green = Color(0xFF4ADE80)

    val Num = TextStyle(fontFeatureSettings = "tnum")
}

/** 波形区配色 */
object W {
    val Bg = Color(0xFF0A0C10)
    val Ruler = Color(0xFF07080B)
    val Wave = Color(0xFF36D6B0)
    val WaveDim = Color(0xFF2A5048)
    val Clip = Color(0xFFFF6B6B)
    val Sel = Color(0xFF36D6B0)
    val Playhead = Color(0xFFFF5C8A)
    val Fade = Color(0xFF9B8CFF)
    val Tick = Color(0xFF6B7380)
    val Grid = Color(0x14FFFFFF)
    val Cut = Color(0xAAFFFFFF)
    val Msg = Color(0xFFA3AAB7)
    /** 选区时长标签上的字 */
    val OnSel = Color(0xFF06231C)
    /** 淡入淡出控制块（没按住时） */
    val Handle = Color(0xFFD5DAE3)
    /** 浮动标签的底 */
    val Tip = Color(0xE6050608)
}

fun Modifier.card(): Modifier = this
    .padding(horizontal = 12.dp, vertical = 5.dp)
    .fillMaxWidth()
    .clip(RoundedCornerShape(12.dp))
    .background(A.Card)
    .border(1.dp, A.Line, RoundedCornerShape(12.dp))

/** 按钮（方圆角）：primary = 薄荷青实心深字，否则深灰底；禁用时半透明 */
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
        primary -> A.OnBrand
        danger -> A.Red
        else -> A.Text1
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(10.dp))
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
