package com.murtdah.fit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Good = Color(0xFF4FD1A5)
val Warn = Color(0xFFFFC155)
val Bad = Color(0xFFFF7A6B)

private val dark: ColorScheme = darkColorScheme(
    primary = Good,
    onPrimary = Color(0xFF00382A),
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF0F1114),
    surface = Color(0xFF15181C),
    surfaceVariant = Color(0xFF1E2227),
    onSurface = Color(0xFFECEFF3),
    onSurfaceVariant = Color(0xFF9AA4AE),
    error = Bad,
)

private val light: ColorScheme = lightColorScheme(primary = Color(0xFF00897B))

@Composable
fun FitTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = if (color != null) androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = color) else androidx.compose.material3.ButtonDefaults.buttonColors(),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun Metric(label: String, value: Int, target: Int, unit: String, lowerIsBetter: Boolean = false) {
    val ratio = if (target > 0) value.toFloat() / target else 0f
    val color = when {
        lowerIsBetter && value > target -> Bad
        lowerIsBetter -> Good
        ratio >= 1f -> Good
        ratio >= 0.6f -> Warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            val remain = if (lowerIsBetter) "${(target - value)} $unit left" else if (value >= target) "done" else "${target - value} $unit to go"
            Text("$value / $target $unit  ($remain)", color = color, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(progress = { ratio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), color = color)
    }
}

@Composable
fun Ring(progress: Float, size: Dp, stroke: Dp = 12.dp, color: Color = Good, secondary: Float? = null, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.surface
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val st = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = st)
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, style = st)
            if (secondary != null) {
                val angle = Math.toRadians((-90 + 360 * secondary.coerceIn(0f, 1f)).toDouble())
                val r = this.size.minDimension / 2
                drawCircle(Warn, stroke.toPx() / 3, Offset(center.x + (r * kotlin.math.cos(angle)).toFloat(), center.y + (r * kotlin.math.sin(angle)).toFloat()))
            }
        }
        content()
    }
}

@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = Good) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    if (values.size < 2) {
        Text("Log at least 2 entries to see a chart.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val min = values.min()
    val max = values.max()
    val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(String.format(java.util.Locale.US, "%.1f", max), style = MaterialTheme.typography.labelSmall)
            Text(String.format(java.util.Locale.US, "latest %.1f", values.last()), style = MaterialTheme.typography.labelSmall, color = color)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            for (i in 0..3) {
                val y = size.height * i / 3
                drawLine(grid, Offset(0f, y), Offset(size.width, y))
            }
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val y = size.height - ((v - min) / span * size.height).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(String.format(java.util.Locale.US, "%.1f", min), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.all { it.isDigit() || (decimal && (it == '.' || it == ',')) } && v.length <= 7) onChange(v.replace(',', '.')) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

fun hm(totalSeconds: Int): String {
    val m = totalSeconds / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
