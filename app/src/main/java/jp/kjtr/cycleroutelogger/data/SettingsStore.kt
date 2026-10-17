package jp.kjtr.cycleroutelogger.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MapKind { NORMAL, SATELLITE, HYBRID, TERRAIN }

/** 測位の方式（0.9.0〜）。DEVICE = 端末の GPS（LocationManager。既定）、GOOGLE = Google の融合測位（Play 開発者サービス。使えなければ端末の GPS）。 */
enum class Positioning { DEVICE, GOOGLE }

/** 地図の追従。 */
enum class FollowMode { NONE, NORTH_UP, HEADING_UP }

data class Settings(
    val mapKind: MapKind = MapKind.NORMAL,
    val followMode: FollowMode = FollowMode.NORTH_UP,
    /** 記録の取得間隔 [秒]（1 / 2 / 5）。 */
    val intervalSec: Int = 1,
    val autoPause: Boolean = true,
    val offRouteAlert: Boolean = true,
    /** ルート外れを知らせる距離 [m]。 */
    val offRouteThresholdM: Int = 50,
    val approachAlert: Boolean = true,
    val alertSound: Boolean = true,
    val alertVibrate: Boolean = true,
    /** ルートサイクリングで、次の経由地を自動で目的地にする。 */
    val autoNextDestination: Boolean = false,
    /** 走行画面で画面を点けたままにする。 */
    val keepScreenOn: Boolean = true,
    /** 初回の安全上の注意に同意した。 */
    val safetyAccepted: Boolean = false,
    /** 地図に出さない経由地の種類（code）。 */
    val hiddenTypes: Set<String> = emptySet(),
    /** ルートプラン画面で地図が占める割合（縦長は高さ、横長は幅）。[PLAN_MAP_RATIO_MIN]〜[PLAN_MAP_RATIO_MAX]。 */
    val planMapRatio: Float = 0.6f,
    /** 記録の詳細で地図が占める割合（0.5.0。範囲はプランと同じ）。 */
    val trackMapRatio: Float = 0.6f,
    /** 初めて記録を始めるときの電池の最適化の案内を出した。 */
    val batteryHintShown: Boolean = false,
    /** アプリ内カメラで撮った写真の EXIF に位置（GPS）を入れる。 */
    val photoExifLocation: Boolean = true,
    /** 更新の確認（GitHub の公開の版一覧を読む。OFF の間は一切通信しない。0.4.0）。 */
    val updateCheck: Boolean = true,
    /** 測位の方式（0.9.0）。 */
    val positioning: Positioning = Positioning.DEVICE,
    /** 軌跡をなめらかにする（0.9.0。地図の線・距離に使う。記録した点はそのまま）。 */
    val trackSmoothing: Boolean = true,
)

/** 走行画面で選んでいるモード（アプリを閉じても保つ）。 */
data class RideSelection(val mode: String, val planId: Long?)

/** 今向かいたい地点（アプリを閉じても保つ）。 */
data class Destination(
    val lat: Double,
    val lon: Double,
    val name: String,
    /** 経由地から選んだならその id。 */
    val waypointId: Long? = null,
    val type: String? = null,
)

/** 設定と走行中の状態の SharedPreferences 永続化。 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private val _ride = MutableStateFlow(loadRide())
    val ride: StateFlow<RideSelection?> = _ride

    private val _destination = MutableStateFlow(loadDestination())
    val destination: StateFlow<Destination?> = _destination

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString(K_MAP, s.mapKind.name)
            .putString(K_FOLLOW, s.followMode.name)
            .putInt(K_INTERVAL, s.intervalSec)
            .putBoolean(K_AUTO_PAUSE, s.autoPause)
            .putBoolean(K_OFF_ROUTE, s.offRouteAlert)
            .putInt(K_OFF_ROUTE_M, s.offRouteThresholdM)
            .putBoolean(K_APPROACH, s.approachAlert)
            .putBoolean(K_SOUND, s.alertSound)
            .putBoolean(K_VIBRATE, s.alertVibrate)
            .putBoolean(K_AUTO_NEXT, s.autoNextDestination)
            .putBoolean(K_SCREEN_ON, s.keepScreenOn)
            .putBoolean(K_SAFETY, s.safetyAccepted)
            .putStringSet(K_HIDDEN, s.hiddenTypes)
            .putFloat(K_PLAN_MAP_RATIO, s.planMapRatio)
            .putFloat(K_TRACK_MAP_RATIO, s.trackMapRatio)
            .putBoolean(K_BATTERY_HINT, s.batteryHintShown)
            .putBoolean(K_PHOTO_EXIF_LOC, s.photoExifLocation)
            .putBoolean(K_UPDATE_CHECK, s.updateCheck)
            .putString(K_POSITIONING, s.positioning.name)
            .putBoolean(K_TRACK_SMOOTHING, s.trackSmoothing)
            .apply()
    }

    fun setRide(r: RideSelection?) {
        _ride.value = r
        prefs.edit().apply {
            if (r == null) {
                remove(K_RIDE_MODE); remove(K_RIDE_PLAN)
            } else {
                putString(K_RIDE_MODE, r.mode)
                if (r.planId != null) putLong(K_RIDE_PLAN, r.planId) else remove(K_RIDE_PLAN)
            }
        }.apply()
    }

    fun setDestination(d: Destination?) {
        _destination.value = d
        prefs.edit().apply {
            if (d == null) {
                remove(K_DEST_LAT); remove(K_DEST_LON); remove(K_DEST_NAME); remove(K_DEST_WP); remove(K_DEST_TYPE)
            } else {
                putString(K_DEST_LAT, d.lat.toString())
                putString(K_DEST_LON, d.lon.toString())
                putString(K_DEST_NAME, d.name)
                if (d.waypointId != null) putLong(K_DEST_WP, d.waypointId) else remove(K_DEST_WP)
                if (d.type != null) putString(K_DEST_TYPE, d.type) else remove(K_DEST_TYPE)
            }
        }.apply()
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            mapKind = enumOr(prefs.getString(K_MAP, null), d.mapKind),
            followMode = enumOr(prefs.getString(K_FOLLOW, null), d.followMode),
            intervalSec = prefs.getInt(K_INTERVAL, d.intervalSec).takeIf { it in INTERVALS } ?: d.intervalSec,
            autoPause = prefs.getBoolean(K_AUTO_PAUSE, d.autoPause),
            offRouteAlert = prefs.getBoolean(K_OFF_ROUTE, d.offRouteAlert),
            offRouteThresholdM = prefs.getInt(K_OFF_ROUTE_M, d.offRouteThresholdM),
            approachAlert = prefs.getBoolean(K_APPROACH, d.approachAlert),
            alertSound = prefs.getBoolean(K_SOUND, d.alertSound),
            alertVibrate = prefs.getBoolean(K_VIBRATE, d.alertVibrate),
            autoNextDestination = prefs.getBoolean(K_AUTO_NEXT, d.autoNextDestination),
            keepScreenOn = prefs.getBoolean(K_SCREEN_ON, d.keepScreenOn),
            safetyAccepted = prefs.getBoolean(K_SAFETY, d.safetyAccepted),
            hiddenTypes = prefs.getStringSet(K_HIDDEN, null)?.toSet() ?: d.hiddenTypes,
            planMapRatio = prefs.getFloat(K_PLAN_MAP_RATIO, d.planMapRatio).coerceIn(PLAN_MAP_RATIO_MIN, PLAN_MAP_RATIO_MAX),
            trackMapRatio = prefs.getFloat(K_TRACK_MAP_RATIO, d.trackMapRatio).coerceIn(PLAN_MAP_RATIO_MIN, PLAN_MAP_RATIO_MAX),
            batteryHintShown = prefs.getBoolean(K_BATTERY_HINT, d.batteryHintShown),
            photoExifLocation = prefs.getBoolean(K_PHOTO_EXIF_LOC, d.photoExifLocation),
            updateCheck = prefs.getBoolean(K_UPDATE_CHECK, d.updateCheck),
            positioning = enumOr(prefs.getString(K_POSITIONING, null), d.positioning),
            trackSmoothing = prefs.getBoolean(K_TRACK_SMOOTHING, d.trackSmoothing),
        )
    }

    private fun loadRide(): RideSelection? {
        val mode = prefs.getString(K_RIDE_MODE, null) ?: return null
        val plan = if (prefs.contains(K_RIDE_PLAN)) prefs.getLong(K_RIDE_PLAN, 0) else null
        return RideSelection(mode, plan)
    }

    private fun loadDestination(): Destination? {
        val lat = prefs.getString(K_DEST_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(K_DEST_LON, null)?.toDoubleOrNull() ?: return null
        return Destination(
            lat = lat,
            lon = lon,
            name = prefs.getString(K_DEST_NAME, "") ?: "",
            waypointId = if (prefs.contains(K_DEST_WP)) prefs.getLong(K_DEST_WP, 0) else null,
            type = prefs.getString(K_DEST_TYPE, null),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: def

    companion object {
        val INTERVALS = listOf(1, 2, 5)
        val OFF_ROUTE_CHOICES = listOf(30, 50, 100, 200)
        const val PLAN_MAP_RATIO_MIN = 0.3f
        const val PLAN_MAP_RATIO_MAX = 0.85f

        private const val K_MAP = "map_kind"
        private const val K_FOLLOW = "follow_mode"
        private const val K_INTERVAL = "interval_sec"
        private const val K_AUTO_PAUSE = "auto_pause"
        private const val K_OFF_ROUTE = "off_route_alert"
        private const val K_OFF_ROUTE_M = "off_route_m"
        private const val K_APPROACH = "approach_alert"
        private const val K_SOUND = "alert_sound"
        private const val K_VIBRATE = "alert_vibrate"
        private const val K_AUTO_NEXT = "auto_next_destination"
        private const val K_SCREEN_ON = "keep_screen_on"
        private const val K_SAFETY = "safety_accepted"
        private const val K_HIDDEN = "hidden_types"
        private const val K_PLAN_MAP_RATIO = "plan_map_ratio"
        private const val K_TRACK_MAP_RATIO = "track_map_ratio"
        private const val K_BATTERY_HINT = "battery_hint_shown"
        private const val K_PHOTO_EXIF_LOC = "photo_exif_location"
        private const val K_UPDATE_CHECK = "update_check"
        private const val K_POSITIONING = "positioning"
        private const val K_TRACK_SMOOTHING = "track_smoothing"
        private const val K_RIDE_MODE = "ride_mode"
        private const val K_RIDE_PLAN = "ride_plan"
        private const val K_DEST_LAT = "dest_lat"
        private const val K_DEST_LON = "dest_lon"
        private const val K_DEST_NAME = "dest_name"
        private const val K_DEST_WP = "dest_wp"
        private const val K_DEST_TYPE = "dest_type"
    }
}
