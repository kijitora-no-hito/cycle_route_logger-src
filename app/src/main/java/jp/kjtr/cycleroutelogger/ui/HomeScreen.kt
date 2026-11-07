package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.TrackEntity

/** ホーム: フリー / ルートの 2 択と、プラン・記録・設定。 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onFree: () -> Unit,
    onRoute: () -> Unit,
    onResumeRide: () -> Unit,
    onPlans: () -> Unit,
    onTracks: () -> Unit,
    onSettings: () -> Unit,
    onSummary: () -> Unit,
    onRanking: () -> Unit,
) {
    val rec by vm.session.state.collectAsStateWithLifecycle()
    val ride by vm.ride.collectAsStateWithLifecycle()
    // 起動して最初にホームが出たとき 1 回だけ、裏で版の一覧を読む（OFF・6 時間以内なら何もしない）。
    LaunchedEffect(Unit) { vm.updates.checkOnLaunch() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            // 更新の確認の帯（記録中は出さない。DESIGN.md §10）
            if (rec == null) UpdateBanner(vm)
            if (rec != null) {
                Card(
                    onClick = onResumeRide,
                    colors = CardDefaults.cardColors(containerColor = colorResource(R.color.recording)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.home_recording), color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.home_back_to_ride), color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            } else if (ride != null) {
                FilledTonalButton(onClick = onResumeRide, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (ride?.mode == TrackEntity.MODE_ROUTE) stringResource(R.string.home_last_route) else stringResource(R.string.home_last_free),
                    )
                }
            }
            Text(stringResource(R.string.home_choose_mode), style = MaterialTheme.typography.titleMedium)
            ModeCard(
                icon = Icons.Filled.Map,
                title = stringResource(R.string.mode_free),
                desc = stringResource(R.string.mode_free_desc),
                enabled = rec == null,
                onClick = onFree,
            )
            ModeCard(
                icon = Icons.Filled.Route,
                title = stringResource(R.string.mode_route),
                desc = stringResource(R.string.mode_route_desc),
                enabled = rec == null,
                onClick = onRoute,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavButton(Icons.Filled.Route, stringResource(R.string.plans_title), onPlans, Modifier.weight(1f))
                NavButton(Icons.Filled.History, stringResource(R.string.tracks_title), onTracks, Modifier.weight(1f))
                NavButton(Icons.Filled.Settings, stringResource(R.string.settings_title), onSettings, Modifier.weight(1f))
            }
            // 0.12.0〜 まとめとオンラインランキング
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavButton(Icons.Filled.BarChart, stringResource(R.string.summary_title), onSummary, Modifier.weight(1f))
                NavButton(Icons.Filled.Leaderboard, stringResource(R.string.ranking_title), onRanking, Modifier.weight(1f))
            }
            Text(stringResource(R.string.home_safety_line), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(150.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.fillMaxSize().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun NavButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Card(onClick = onClick, modifier = modifier.height(84.dp)) {
        Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
