package jp.kjtr.cycleroutelogger.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.plan.WaypointType
import java.util.Calendar

/**
 * 経由地の追加・編集。[onGo] を渡すと［ここへ向かう］も出す（保存してから目的地にする）。
 * [onDelete] は既存の経由地だけ。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaypointDialog(
    initial: WaypointEntity,
    isNew: Boolean,
    onSave: (WaypointEntity) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onGo: ((WaypointEntity) -> Unit)? = null,
    alongText: String? = null,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(WaypointType.fromCode(initial.type)) }
    var memo by remember { mutableStateOf(initial.memo) }
    var planned by remember { mutableStateOf(initial.plannedAt) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun result() = initial.copy(name = name.trim().ifBlank { context.getString(type.style().label) }, type = type.code, memo = memo.trim(), plannedAt = planned)

    fun pickTime() {
        val cal = Calendar.getInstance().apply { planned?.let { timeInMillis = it } }
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                cal.set(y, m, d, h, min, 0)
                cal.set(Calendar.MILLISECOND, 0)
                planned = cal.timeInMillis
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.wp_add_title else R.string.wp_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alongText != null) Text(alongText, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.wp_name)) }, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.wp_type), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WaypointType.entries.forEach { t ->
                        FilterChip(
                            selected = t == type,
                            onClick = { type = t },
                            label = { Text(t.label()) },
                            leadingIcon = { WaypointBadge(t, size = 22.dp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = memo, onValueChange = { memo = it },
                    label = { Text(stringResource(R.string.wp_memo)) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        planned?.let { stringResource(R.string.wp_planned_at, Format.dateTime(it)) } ?: stringResource(R.string.wp_no_planned),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickTime() }) { Text(stringResource(R.string.wp_set_time)) }
                    if (planned != null) TextButton(onClick = { planned = null }) { Text(stringResource(R.string.clear)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (onGo != null) TextButton(onClick = { onGo(result()); onDismiss() }) { Text(stringResource(R.string.go_here)) }
                    if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(result()); onDismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.wp_delete_confirm, name),
            text = null,
            confirm = stringResource(R.string.delete),
            onConfirm = { onDelete(); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}
