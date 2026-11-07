package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.cycleroutelogger.BuildConfig
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.MapKind
import jp.kjtr.cycleroutelogger.data.Positioning
import jp.kjtr.cycleroutelogger.data.SettingsStore
import jp.kjtr.cycleroutelogger.ranking.RankingSpec

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenImport: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val restoring by vm.restoring.collectAsStateWithLifecycle()
    // バックアップではないファイルを選んだときの案内（0.10.2）
    when (val n = vm.backupNotice.collectAsStateWithLifecycle().value) {
        is MainViewModel.BackupNotice.Activity -> AlertDialog(
            onDismissRequest = vm::dismissBackupNotice,
            title = { Text(stringResource(R.string.backup_notice_activity_title)) },
            text = { Text(listOfNotNull(n.name, stringResource(R.string.backup_notice_activity)).joinToString("\n\n")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.importAsActivities(n.uri)
                    onOpenImport()
                }) { Text(stringResource(R.string.backup_notice_go_import)) }
            },
            dismissButton = { TextButton(onClick = vm::dismissBackupNotice) { Text(stringResource(R.string.cancel)) } },
        )
        is MainViewModel.BackupNotice.Unknown -> AlertDialog(
            onDismissRequest = vm::dismissBackupNotice,
            title = { Text(stringResource(R.string.backup_notice_unknown_title)) },
            text = { Text(listOfNotNull(n.name, stringResource(R.string.backup_notice_unknown)).joinToString("\n\n")) },
            confirmButton = { TextButton(onClick = vm::dismissBackupNotice) { Text(stringResource(R.string.close)) } },
        )
        null -> Unit
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section(stringResource(R.string.set_map))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapKind.entries.forEach { k ->
                    FilterChip(selected = s.mapKind == k, onClick = { vm.updateSettings { it.copy(mapKind = k) } }, label = { Text(stringResource(mapKindLabel(k))) })
                }
            }
            Text(stringResource(R.string.set_unit), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()

            Section(stringResource(R.string.set_record))
            Text(stringResource(R.string.set_interval))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.INTERVALS.forEach { sec ->
                    FilterChip(selected = s.intervalSec == sec, onClick = { vm.updateSettings { it.copy(intervalSec = sec) } }, label = { Text(stringResource(R.string.seconds, sec)) })
                }
            }
            // 測位の方式（0.9.0〜。Google の融合測位は Play 開発者サービスが無ければ端末の GPS）
            Text(stringResource(R.string.set_positioning_mode))
            val fusedOk = remember { vm.locationHub.fusedAvailable() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Positioning.entries.forEach { p ->
                    FilterChip(
                        selected = s.positioning == p, onClick = { vm.updateSettings { it.copy(positioning = p) } },
                        label = { Text(stringResource(if (p == Positioning.GOOGLE) R.string.positioning_google else R.string.positioning_device)) },
                    )
                }
            }
            val fusedNg = s.positioning == Positioning.GOOGLE && !fusedOk
            Text(
                stringResource(if (fusedNg) R.string.set_positioning_unavailable else R.string.set_positioning_desc),
                style = MaterialTheme.typography.bodySmall,
                color = if (fusedNg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(stringResource(R.string.set_track_smoothing), stringResource(R.string.set_track_smoothing_desc), s.trackSmoothing) { v ->
                vm.updateSettings { it.copy(trackSmoothing = v) }
            }
            SwitchRow(stringResource(R.string.set_auto_pause), stringResource(R.string.set_auto_pause_desc), s.autoPause) { v -> vm.updateSettings { it.copy(autoPause = v) } }
            SwitchRow(stringResource(R.string.set_keep_screen), null, s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
            SwitchRow(stringResource(R.string.set_photo_exif_location), stringResource(R.string.set_photo_exif_location_desc), s.photoExifLocation) { v ->
                vm.updateSettings { it.copy(photoExifLocation = v) }
            }
            HorizontalDivider()

            Section(stringResource(R.string.set_background))
            BackgroundRecordingSection()
            HorizontalDivider()

            Section(stringResource(R.string.set_guide))
            SwitchRow(stringResource(R.string.set_off_route), null, s.offRouteAlert) { v -> vm.updateSettings { it.copy(offRouteAlert = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsStore.OFF_ROUTE_CHOICES.forEach { m ->
                    FilterChip(selected = s.offRouteThresholdM == m, onClick = { vm.updateSettings { it.copy(offRouteThresholdM = m) } }, label = { Text("$m m") })
                }
            }
            SwitchRow(stringResource(R.string.set_approach), null, s.approachAlert) { v -> vm.updateSettings { it.copy(approachAlert = v) } }
            SwitchRow(stringResource(R.string.set_sound), null, s.alertSound) { v -> vm.updateSettings { it.copy(alertSound = v) } }
            SwitchRow(stringResource(R.string.set_vibrate), null, s.alertVibrate) { v -> vm.updateSettings { it.copy(alertVibrate = v) } }
            SwitchRow(stringResource(R.string.set_auto_next), stringResource(R.string.set_auto_next_desc), s.autoNextDestination) { v -> vm.updateSettings { it.copy(autoNextDestination = v) } }
            HorizontalDivider()

            Section(stringResource(R.string.set_data))
            Text(stringResource(R.string.set_backup_desc), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.set_backup_desc_import), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportZip.launch("cycleroutelogger-backup-${Format.fileStamp(System.currentTimeMillis())}.zip") }) { Text(stringResource(R.string.backup_export)) }
                OutlinedButton(onClick = { importZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !restoring) { Text(stringResource(R.string.backup_import)) }
            }
            if (restoring) Text(stringResource(R.string.backup_checking), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()

            // オンラインランキング（0.12.0。既定 OFF。ranking_server/docs/API.md）
            RankingSettingsSection(
                sync = vm.ranking,
                sentValues = RankingSpec.SENT_VALUES,
                notSent = RankingSpec.NOT_SENT,
                debug = BuildConfig.DEBUG,
                onMessage = { vm.sayText(it) },
            )
            HorizontalDivider()

            Section(stringResource(R.string.set_update))
            UpdateSettingsSection(vm) { title, desc, checked, onChange -> SwitchRow(title, desc, checked, onChange) }
            HorizontalDivider()

            Section(stringResource(R.string.set_about))
            Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.safety_show_again),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { vm.updateSettings { it.copy(safetyAccepted = false) } }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
