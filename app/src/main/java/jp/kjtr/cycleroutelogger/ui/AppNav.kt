package jp.kjtr.cycleroutelogger.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import jp.kjtr.cycleroutelogger.R

@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val unfinished by vm.unfinished.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val plans by remember { vm.plans.summaries() }.collectAsState(emptyList())

    LaunchedEffect(Unit) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    // オンラインランキングの知らせ（参加できなくなった など）
    LaunchedEffect(Unit) {
        vm.ranking.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) {
        vm.navRequests.collect { route -> nav.navigate(route) { launchSingleTop = true } }
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm,
                onFree = { vm.chooseFree(); nav.navigate("ride") },
                onRoute = { nav.navigate("plans?pick=true") },
                onResumeRide = { nav.navigate("ride") },
                onPlans = { nav.navigate("plans?pick=false") },
                onTracks = { nav.navigate("tracks") },
                onSettings = { nav.navigate("settings") },
                onSummary = { nav.navigate("summary") },
                onRanking = { nav.navigate("ranking") { launchSingleTop = true } },
            )
        }
        // オンラインランキング（0.12.0。ホーム・まとめから）
        composable("ranking") {
            RankingScreen(vm, onBack = { nav.popBackStack() }, onSettings = { nav.navigate("settings") { launchSingleTop = true } })
        }
        composable("ride") {
            RideScreen(vm, onBack = { if (!nav.popBackStack("home", false)) nav.navigate("home") }, onCamera = { nav.navigate("camera") }, onShareCard = { nav.navigate("card/$it") })
        }
        composable("camera") { CameraScreen(vm, onBack = { nav.popBackStack() }) }
        composable(
            "draw/{planId}?routeId={routeId}",
            arguments = listOf(navArgument("planId") { type = NavType.LongType }, navArgument("routeId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            val planId = entry.arguments?.getLong("planId") ?: 0L
            val routeId = entry.arguments?.getLong("routeId")?.takeIf { it > 0 }
            RouteDrawScreen(vm, planId, routeId, onDone = { nav.popBackStack() })
        }
        composable("card/{trackId}", arguments = listOf(navArgument("trackId") { type = NavType.LongType })) { entry ->
            ShareCardScreen(vm, entry.arguments?.getLong("trackId") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable(
            "plans?pick={pick}",
            arguments = listOf(navArgument("pick") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val pick = entry.arguments?.getBoolean("pick") ?: false
            PlanListScreen(
                vm, pickMode = pick,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate("plan/$it") },
                onPicked = { nav.navigate("ride") { popUpTo("home") } },
            )
        }
        composable("plan/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            PlanEditScreen(
                vm, id, onBack = { nav.popBackStack() }, onRide = { nav.navigate("ride") { popUpTo("home") } },
                onDraw = { routeId -> nav.navigate("draw/$id" + (routeId?.let { "?routeId=$it" } ?: "")) },
            )
        }
        composable("tracks") {
            TrackListScreen(
                vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate("track/$it") }, onSummary = { nav.navigate("summary") },
                onImport = { nav.navigate("trackImport") { launchSingleTop = true } },
                onFilter = { nav.navigate("trackFilter") { launchSingleTop = true } },
                onSummaryFiltered = { nav.navigate("summary?filtered=true") },
            )
        }
        // 記録の絞り込み（0.11.0）: 条件の画面と、場所を地図で選ぶ画面
        composable("trackFilter") { TrackFilterScreen(vm, onBack = { nav.popBackStack() }, onPickPlace = { nav.navigate("trackFilterPlace") { launchSingleTop = true } }) }
        composable("trackFilterPlace") { TrackPlaceScreen(vm, onBack = { nav.popBackStack() }) }
        composable("trackImport") { TrackImportScreen(vm, onBack = { nav.popBackStack() }) }
        composable(
            "summary?filtered={filtered}",
            arguments = listOf(navArgument("filtered") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            SummaryScreen(
                vm, filtered = entry.arguments?.getBoolean("filtered") ?: false, onBack = { nav.popBackStack() }, onOpenTrack = { nav.navigate("track/$it") },
                onRanking = { nav.navigate("ranking") { launchSingleTop = true } },
            )
        }
        composable(
            "track/{id}?finished={finished}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("finished") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            TrackDetailScreen(vm, id, justFinished = entry.arguments?.getBoolean("finished") ?: false, onCard = { nav.navigate("card/$id") }, onBack = { nav.popBackStack() }, onOpenPlan = { nav.navigate("plan/$it") })
        }
        composable("settings") {
            SettingsScreen(
                vm, onBack = { nav.popBackStack() },
                // バックアップではなく記録のファイルだった（0.10.2）: 記録の一覧 → 取り込みの画面へ（戻ると記録の一覧で、入口が分かる）
                onOpenImport = {
                    nav.navigate("tracks") { popUpTo("home") }
                    nav.navigate("trackImport") { launchSingleTop = true }
                },
            )
        }
    }

    // 初回の安全上の注意
    if (!settings.safetyAccepted) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.safety_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.safety_text)) } },
            confirmButton = { TextButton(onClick = { vm.updateSettings { it.copy(safetyAccepted = true) } }) { Text(stringResource(R.string.safety_ok)) } },
        )
    } else {
        unfinished?.let { t ->
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text(stringResource(R.string.unfinished_title)) },
                text = { Text(stringResource(R.string.unfinished_text, t.name)) },
                confirmButton = { TextButton(onClick = { vm.continueUnfinished() }) { Text(stringResource(R.string.unfinished_continue)) } },
                dismissButton = { TextButton(onClick = { vm.finalizeUnfinished() }) { Text(stringResource(R.string.unfinished_finish)) } },
            )
        }
    }

    UrlImportDialogs(vm)

    pending?.let { p ->
        PlanChooserDialog(
            title = stringResource(R.string.import_choose_plan, p.items.size),
            plans = plans,
            suggestedNewName = p.suggestedName,
            onChoose = { pid, name -> vm.confirmImport(pid, name) },
            onDismiss = { vm.cancelImport() },
        )
    }
}
