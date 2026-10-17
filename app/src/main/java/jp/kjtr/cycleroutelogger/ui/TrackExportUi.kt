package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.GpxSource
import jp.kjtr.cycleroutelogger.record.TrackExport
import kotlinx.coroutines.launch

/** 記録でできること（ルートとして保存・GPX で書き出す・共有）。 */
enum class TrackAction { ROUTE, GPX, SHARE }

/** 記録の 3 つの操作のボタン（記録の詳細の上部・記録を終えたとき）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackActionButtons(onAction: (TrackAction) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onAction(TrackAction.ROUTE) }) {
            Icon(Icons.Filled.Route, contentDescription = null); Text(" " + stringResource(R.string.track_save_route))
        }
        OutlinedButton(onClick = { onAction(TrackAction.GPX) }) {
            Icon(Icons.Filled.FileDownload, contentDescription = null); Text(" " + stringResource(R.string.export_gpx))
        }
        OutlinedButton(onClick = { onAction(TrackAction.SHARE) }) {
            Icon(Icons.Filled.Share, contentDescription = null); Text(" " + stringResource(R.string.share))
        }
    }
}

/** 記録を終えたときの案内（［ルートとして保存］［GPX で書き出す］［共有］［あとで］）。 */
@Composable
fun TrackFinishedDialog(name: String, onAction: (TrackAction) -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.track_finished_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.track_finished_text, name))
                TextButton(onClick = { onAction(TrackAction.ROUTE) }) {
                    Icon(Icons.Filled.Route, contentDescription = null); Text(" " + stringResource(R.string.track_save_route_long))
                }
                TextButton(onClick = { onAction(TrackAction.GPX) }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null); Text(" " + stringResource(R.string.export_gpx))
                }
                TextButton(onClick = { onAction(TrackAction.SHARE) }) {
                    Icon(Icons.Filled.Share, contentDescription = null); Text(" " + stringResource(R.string.share))
                }
            }
        },
        confirmButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.later)) } },
    )
}

/**
 * [action] のダイアログと書き出し先の選択（SAF）を受け持つ。[trackId] / [action] が null なら何も出さない。
 * 終わったら（取り消しも）[onDone]。
 */
@Composable
fun TrackActionHost(vm: MainViewModel, trackId: Long?, action: TrackAction?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<Triple<Long, Boolean, Boolean>?>(null) }
    var pendingSource by remember { mutableStateOf(GpxSource.RECORDED) }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val p = pendingExport
        pendingExport = null
        if (uri != null && p != null) vm.exportTrack(p.first, uri, p.second, p.third, pendingSource)
    }
    if (trackId == null || action == null) return
    val track by produceState<TrackEntity?>(null, trackId) { value = vm.tracks.trackNow(trackId) }
    val t = track ?: return

    when (action) {
        TrackAction.ROUTE -> SaveAsRouteDialog(vm, t, onDone)
        TrackAction.GPX, TrackAction.SHARE -> {
            val passed by produceState(-1, trackId) { value = vm.passedWaypointCount(trackId) }
            var withWps by remember { mutableStateOf(true) }
            val photoCount by produceState(0, trackId) { value = vm.photos.forTrackNow(trackId).count { it.lat != null } }
            var withPhotos by remember { mutableStateOf(true) }
            // 0.9.0〜 書き出す位置（既定は設定「軌跡をなめらかにする」に合わせる＝地図の線と同じ）
            val smoothingOn = vm.settings.collectAsState().value.trackSmoothing
            var source by remember { mutableStateOf(if (smoothingOn) GpxSource.SMOOTHED else GpxSource.RECORDED) }
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text(stringResource(if (action == TrackAction.GPX) R.string.export_gpx else R.string.share)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.track_export_contents, t.pointCount))
                        if (passed > 0) {
                            Row(Modifier.fillMaxWidth().clickable { withWps = !withWps }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = withWps, onCheckedChange = { withWps = it })
                                Text(stringResource(R.string.track_export_waypoints, passed))
                            }
                        }
                        if (photoCount > 0) {
                            Row(Modifier.fillMaxWidth().clickable { withPhotos = !withPhotos }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = withPhotos, onCheckedChange = { withPhotos = it })
                                Text(stringResource(R.string.track_export_photos, photoCount))
                            }
                        }
                        Text(stringResource(R.string.gpx_source), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        listOf(GpxSource.SMOOTHED to R.string.gpx_smoothed, GpxSource.RECORDED to R.string.gpx_recorded).forEach { (src, label) ->
                            Row(Modifier.fillMaxWidth().clickable { source = src }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = source == src, onClick = { source = src })
                                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(vm.trackFileBase(t) + ".gpx", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val include = passed > 0 && withWps
                        val includePhotos = photoCount > 0 && withPhotos
                        if (action == TrackAction.GPX) {
                            pendingExport = Triple(trackId, include, includePhotos)
                            pendingSource = source
                            exportGpx.launch(vm.trackFileBase(t) + ".gpx")
                        } else {
                            val src = source
                            scope.launch {
                                val data = vm.trackGpx(trackId, include, includePhotos, src) ?: return@launch
                                vm.shareFile(data, vm.trackFileBase(t), t.startedAt)?.let { shareGpx(context, it) }
                            }
                        }
                        onDone()
                    }) { Text(stringResource(if (action == TrackAction.GPX) R.string.track_export_choose else R.string.share)) }
                },
                dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** ルートとして保存: 入れるプラン・ルート名・間引き・止まっていた点を除く。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveAsRouteDialog(vm: MainViewModel, t: TrackEntity, onDone: () -> Unit) {
    val plans by remember { vm.plans.summaries() }.collectAsState(emptyList())
    var selected by remember { mutableStateOf<Long?>(t.planId) }
    var newPlanName by remember { mutableStateOf(t.planName ?: t.name) }
    var routeName by remember { mutableStateOf(t.name) }
    var tolerance by remember { mutableStateOf<Double?>(5.0) }
    var dropStops by remember { mutableStateOf(true) }
    // 記録のプランが消されていたら新しいプランに
    if (selected != null && plans.isNotEmpty() && plans.none { it.id == selected }) selected = null

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.track_save_route_long)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    OutlinedTextField(
                        value = routeName, onValueChange = { routeName = it }, singleLine = true,
                        label = { Text(stringResource(R.string.route_name)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.route_thin), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrackExport.TOLERANCES.forEach { tol ->
                            FilterChip(
                                selected = tolerance == tol, onClick = { tolerance = tol },
                                label = { Text(if (tol == null) stringResource(R.string.route_thin_none) else "${tol.toInt()} m") },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { dropStops = !dropStops }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dropStops, onCheckedChange = { dropStops = it })
                        Text(stringResource(R.string.route_drop_stops), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.route_save_to), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().clickable { selected = null }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == null, onClick = { selected = null })
                        Text(stringResource(R.string.new_plan))
                    }
                    if (selected == null) {
                        OutlinedTextField(
                            value = newPlanName, onValueChange = { newPlanName = it }, singleLine = true,
                            label = { Text(stringResource(R.string.plan_name)) },
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp),
                        )
                    }
                    HorizontalDivider()
                }
                items(plans, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { selected = p.id }, verticalAlignment = Alignment.CenterVertically) {
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
            TextButton(onClick = {
                vm.saveTrackAsRoute(t.id, selected, if (selected == null) newPlanName.trim() else null, routeName.trim(), tolerance, dropStops)
                onDone()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}
