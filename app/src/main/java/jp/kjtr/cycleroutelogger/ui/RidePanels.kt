package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.geo.Bearing
import jp.kjtr.cycleroutelogger.guide.DestinationState
import jp.kjtr.cycleroutelogger.guide.GuideState
import jp.kjtr.cycleroutelogger.plan.WaypointType
import jp.kjtr.cycleroutelogger.record.RecordingState
import jp.kjtr.cycleroutelogger.record.RideTracker

private val panelShape = RoundedCornerShape(14.dp)
private val tightPad = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)

@Composable
fun PanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = panelShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 4.dp,
        content = content,
    )
}

/** 「北東 1.2 km」 */
@Composable
fun directionText(bearingDeg: Double?, distanceM: Double?): String {
    if (bearingDeg == null || distanceM == null) return stringResource(R.string.waiting_gps)
    val names = stringArrayResource(R.array.octants)
    return names[Bearing.octant(bearingDeg)] + " " + Format.distance(distanceM)
}

/** 目的地のパネル（方向の矢印・方角と距離・ルート沿いの残り）。 */
@Composable
fun DestinationPanel(state: DestinationState, mapBearing: Float, onClear: () -> Unit, modifier: Modifier = Modifier, big: Boolean = false) {
    val accent = colorResource(R.color.destination)
    PanelSurface(modifier) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val rot = state.bearingDeg?.let { Bearing.screenRotation(it, mapBearing.toDouble()).toFloat() }
            Box(Modifier.size(if (big) 96.dp else 52.dp), contentAlignment = Alignment.Center) {
                if (rot != null) {
                    Icon(
                        Icons.Filled.Navigation,
                        contentDescription = stringResource(R.string.cd_direction),
                        tint = accent,
                        modifier = Modifier.fillMaxSize().rotate(rot),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.dest_label, state.destination.name.ifBlank { stringResource(R.string.dest_unnamed) }),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    directionText(state.bearingDeg, state.distanceM),
                    fontSize = if (big) 44.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                state.alongRouteM?.let {
                    Text(stringResource(R.string.dest_along, Format.distance(it)), fontSize = if (big) 22.sp else 16.sp)
                }
            }
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dest_clear)) }
        }
    }
}

/** ルートの案内（外れ・進捗・次の経由地）。 */
@Composable
fun GuidePanel(g: GuideState, modifier: Modifier = Modifier, big: Boolean = false) {
    val warn = colorResource(R.color.off_route)
    PanelSurface(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val m = g.match
                if (m == null) {
                    Text(stringResource(R.string.waiting_gps), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.off_route_label), style = MaterialTheme.typography.labelMedium)
                        Text(
                            Format.distance(m.deviationM),
                            fontSize = if (big) 34.sp else 22.sp, fontWeight = FontWeight.Bold,
                            color = if (g.offRoute) warn else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.progress_label), style = MaterialTheme.typography.labelMedium)
                        Text(Format.km(m.progressM), fontSize = if (big) 28.sp else 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.remaining_label), style = MaterialTheme.typography.labelMedium)
                        Text(Format.km(m.remainingM), fontSize = if (big) 28.sp else 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (g.offRoute) {
                Text(stringResource(R.string.off_route_warning), color = warn, fontWeight = FontWeight.Bold)
            }
            val next = g.next
            if (next != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    val t = WaypointType.fromCode(next.item.type)
                    WaypointBadge(t, size = if (big) 36.dp else 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.next_wp, next.item.name.ifBlank { t.label() }, t.label()),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = if (big) 20.sp else 14.sp,
                    )
                    g.nextAlongM?.let {
                        Text(stringResource(R.string.next_wp_along, Format.distance(it)), fontWeight = FontWeight.Bold, fontSize = if (big) 24.sp else 16.sp)
                    }
                }
            }
        }
    }
}

/** 下の記録パネル。 */
@Composable
fun RecordPanel(
    rec: RecordingState?,
    now: Long,
    modeLabel: String,
    canStart: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onBig: () -> Unit,
    modifier: Modifier = Modifier,
    /** 記録中の裏の様子（「バックグラウンドでも記録中」・止まっている・位置情報 OFF など）。 */
    statusLine: String? = null,
    statusWarn: Boolean = false,
    statusActionLabel: String? = null,
    onStatusAction: (() -> Unit)? = null,
    /** 今の測位の方式（0.9.0〜「端末の GPS」/「Google の融合測位」。小さく出す）。 */
    positioningLabel: String? = null,
) {
    PanelSurface(modifier) {
        Column(Modifier.padding(12.dp)) {
            if (rec != null && statusLine != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(
                        statusLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (statusWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (statusWarn) FontWeight.Bold else null,
                        modifier = Modifier.weight(1f),
                    )
                    if (statusActionLabel != null && onStatusAction != null) {
                        androidx.compose.material3.TextButton(onClick = onStatusAction, contentPadding = tightPad) { Text(statusActionLabel) }
                    }
                }
            }
            if (rec == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(modeLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (positioningLabel != null) {
                        Text(positioningLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStart, enabled = canStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.record_start), fontSize = 20.sp)
                }
                return@Column
            }
            val speed = if (rec.lastFixAt != null && now - rec.lastFixAt > RideTracker.STALE_MS) 0.0 else rec.currentSpeedMps
            Row {
                StatCell(stringResource(R.string.stat_speed), Format.speed(speed), Modifier.weight(1.2f), valueSize = 40.sp, unit = "km/h")
                StatCell(stringResource(R.string.stat_distance), Format.kmNumber(rec.distanceM), Modifier.weight(1f), valueSize = 30.sp, unit = "km")
            }
            Row {
                StatCell(stringResource(R.string.stat_moving), Format.duration(rec.movingMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_elapsed), Format.duration(now - rec.startedAt), Modifier.weight(1f))
                StatCell(stringResource(R.string.stat_ascent), Format.meters(rec.ascentM), Modifier.weight(1f))
            }
            Row {
                StatCell(stringResource(R.string.stat_avg), Format.speed(rec.averageSpeedMps), Modifier.weight(1f), unit = "km/h")
                StatCell(stringResource(R.string.stat_max), Format.speed(rec.maxSpeedMps), Modifier.weight(1f), unit = "km/h")
                Column(Modifier.weight(1f)) {
                    val status = when {
                        rec.paused -> stringResource(R.string.status_paused)
                        rec.autoPaused -> stringResource(R.string.status_auto_paused)
                        rec.lastFixAt == null -> stringResource(R.string.waiting_gps)
                        else -> stringResource(R.string.status_recording)
                    }
                    Text(status, color = colorResource(R.color.recording), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    if (positioningLabel != null) {
                        Text(positioningLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rec.paused) {
                    Button(onClick = onResume, contentPadding = tightPad, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null); Text(stringResource(R.string.resume), maxLines = 1, softWrap = false)
                    }
                } else {
                    FilledTonalButton(onClick = onPause, contentPadding = tightPad, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Filled.Pause, contentDescription = null); Text(stringResource(R.string.pause), maxLines = 1, softWrap = false)
                    }
                }
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.recording)),
                    contentPadding = tightPad,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null); Text(stringResource(R.string.finish), maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = onBig, contentPadding = tightPad, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.big_view), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** 走行中の大きな表示（地図を隠して数字だけ）。 */
@Composable
fun BigRideView(
    rec: RecordingState?,
    now: Long,
    guide: GuideState?,
    dest: DestinationState?,
    mapBearing: Float,
    onClearDest: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_map)) }
                Text(stringResource(R.string.big_view), style = MaterialTheme.typography.titleLarge)
            }
            if (rec != null) {
                val speed = if (rec.lastFixAt != null && now - rec.lastFixAt > RideTracker.STALE_MS) 0.0 else rec.currentSpeedMps
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Format.speed(speed), fontSize = 96.sp, fontWeight = FontWeight.Bold)
                    Text(" km/h", fontSize = 24.sp, modifier = Modifier.padding(bottom = 18.dp))
                }
                Row {
                    StatCell(stringResource(R.string.stat_distance), Format.kmNumber(rec.distanceM), Modifier.weight(1f), valueSize = 48.sp, unit = "km")
                    StatCell(stringResource(R.string.stat_moving), Format.duration(rec.movingMs), Modifier.weight(1f), valueSize = 40.sp)
                }
                Row {
                    StatCell(stringResource(R.string.stat_avg), Format.speed(rec.averageSpeedMps), Modifier.weight(1f), valueSize = 36.sp, unit = "km/h")
                    StatCell(stringResource(R.string.stat_ascent), Format.meters(rec.ascentM), Modifier.weight(1f), valueSize = 36.sp)
                }
                if (rec.paused || rec.autoPaused) {
                    Text(
                        stringResource(if (rec.paused) R.string.status_paused else R.string.status_auto_paused),
                        color = colorResource(R.color.recording), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (dest != null) DestinationPanel(dest, mapBearing, onClearDest, Modifier.fillMaxWidth(), big = true)
            if (guide != null) GuidePanel(guide, Modifier.fillMaxWidth(), big = true)
            if (rec != null) {
                if (rec.paused) {
                    Button(onClick = onResume, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text(stringResource(R.string.resume), fontSize = 22.sp) }
                } else {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text(stringResource(R.string.pause), fontSize = 22.sp) }
                }
            }
        }
    }
}

/** 画面外の目的地を指す、画面端の矢印と距離。 */
@Composable
fun EdgeArrow(rotationDeg: Float, distanceM: Double?, modifier: Modifier = Modifier) {
    val accent = colorResource(R.color.destination)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Navigation, contentDescription = stringResource(R.string.cd_direction), tint = accent, modifier = Modifier.size(34.dp).rotate(rotationDeg))
        }
        if (distanceM != null) {
            Text(
                Format.distance(distanceM),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.background(accent, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}
