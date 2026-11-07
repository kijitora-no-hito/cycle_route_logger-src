package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.PlanSummary

/** プランの一覧。[pickMode] ならルートサイクリングで走るプランを選ぶ画面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanListScreen(vm: MainViewModel, pickMode: Boolean, onBack: () -> Unit, onOpen: (Long) -> Unit, onPicked: () -> Unit) {
    val plans by remember { vm.plans.summaries() }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PlanSummary?>(null) }
    val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, null)
    }
    val scanQr = rememberQrScan(vm)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (pickMode) R.string.pick_plan_title else R.string.plans_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    ImportMenuButton(
                        onFile = { openGpx.launch(GPX_MIME_TYPES) },
                        onQr = { scanQr(null) },
                        onUrl = { vm.openUrlInput(null) },
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_plan)) },
            )
        },
    ) { pad ->
        if (plans.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.plans_empty))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pickMode && plans.isNotEmpty()) {
                item { Text(stringResource(R.string.pick_plan_hint), style = MaterialTheme.typography.bodyMedium) }
            }
            items(plans, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Card(
                    Modifier.fillMaxWidth().clickable {
                        if (pickMode) {
                            vm.chooseRoute(p.id)
                            onPicked()
                        } else {
                            onOpen(p.id)
                        }
                    },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.plan_summary_line, p.routeCount, p.waypointCount, Format.km(p.distanceM)), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.plan_ascent_line, Format.meters(p.ascentM), Format.dateTime(p.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.open_edit)) }, onClick = { menu = false; onOpen(p.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; vm.duplicatePlan(p.id, p.name) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = p })
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_plan),
            initial = stringResource(R.string.plan_default_name),
            onDone = { vm.createPlan(it, open = !pickMode) },
            onDismiss = { creating = false },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.plan_delete_confirm, p.name),
            text = stringResource(R.string.plan_delete_text),
            confirm = stringResource(R.string.delete),
            onConfirm = { vm.deletePlan(p.id) },
            onDismiss = { deleting = null },
        )
    }
}

// SAF で GPX を選ぶときの MIME（GPX の MIME を登録していない端末も多いので、すべてのファイルも含める）。
val GPX_MIME_TYPES = arrayOf("application/gpx+xml", "application/gpx", "application/xml", "text/xml", "application/octet-stream", "*/*")
