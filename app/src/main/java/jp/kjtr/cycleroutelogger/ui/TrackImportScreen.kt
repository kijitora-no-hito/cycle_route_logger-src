package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.importer.ActivityFormat
import jp.kjtr.cycleroutelogger.importer.ActivityImporter
import jp.kjtr.cycleroutelogger.importer.ActivityImporter.State
import jp.kjtr.cycleroutelogger.importer.FileReport
import jp.kjtr.cycleroutelogger.importer.FileStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SAF で選ぶファイルの種類（zip・GPX・FIT など。拡張子で MIME が決まらない端末が多いので、すべてのファイル）
val ACTIVITY_IMPORT_MIME = arrayOf("*/*")

/**
 * ファイルから記録を取り込む画面（0.10.0。DESIGN.md §4.10）。読み込みの進み具合 → 日ごとの確認（選んで［取り込む］）→ 結果。
 * 中身は [ActivityImporter]（アプリ全体で 1 つ）の状態を映すだけなので、回転・画面を離れても続く。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackImportScreen(vm: MainViewModel, onBack: () -> Unit) {
    val importer = vm.app.activityImport
    val state by importer.state.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importer.start(uris)
    }
    val leave = {
        // 終わった・失敗した表示は閉じる。読み込み・確認待ち・保存中はそのまま（記録の一覧に帯が出る）
        importer.dismiss()
        onBack()
    }
    BackHandler(onBack = leave)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.act_import_title)) },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = {
            val s = state
            if (s is State.Review && s.analysis.days.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { importer.cancel(); onBack() }) { Text(stringResource(R.string.act_import_stop)) }
                        Button(onClick = { importer.importSelected() }, enabled = s.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.act_import_do, s.selected.size))
                        }
                    }
                }
            }
        },
    ) { pad ->
        when (val s = state) {
            State.Idle -> Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.act_import_intro))
                Button(onClick = { pick.launch(ACTIVITY_IMPORT_MIME) }) { Text(stringResource(R.string.act_import_pick)) }
                Text(stringResource(R.string.act_import_note), style = MaterialTheme.typography.bodySmall)
            }
            is State.Reading -> Progress(
                Modifier.padding(pad),
                title = stringResource(R.string.act_import_reading),
                fraction = s.total?.let { (s.bytes.toFloat() / it).coerceIn(0f, 1f) },
                lines = listOfNotNull(
                    if (s.total != null) stringResource(R.string.act_import_bytes, mb(s.bytes), mb(s.total)) else stringResource(R.string.act_import_bytes_only, mb(s.bytes)),
                    stringResource(R.string.act_import_counts, s.files, String.format(Locale.ROOT, "%,d", s.points)),
                    s.current,
                    stringResource(R.string.act_import_background),
                ),
                onStop = { importer.cancel(); onBack() },
            )
            is State.Planning -> Progress(
                Modifier.padding(pad),
                title = if (s.total > 0) stringResource(R.string.act_import_planning, s.done, s.total) else stringResource(R.string.act_import_planning_start),
                fraction = if (s.total > 0) s.done.toFloat() / s.total else null,
                lines = listOf(stringResource(R.string.act_import_background)),
                onStop = { importer.cancel(); onBack() },
            )
            is State.Review -> Review(Modifier.padding(pad), importer, s)
            is State.Importing -> Progress(
                Modifier.padding(pad),
                title = stringResource(R.string.act_import_saving, s.done, s.total),
                fraction = if (s.total > 0) s.done.toFloat() / s.total else null,
                lines = listOf(stringResource(R.string.act_import_saving_note)),
                onStop = { importer.cancel() },
            )
            is State.Done -> Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.act_import_done, s.imported, Format.km(s.distanceM)), style = MaterialTheme.typography.titleMedium)
                Button(onClick = leave) { Text(stringResource(R.string.act_import_to_list)) }
            }
            // このアプリのバックアップを選んだ（0.10.2）: 同じファイルのままバックアップとして読み込める
            is State.LooksLikeBackup -> Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.act_import_backup_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.act_import_backup, s.name ?: "zip"))
                Button(onClick = {
                    vm.importBackup(s.uri, checked = true)
                    importer.dismiss()
                    onBack()
                }) { Text(stringResource(R.string.act_import_backup_restore)) }
                OutlinedButton(onClick = { importer.start(listOf(s.uri), skipBackupCheck = true) }) { Text(stringResource(R.string.act_import_backup_anyway)) }
                OutlinedButton(onClick = leave) { Text(stringResource(R.string.cancel)) }
            }
            is State.Failed -> Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.act_import_failed, s.message))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = leave) { Text(stringResource(R.string.close)) }
                    Button(onClick = { importer.dismiss(); pick.launch(ACTIVITY_IMPORT_MIME) }) { Text(stringResource(R.string.act_import_pick)) }
                }
            }
        }
    }
}

@Composable
private fun Progress(modifier: Modifier, title: String, fraction: Float?, lines: List<String>, onStop: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.act_import_stop)) }
    }
}

@Composable
private fun Review(modifier: Modifier, importer: ActivityImporter, s: State.Review) {
    val a = s.analysis
    var showProblems by rememberSaveable { mutableStateOf(false) }
    var showSame by rememberSaveable { mutableStateOf(false) }
    // 日が多い（Strava の一括書き出しで数百〜千日）ときは年・月の見出しで畳む（0.10.1）。開いている見出しのキー（"2014"・"2014-05"）
    val grouped = a.days.size > GROUP_OVER
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val years = remember(a) { a.days.groupBy { it.label.take(4) }.toSortedMap() }
    val selectedKm = remember(a, s.selected) { a.days.filter { it.key in s.selected }.sumOf { it.distanceM } }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.act_import_summary, a.days.size, s.selected.size), style = MaterialTheme.typography.titleMedium)
                    if (s.selected.isNotEmpty()) Text(stringResource(R.string.act_import_selected_km, Format.km(selectedKm)), style = MaterialTheme.typography.bodyMedium)
                    Text(a.sources.joinToString("、"), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.act_import_files_line, a.okFiles, String.format(Locale.ROOT, "%,d", a.readPoints)), style = MaterialTheme.typography.bodySmall)
                    if (a.catalogFiles > 0) Text(stringResource(R.string.act_import_catalog, a.catalogFiles), style = MaterialTheme.typography.bodySmall)
                    if (a.alreadyImported > 0) Text(stringResource(R.string.act_import_already, a.alreadyImported), style = MaterialTheme.typography.bodySmall)
                    if (a.sameRideDropped > 0) Text(stringResource(R.string.act_import_same_ride_total, a.sameRideDropped), style = MaterialTheme.typography.bodySmall)
                    if (a.sameAsExisting.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().clickable { showSame = !showSame }, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.act_import_same_existing, a.sameAsExisting.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Icon(if (showSame) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                        }
                        if (showSame) a.sameAsExisting.take(200).forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    if (a.routeOnly > 0) Text(stringResource(R.string.act_import_route_only, a.routeOnly), style = MaterialTheme.typography.bodySmall)
                    if (a.ignored > 0) Text(stringResource(R.string.act_import_ignored, a.ignored), style = MaterialTheme.typography.bodySmall)
                    if (a.otherFiles > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.act_import_other_switch, a.otherFiles), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(checked = s.includeOther, onCheckedChange = { importer.setIncludeOther(it) })
                        }
                    }
                    if (a.days.isEmpty()) {
                        Text(
                            stringResource(if (a.alreadyImported > 0 || a.sameAsExisting.isNotEmpty()) R.string.act_import_nothing_new else R.string.act_import_none),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(stringResource(R.string.act_import_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        val problems = a.problems
        if (problems.isNotEmpty()) {
            item(key = "problems") {
                Card(Modifier.fillMaxWidth().clickable { showProblems = !showProblems }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.act_import_problems, problems.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Icon(if (showProblems) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                        }
                        if (showProblems) problems.take(200).forEach { ProblemLine(it) }
                    }
                }
            }
        }
        if (a.days.isNotEmpty()) {
            item(key = "select") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { importer.selectAll(true, includeOverlaps = true) }) { Text(stringResource(R.string.act_import_select_everything)) }
                    TextButton(onClick = { importer.selectAll(false) }) { Text(stringResource(R.string.act_import_select_none)) }
                }
            }
        }
        if (!grouped) {
            items(a.days, key = { it.key }) { d -> DayCard(d, d.key in s.selected) { importer.toggle(d.key) } }
        } else {
            for ((year, yDays) in years) {
                val yOpen = year in open
                item(key = "y$year") {
                    GroupHeader(
                        title = stringResource(R.string.act_import_year, year),
                        days = yDays, selected = s.selected, open = yOpen, level = 0,
                        onOpen = { open = if (yOpen) open - year else open + year },
                        onCheck = { on -> importer.selectDays(yDays.map { it.key }, on) },
                    )
                }
                if (!yOpen) continue
                for ((month, mDays) in yDays.groupBy { it.label.substring(5, 7) }.toSortedMap()) {
                    val key = "$year-$month"
                    val mOpen = key in open
                    item(key = "m$key") {
                        GroupHeader(
                            title = stringResource(R.string.act_import_month, month.toInt()),
                            days = mDays, selected = s.selected, open = mOpen, level = 1,
                            onOpen = { open = if (mOpen) open - key else open + key },
                            onCheck = { on -> importer.selectDays(mDays.map { it.key }, on) },
                        )
                    }
                    if (mOpen) items(mDays, key = { it.key }) { d -> DayCard(d, d.key in s.selected) { importer.toggle(d.key) } }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(8.dp)) }
    }
}

/** 年・月の見出し（日数・距離・選んだ日数、チェックでまとめて選ぶ / 外す、タップで開く / 畳む）。 */
@Composable
private fun GroupHeader(
    title: String,
    days: List<ActivityImporter.DayRow>,
    selected: Set<Long>,
    open: Boolean,
    level: Int,
    onOpen: () -> Unit,
    onCheck: (Boolean) -> Unit,
) {
    val n = days.count { it.key in selected }
    val state = when (n) {
        0 -> ToggleableState.Off
        days.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val km = remember(days) { days.sumOf { it.distanceM } }
    Surface(
        Modifier.fillMaxWidth().padding(start = (level * 16).dp).clickable(onClick = onOpen),
        tonalElevation = if (level == 0) 3.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(state = state, onClick = { onCheck(state != ToggleableState.On) })
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = if (level == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.act_import_group_stats, days.size, Format.km(km), n), style = MaterialTheme.typography.bodySmall)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/** これより多い日数なら年・月の見出しで畳む。 */
private const val GROUP_OVER = 31

@Composable
private fun ProblemLine(f: FileReport) {
    val status = stringResource(
        when (f.status) {
            FileStatus.PARTIAL -> R.string.act_import_status_partial
            FileStatus.ROUTE_ONLY -> R.string.act_import_status_route
            FileStatus.NO_TIME -> R.string.act_import_status_no_time
            FileStatus.NO_POSITION -> R.string.act_import_status_no_position
            FileStatus.TOO_LARGE -> R.string.act_import_status_too_large
            else -> R.string.act_import_status_broken
        },
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(status + (f.detail?.let { "（$it）" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DayCard(d: ActivityImporter.DayRow, selected: Boolean, onToggle: () -> Unit) {
    var showFiles by rememberSaveable(d.key) { mutableStateOf(false) }
    val warn = d.overlaps.isNotEmpty()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        colors = if (warn) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(dayLabel(d.start) + if (d.key % 16 != 0L) "  (${d.key % 16 + 1})" else "", style = MaterialTheme.typography.titleMedium)
                if (d.titles.isNotEmpty()) {
                    Text(d.titles.joinToString(" / "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.act_import_day_stats, Format.km(d.distanceM), Format.duration(d.movingMs), Format.time(d.start), Format.time(d.end)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.act_import_day_files, d.files.size, d.formats.joinToString("・") { it.label }),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (d.appendTo != null) {
                    Text(stringResource(R.string.act_import_append_to, d.appendTo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                d.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (warn) {
                    Text(
                        stringResource(R.string.act_import_elsewhere, d.overlaps.take(3).joinToString("、") + if (d.overlaps.size > 3) " ほか" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (d.hasOther) Text(stringResource(R.string.act_import_has_other), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                if (showFiles) d.files.forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = { showFiles = !showFiles }) {
                Icon(if (showFiles) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
        }
    }
}

/** 「2026/05/03 (日)」。 */
private fun dayLabel(ms: Long): String = SimpleDateFormat("yyyy/MM/dd (E)", Locale.JAPAN).format(Date(ms))

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

/** 記録の印（一覧・詳細）: 「取り込み（FIT・GPX）」の括弧の中。 */
fun importFormatsLabel(code: String?): String = ActivityFormat.decode(code).joinToString("・") { it.label }.ifEmpty { "?" }
