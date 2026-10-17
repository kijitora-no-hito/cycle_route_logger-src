package jp.kjtr.cycleroutelogger

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import jp.kjtr.cycleroutelogger.geo.NominatimSearch
import jp.kjtr.cycleroutelogger.geo.PlaceSearch
import jp.kjtr.cycleroutelogger.data.AppDatabase
import jp.kjtr.cycleroutelogger.data.Backup
import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.data.SettingsStore
import jp.kjtr.cycleroutelogger.data.SummaryRepository
import jp.kjtr.cycleroutelogger.data.TrackBrowser
import jp.kjtr.cycleroutelogger.data.TrackGeoIndex
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.guide.Alerter
import jp.kjtr.cycleroutelogger.importer.ActivityImporter
import jp.kjtr.cycleroutelogger.guide.Navigator
import jp.kjtr.cycleroutelogger.photo.PhotoRepository
import jp.kjtr.cycleroutelogger.record.RecorderStatus
import jp.kjtr.cycleroutelogger.record.RecordingSession
import jp.kjtr.cycleroutelogger.sensor.LocationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import jp.kjtr.cycleroutelogger.update.UpdateManager
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** アプリ全体で 1 つずつのもの（DB・設定・測位・記録・案内）。 */
class CycleApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val plans: PlanRepository by lazy { PlanRepository(db) }
    val tracks: TrackRepository by lazy { TrackRepository(db) }
    val backup: Backup by lazy { Backup(db, plans, tracks) }
    /** 全記録のまとめの材料（記録ごとの統計と間引いた線。メモリに覚える）。 */
    val summaries: SummaryRepository by lazy { SummaryRepository(db) }
    val photos: PhotoRepository by lazy { PhotoRepository(this, db) }
    /** 記録の場所の索引（0.11.0。外接矩形・始点・終点・間引いた線。起動したらバックグラウンドで足りない分を作る）。 */
    val geoIndex: TrackGeoIndex by lazy { TrackGeoIndex(db, appScope) }
    /** 記録の一覧の絞り込み・並べ替え（0.11.0。条件は保存して次回も残す）。 */
    val trackBrowser: TrackBrowser by lazy { TrackBrowser(this, db, geoIndex, appScope) }
    /** ファイル（zip / GPX / FIT / TCX / KML）から記録を取り込む（0.10.0。アプリのスコープで動き、画面の回転では止まらない）。 */
    val activityImport: ActivityImporter by lazy { ActivityImporter(this, appScope) }
    val locationHub: LocationHub by lazy { LocationHub(this) }
    val session: RecordingSession by lazy {
        RecordingSession(db, appScope).also { s -> s.smoothingEnabled = { settings.settings.value.trackSmoothing } }
    }
    /** 記録サービスの様子（サービスが書き、画面が読む）。 */
    val recorder = RecorderStatus()
    val alerter: Alerter by lazy { Alerter(this) }
    val navigator: Navigator by lazy { Navigator(appScope, locationHub, settings, plans, alerter) }
    /** 更新の確認（GitHub の releases.json。設定で OFF なら通信しない）。 */
    val updates: UpdateManager by lazy { UpdateManager(this, appScope) { settings.settings.value.updateCheck } }
    /**
     * 場所名の検索（0.6.0。Nominatim / OpenStreetMap。検索ボタンを押したときだけ通信。結果はメモリに覚えるだけで保存しない）。
     * 別のサービスに替えるときはここを差し替える（`PlaceSearch`）。
     */
    val placeSearch: PlaceSearch by lazy {
        NominatimSearch(userAgent = NominatimSearch.userAgent(BuildConfig.VERSION_NAME), online = ::isOnline)
    }

    /** ネットにつながっていそうか（つながっていなければ検索を送らずに「オフライン」と出す）。 */
    fun isOnline(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    override fun onCreate() {
        super.onCreate()
        // 取得間隔の設定を測位に反映
        appScope.launch {
            settings.settings.collect { locationHub.setInterval(it.intervalSec * 1000L) }
        }
        // 測位の方式（0.9.0〜。Google の融合測位は使えなければ端末の GPS に戻る）
        appScope.launch {
            settings.settings.map { it.positioning }.distinctUntilChanged().collect {
                locationHub.setUseFused(it == jp.kjtr.cycleroutelogger.data.Positioning.GOOGLE)
            }
        }
        // 軌跡をなめらかにする（0.9.0〜）: 表示・距離に使う。設定が変わった・0.8.0 から上書きした最初の起動なら、
        // 終えた記録の統計（距離・獲得標高）を点から計算し直す（どの点で計算したかを SharedPreferences `state` に覚える）
        TrackRepository.smoothingEnabled = settings.settings.value.trackSmoothing
        appScope.launch {
            settings.settings.map { it.trackSmoothing }.distinctUntilChanged().collect { on ->
                TrackRepository.smoothingEnabled = on
                val key = if (on) STATS_SMOOTHED else STATS_RECORDED
                val prefs = getSharedPreferences("state", MODE_PRIVATE)
                if (prefs.getString(K_STATS_KEY, null) != key) {
                    runCatching { tracks.recomputeAllFinished() }.onFailure { android.util.Log.w("CRL-App", "recompute failed: ${it.message}") }
                    prefs.edit().putString(K_STATS_KEY, key).apply()
                }
            }
        }
        // 場所の索引（0.11.0）: 0.10.2 からの上書きの最初の起動では既存の記録の分をここで作る（最初の画面の表示とぶつからないよう少し待つ）
        appScope.launch {
            kotlinx.coroutines.delay(1_500)
            geoIndex.start()
        }
        // 更新の確認の ON/OFF を帯・設定の表示に反映（OFF なら帯を消す）
        appScope.launch {
            settings.settings.map { it.updateCheck }.distinctUntilChanged().collect { updates.publish() }
        }
    }

    companion object {
        /** 終えた記録の統計をどの点・どの数え方で計算したか（なめらかにした点 / 記録した点。獲得標高は 0.9.0 の ElevationGain）。 */
        private const val K_STATS_KEY = "stats_points"
        private const val STATS_SMOOTHED = "smoothed-1"
        private const val STATS_RECORDED = "recorded-1"
    }
}
