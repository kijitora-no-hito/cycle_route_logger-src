package jp.kjtr.cycleroutelogger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** ルートプラン。GPX ルート（[RouteEntity]）を何本でも持ち、経由地（[WaypointEntity]）を持つ。 */
@Entity(tableName = "plan")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val memo: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

/** プランの中の GPX ルート 1 本（trk か rte 1 本分）。点は [RoutePointEntity]。 */
@Entity(
    tableName = "route",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId")],
)
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    /** プランの中の並び順（小さい順。1 日目・2 日目など）。 */
    val sortOrder: Int,
    val name: String,
    /** 線の色（ARGB）。 */
    val color: Int,
    val visible: Boolean = true,
    val createdAt: Long,
    val distanceM: Double,
    val ascentM: Double,
    val pointCount: Int,
    val hasElevation: Boolean,
    /** 記録から作ったルートなら、その記録の id（記録を消しても残る）。 */
    val sourceTrackId: Long? = null,
    /** URL（QR を含む）から取り込んだなら、その URL（利用者が渡したもの。［URL から再読み込み］に使う）。版 2 から。 */
    val sourceUrl: String? = null,
    /**
     * 地図に描いたルートなら、編集できる形（アンカーとベジエのハンドル、区間の引き方）の JSON（[jp.kjtr.cycleroutelogger.draw.DrawnShape]）。
     * 点（route_point）はこれをサンプリングしたもの。版 3 から。
     */
    val editShape: String? = null,
)

@Entity(
    tableName = "route_point",
    primaryKeys = ["routeId", "seq"],
    foreignKeys = [ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE)],
)
data class RoutePointEntity(
    val routeId: Long,
    val seq: Int,
    /** trkseg の番号（0 から）。区間の間は線を引かない。 */
    val segment: Int,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
)

/** 経由地。種類は [jp.kjtr.cycleroutelogger.plan.WaypointType.code]。 */
@Entity(
    tableName = "waypoint",
    foreignKeys = [ForeignKey(entity = PlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("planId")],
)
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    val type: String,
    val memo: String = "",
    /** 予定時刻（任意、UTC ms）。 */
    val plannedAt: Long? = null,
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val createdAt: Long,
    /** URL から取り込んだ経由地なら、その URL（再読み込みで置き換える対象）。版 2 から。 */
    val sourceUrl: String? = null,
)

/** 走行記録。点は [TrackPointEntity] に逐次保存する。 */
@Entity(tableName = "track")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** [STATUS_RECORDING] / [STATUS_PAUSED] / [STATUS_FINISHED]。 */
    val status: Int,
    /** [MODE_FREE] / [MODE_ROUTE]。 */
    val mode: String,
    /** ルートサイクリングで使ったプラン（プランを消しても記録は残すので外部キーにしない）。 */
    val planId: Long? = null,
    val planName: String? = null,
    val autoPause: Boolean,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val ascentM: Double = 0.0,
    val pointCount: Int = 0,
    /**
     * ファイルから取り込んだ記録なら元の形式（"fit,gpx" のように [jp.kjtr.cycleroutelogger.importer.ActivityFormat] の code をカンマで）。
     * 自分で記録したものは null。版 5（0.10.0）から。
     */
    val importFormat: String? = null,
) {
    /** ファイルから取り込んだ記録か（0.10.0〜）。 */
    val imported: Boolean get() = importFormat != null

    companion object {
        const val STATUS_RECORDING = 0
        const val STATUS_PAUSED = 1
        const val STATUS_FINISHED = 2
        const val MODE_FREE = "free"
        const val MODE_ROUTE = "route"
    }
}

@Entity(
    tableName = "track_point",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val segment: Int,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val accuracy: Float?,
    /** 端末が出した速度（再計算用）。 */
    val deviceSpeed: Float?,
    /** 平滑化した速度（色分け表示用）[m/s]。 */
    val speed: Float?,
    /** 高度の精度（verticalAccuracyMeters）[m]。版 4（0.9.0）から。獲得標高で精度の悪い高度を除くのに使う。それより前の点は null。 */
    val altAccuracy: Float? = null,
    /** 測位の出どころ（版 4 から。[PROVIDER_GPS] / [PROVIDER_NETWORK] / [PROVIDER_FUSED] / [PROVIDER_IMPORT]（0.10.0）。それより前の点・バックアップから戻した点は [PROVIDER_UNKNOWN]）。 */
    @ColumnInfo(defaultValue = "0") val provider: Int = PROVIDER_UNKNOWN,
) {
    companion object {
        const val PROVIDER_UNKNOWN = 0
        /** 端末の GPS（LocationManager の gps）。 */
        const val PROVIDER_GPS = 1
        /** ネットワーク位置（Wi‑Fi・基地局。高度の基準が違うことがある）。 */
        const val PROVIDER_NETWORK = 2
        /** Google の融合測位（FusedLocationProviderClient、provider = fused）。 */
        const val PROVIDER_FUSED = 3
        /** ファイル（GPX / FIT / TCX / KML）から取り込んだ点（0.10.0〜。測位の精度は無い）。 */
        const val PROVIDER_IMPORT = 4

        fun providerOf(name: String?): Int = when (name) {
            "gps" -> PROVIDER_GPS
            "network" -> PROVIDER_NETWORK
            "fused" -> PROVIDER_FUSED
            else -> PROVIDER_UNKNOWN
        }
    }
}

/**
 * 記録中の出来事（位置情報が OFF にされた・GPS が取れない・アプリが落ちて再開した など）。版 2 から。
 * 続いた出来事は [endedAt] に終わりの時刻を入れる（終わる前に記録を終えたら null のまま）。
 */
@Entity(
    tableName = "track_event",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class TrackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** [TYPE_LOCATION_OFF] など。 */
    val type: String,
    val startedAt: Long,
    val endedAt: Long? = null,
) {
    companion object {
        /** 端末の位置情報（GPS）が OFF にされた。 */
        const val TYPE_LOCATION_OFF = "location_off"
        /** 位置が一定時間届かない（トンネル・屋内など）。 */
        const val TYPE_NO_FIX = "no_fix"
        /** アプリ（プロセス）が落ちて、記録サービスが自動で再開した。 */
        const val TYPE_RESTARTED = "restarted"
        /** アプリが落ちて、自動では再開できなかった（次に開いたときに続けた）。 */
        const val TYPE_INTERRUPTED = "interrupted"
    }
}

/**
 * 写真（版 3）。本体は端末のフォトライブラリ（MediaStore）か、Photo Picker で選んだもの（[uri]）。アプリには参照と
 * 撮影時刻・位置・縮小版（[thumbPath]、アプリの files/thumbs/）を持つ。記録を消すと紐づけも消える（写真の本体は残る）。
 */
@Entity(
    tableName = "photo",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 撮ったときの記録（記録していなければ null）。 */
    val trackId: Long?,
    val uri: String,
    /** 撮影時刻（UTC ms）。 */
    val takenAt: Long,
    val lat: Double?,
    val lon: Double?,
    /** 位置の出どころ: [LOC_GPS]（その時の測位）/ [LOC_EXIF] / [LOC_TRACK]（軌跡から時刻で補間）/ null（不明）。 */
    val locationSource: String?,
    val thumbPath: String?,
    /** アプリ内カメラで撮った（ライブラリから消せる）なら true。取り込んだものは false。 */
    val ownCapture: Boolean,
    val createdAt: Long,
) {
    companion object {
        const val LOC_GPS = "gps"
        const val LOC_EXIF = "exif"
        const val LOC_TRACK = "track"
    }
}

/**
 * 取り込みの印（版 6、0.10.1）: 取り込んで保存した活動の [key]（[jp.kjtr.cycleroutelogger.importer.ActivityKeys]。ファイルの内容の SHA-256・FIT の file_id・
 * Strava の Activity ID ＋ 始まりの時刻）。同じものをもう一度取り込むと黙って外す。記録を消すと一緒に消える（その走行はまた取り込める）。
 */
@Entity(
    tableName = "import_source",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId")],
)
data class ImportSourceEntity(
    @PrimaryKey val key: String,
    val trackId: Long,
    val importedAt: Long,
)

/**
 * 記録の場所の索引（版 7、0.11.0。一覧の場所の絞り込み。[jp.kjtr.cycleroutelogger.stats.TrackGeo]）。記録した全点の外接矩形・始点・終点と、
 * 区間ごとに 10 m で間引いた線（[line] = [jp.kjtr.cycleroutelogger.stats.TrackGeoCodec]）。記録の点から作るだけの写しなので、無ければ・古ければ作り直す
 * （[version] が [jp.kjtr.cycleroutelogger.stats.TrackGeo.VERSION] と違う、[sourcePoints] が track.pointCount と違う）。記録を消すと一緒に消える。
 */
@Entity(
    tableName = "track_geo",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
)
class TrackGeoEntity(
    @PrimaryKey val trackId: Long,
    val version: Int,
    val sourcePoints: Int,
    val south: Double?,
    val west: Double?,
    val north: Double?,
    val east: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val line: ByteArray,
)

/** 索引が新しいかを見るだけの軽い行（線は読まない）。 */
data class TrackGeoMeta(val trackId: Long, val version: Int, val sourcePoints: Int)

/** 記録ごとの写真の数。 */
data class TrackPhotoCount(val trackId: Long, val n: Int)

/** 索引を作るのに読む点（区間と位置だけ）。 */
data class PointLL(val segment: Int, val lat: Double, val lon: Double)

/** 一覧用（プラン + 集計）。 */
data class PlanSummary(
    val id: Long,
    val name: String,
    val memo: String,
    val createdAt: Long,
    val updatedAt: Long,
    val routeCount: Int,
    val waypointCount: Int,
    val distanceM: Double,
    val ascentM: Double,
)
