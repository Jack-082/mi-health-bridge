package io.github.mihealthbridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 期刊插图风格（Nature / Cell / Science）：白底、细线、克制配色，
 * 主色取自 Nature Publishing Group 常用调色板。
 */
object Palette {
    val Navy = Color(0xFF3C5488)
    val Red = Color(0xFFE64B35)
    val Teal = Color(0xFF00A087)
    val Salmon = Color(0xFFF39B7F)
    val Ink = Color(0xFF1A1A1A)
    val Muted = Color(0xFF6B6B6B)
    val Faint = Color(0xFFB4B4B4)
    val Rule = Color(0xFFDADADA)
    val Tint = Color(0xFFF6F6F4)
    val Paper = Color.White
}

enum class Status(val color: Color) {
    Ok(Palette.Teal), Warn(Palette.Salmon), Error(Palette.Red), None(Palette.Faint)
}

private val SmallShape = RoundedCornerShape(4.dp)
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val Scheme = lightColorScheme(
    primary = Palette.Navy, onPrimary = Color.White,
    secondary = Palette.Teal, onSecondary = Color.White,
    error = Palette.Red, onError = Color.White,
    background = Palette.Paper, onBackground = Palette.Ink,
    surface = Palette.Paper, onSurface = Palette.Ink,
    surfaceVariant = Palette.Tint, onSurfaceVariant = Palette.Muted,
    outline = Palette.Rule, outlineVariant = Palette.Rule,
)

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

/** 分图：粗体小写字母编号 + 标题，与论文 Figure 的 a / b / c 一致。 */
@Composable
fun Panel(label: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            Text(label, Modifier.alignByBaseline(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.alignByBaseline(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        }
        content()
    }
}

/** 三线表：上下粗线，中间不画竖线和行线。 */
@Composable
fun StatTable(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = Palette.Ink)
        Column(Modifier.padding(vertical = 4.dp)) { content() }
        HorizontalDivider(thickness = 1.dp, color = Palette.Ink)
    }
}

@Composable
fun StatRow(label: String, value: String, status: Status? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.38f), fontSize = 13.sp, color = Palette.Muted)
        Row(Modifier.weight(0.62f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (status != null) {
                Dot(status.color)
                Spacer(Modifier.width(6.dp))
            }
            Text(value, fontSize = 13.sp, color = Palette.Ink, textAlign = TextAlign.End, style = Tabular)
        }
    }
}

@Composable
fun Dot(color: Color, diameter: Int = 7) {
    Box(Modifier.size(diameter.dp).background(color, CircleShape))
}

/** 图例式状态摘要：● 账号  ● 权限  ● 同步 */
@Composable
fun LegendItem(status: Status, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(status.color, 8)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Palette.Muted)
    }
}

/** 图注风格的说明文字。 */
@Composable
fun Caption(text: String, color: Color = Palette.Muted) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = color)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = SmallShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = SmallShape,
        border = BorderStroke(1.dp, Palette.Navy),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Navy),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, fontSize = 14.sp) }
}

/** 分段选择器，替代圆角 Chip，更接近图表里的刻度/分组。 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).clip(SmallShape).border(1.dp, Palette.Navy, SmallShape)) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.Navy))
            val on = value == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (on) Palette.Navy else Color.Transparent)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp, style = Tabular,
                    color = if (on) Color.White else Palette.Navy,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.Muted, letterSpacing = 0.2.sp)
}
