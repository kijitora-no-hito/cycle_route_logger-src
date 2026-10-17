package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.data.PlanSummary
import kotlin.math.roundToInt

/** 標高グラフ（距離 m, 標高 m の折れ線）。 */
@Composable
fun ElevationChart(profile: List<Pair<Double, Double>>, color: Color, modifier: Modifier = Modifier) {
    if (profile.size < 2) {
        Text(stringResource(R.string.no_elevation), style = MaterialTheme.typography.bodySmall, modifier = modifier)
        return
    }
    val minE = profile.minOf { it.second }
    val maxE = profile.maxOf { it.second }
    val maxD = profile.last().first.coerceAtLeast(1.0)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.elev_max, maxE.roundToInt()), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.elev_min, minE.roundToInt()), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val range = (maxE - minE).coerceAtLeast(10.0)
            val base = minE - (range - (maxE - minE)) / 2
            fun x(d: Double) = (d / maxD * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - base) / range * size.height).toFloat()
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height))
            val line = Path()
            val fill = Path()
            profile.forEachIndexed { i, (d, e) ->
                if (i == 0) {
                    line.moveTo(x(d), y(e)); fill.moveTo(x(d), size.height); fill.lineTo(x(d), y(e))
                } else {
                    line.lineTo(x(d), y(e)); fill.lineTo(x(d), y(e))
                }
            }
            fill.lineTo(x(profile.last().first), size.height)
            fill.close()
            drawPath(fill, color.copy(alpha = 0.18f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text(Format.km(maxD), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 数値 1 つ（ラベル + 値）。 */
@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier, valueSize: TextUnit = 22.sp, unit: String? = null) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit != null) Text(" $unit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit, singleLine: Boolean = true) {
    // 最初は全体を選んだ状態（そのまま打てば置き換わる）
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = singleLine,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.text.trim()); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** どのプランに入れるか（既存のプラン / 新しいプラン）。 */
@Composable
fun PlanChooserDialog(
    title: String,
    plans: List<PlanSummary>,
    suggestedNewName: String,
    onChoose: (planId: Long?, newName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf(suggestedNewName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = null },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == null, onClick = { selected = null })
                        Text(stringResource(R.string.new_plan))
                    }
                    if (selected == null) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, singleLine = true,
                            label = { Text(stringResource(R.string.plan_name)) },
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp),
                        )
                    }
                }
                items(plans, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = p.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == p.id, onClick = { selected = p.id })
                        Column {
                            Text(p.name)
                            Text(
                                stringResource(R.string.plan_summary_line, p.routeCount, p.waypointCount, Format.km(p.distanceM)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(selected, if (selected == null) newName.trim() else null); onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** ルートの色を選ぶ。 */
@Composable
fun ColorPickerDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlanRepository.ROUTE_PALETTE.toList().chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(Color(c), CircleShape)
                                    .border(if (c == current) 4.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { onPick(c); onDismiss() },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
