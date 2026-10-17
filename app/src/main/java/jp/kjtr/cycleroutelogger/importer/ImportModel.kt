package jp.kjtr.cycleroutelogger.importer

/**
 * ファイルからの記録の取り込み（0.10.0。DESIGN.md §4.10）で共通に使う型。
 * パーサ（[FitParser] / [TcxParser] / [KmlParser] / GPX）は時刻つきの点を [PointSink] に流し、[RunCollector] が
 * 点の置き場（[PointStore]、端末では一時ファイル）に書いて「続いた線」（[RunInfo]）にまとめる。日ごとの分け方は [DayPlanner]。
 */

/** 元の形式。[rank] は同じ時間に複数のファイルがあるときの優先（小さいほど優先。FIT は端末の速度・気圧高度を持つことが多い）。 */
enum class ActivityFormat(val code: String, val label: String, val rank: Int) {
    FIT("fit", "FIT", 0),
    TCX("tcx", "TCX", 1),
    GPX("gpx", "GPX", 2),
    KML("kml", "KML", 3),
    ;

    companion object {
        fun fromCode(code: String): ActivityFormat? = entries.firstOrNull { it.code == code }

        /** 記録の印（`TrackEntity.importFormat`）の文字列: 優先の順に code をカンマでつなぐ（"fit,gpx"）。 */
        fun encode(formats: Collection<ActivityFormat>): String = formats.toSortedSet(compareBy { it.rank }).joinToString(",") { it.code }

        fun decode(s: String?): List<ActivityFormat> = s?.split(',')?.mapNotNull { fromCode(it.trim()) }.orEmpty()
    }
}

/**
 * 種目（ファイルに書いてあれば）。自転車以外（[OTHER]）とバーチャル（[VIRTUAL]）は既定で取り込まない（確認画面で切り替え。[excludedByDefault]）。
 * 0.10.1〜 Strava の書き出しは `activities.csv` の Activity Type をファイルの種目より優先する（[ofStrava]、[ActivityCatalog]）。
 */
enum class Sport { UNKNOWN, CYCLING, OTHER,
    /** Zwift などのバーチャルライド（位置が無いか、仮想の世界の座標）。地図・開拓したマスを汚すので既定で外す。 */
    VIRTUAL;

    val excludedByDefault: Boolean get() = this == OTHER || this == VIRTUAL

    companion object {
        /** GPX の trk/type・TCX の Activity@Sport・名前などの文字列から。分からなければ [UNKNOWN]。 */
        fun of(text: String?): Sport {
            val s = text?.trim()?.lowercase() ?: return UNKNOWN
            if (s.isEmpty()) return UNKNOWN
            return when {
                VIRTUAL_WORDS.any { it in s } -> VIRTUAL
                CYCLING_WORDS.any { it in s } -> CYCLING
                OTHER_WORDS.any { it in s } -> OTHER
                // Strava の GPX は数字のことがある（1 = Ride、9 = Run、10 = Walk。実物では未確認）
                s == "1" -> CYCLING
                s == "9" || s == "10" -> OTHER
                else -> UNKNOWN
            }
        }

        /**
         * Strava の Activity Type（activities.csv。英語 "Ride" / "E-Bike Ride" / "Gravel Ride" / "Mountain Bike Ride" / "Virtual Ride"、
         * 日本語の書き出しなら「ライド」「バーチャルライド」など）。自転車の種類なら [CYCLING]、バーチャルは [VIRTUAL]、
         * それ以外（Run・Walk・Canoe・Alpine Ski…）は [OTHER]。空なら [UNKNOWN]。
         */
        fun ofStrava(type: String?): Sport {
            val s = type?.trim()?.lowercase() ?: return UNKNOWN
            if (s.isEmpty()) return UNKNOWN
            return when {
                VIRTUAL_WORDS.any { it in s } -> VIRTUAL
                CYCLING_WORDS.any { it in s } || STRAVA_CYCLING_WORDS.any { it in s } -> CYCLING
                else -> OTHER
            }
        }

        /**
         * FIT の sport（session / sport メッセージ）。2 = cycling、1 = running、11 = walking など。
         * [subSport] 58 = virtual_activity（Zwift など）はバーチャル。
         */
        fun ofFit(code: Int, subSport: Int = -1): Sport = when {
            subSport == 58 -> VIRTUAL
            code == 2 -> CYCLING
            code == 0 || code == 0xFF -> UNKNOWN // generic・無効
            else -> OTHER
        }

        private val VIRTUAL_WORDS = listOf("virtual", "バーチャル")
        private val CYCLING_WORDS = listOf("cycl", "bik", "ride", "自転車", "サイクリング")
        // Strava の自転車の種類（ハンドサイクル・ベロモービル。日本語の書き出しの「ライド」「バイク」）
        private val STRAVA_CYCLING_WORDS = listOf("velomobile", "ライド", "バイク", "サイクル", "ベロモービル")
        private val OTHER_WORDS = listOf("run", "walk", "hik", "swim", "ski", "row", "paddl", "ラン", "ウォーク", "歩")
    }
}

/** パーサから点を受け取る。 */
interface PointSink {
    /** 新しい続いた線を始める（GPX の trkseg・KML の gx:Track / LineString・TCX の Track）。 */
    fun beginRun()

    /** 時刻つきの点 1 つ（[timeMs] は UTC ms、[speedMps] は端末・センサーの速度があれば）。 */
    fun point(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?)

    /** ファイルに書いてある種目（分かったとき。ファイルの全部の線に効く）。 */
    fun sport(s: Sport) {}
}

/** 1 つのファイル（zip の中の 1 エントリ）の読み取り結果。 */
data class FileReport(
    /** zip の中のパス（入れ子は「a.zip/b.gpx」）。 */
    val path: String,
    val format: ActivityFormat?,
    val status: FileStatus,
    /** 取り込みの材料になった点の数（重複を除く前）。 */
    val points: Int = 0,
    /** 補足（「CRC 不一致」「位置の無い記録 120」など）。 */
    val detail: String? = null,
    /** 書き出しの目録（Strava の activities.csv）の名前（0.10.1）。 */
    val title: String? = null,
    /** 書き出しの目録の種目（Activity Type。表示用）。 */
    val activityType: String? = null,
    /**
     * 取り込みの印の元（0.10.1）: "sha:<内容の SHA-256 の先頭 16 バイト>"・"fit:<製造元>/<シリアル>/<作成時刻>"（FIT の file_id）・"strava:<Activity ID>"。
     * 活動の始まりの時刻をつけて [ActivityKeys] にする。
     */
    val keys: List<String> = emptyList(),
)

enum class FileStatus {
    /** 時刻つきの点が読めた。 */
    OK,
    /** 読めた点はあるが途中で壊れていた（読めたところまで使う）。 */
    PARTIAL,
    /** GPX の rte / wpt だけ（時刻が無いので記録にしない）。 */
    ROUTE_ONLY,
    /** 線はあるが時刻が無い（KML の LineString だけ・時刻の無い GPX など）。 */
    NO_TIME,
    /** 時刻はあるが位置が無い（屋内・センサーだけの FIT など）。 */
    NO_POSITION,
    /** 形式が違う・壊れている。 */
    BROKEN,
    /** 大きすぎる（展開後 64 MB 超）。 */
    TOO_LARGE,
}

/**
 * 続いた線 1 本（1 つのファイルの中で、時刻が戻らず 10 分以上あかない点の並び）。点は [PointStore] の [first] から [count] 個。
 */
data class RunInfo(
    val id: Int,
    val file: Int,
    val format: ActivityFormat,
    val sport: Sport,
    val first: Long,
    val count: Int,
    val start: Long,
    val end: Long,
)

/** 点の置き場（取り込みの間だけ。端末では一時ファイル [FileSpool]、テストでは [MemoryStore]）。 */
interface PointStore : AutoCloseable {
    val size: Long
    fun append(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?)
    /** 書き終えた点を [first] から [count] 個読む（書いている途中でも、書いた分は読める）。 */
    fun read(first: Long, count: Int): PointBlock
    override fun close() {}
}

/** 読んだ点（高度・速度の無い点は NaN）。 */
class PointBlock(val time: LongArray, val lat: DoubleArray, val lon: DoubleArray, val ele: FloatArray, val speed: FloatArray) {
    val size: Int get() = time.size
    fun eleAt(i: Int): Double? = ele[i].takeIf { !it.isNaN() }?.toDouble()
    fun speedAt(i: Int): Float? = speed[i].takeIf { !it.isNaN() }
}

/** メモリの置き場（単体テスト・小さな入力用）。 */
class MemoryStore : PointStore {
    private val t = ArrayList<Long>()
    private val la = ArrayList<Double>()
    private val lo = ArrayList<Double>()
    private val e = ArrayList<Float>()
    private val s = ArrayList<Float>()
    override val size: Long get() = t.size.toLong()

    override fun append(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?) {
        t.add(timeMs); la.add(lat); lo.add(lon); e.add(ele?.toFloat() ?: Float.NaN); s.add(speedMps ?: Float.NaN)
    }

    override fun read(first: Long, count: Int): PointBlock {
        val f = first.toInt()
        return PointBlock(
            LongArray(count) { t[f + it] }, DoubleArray(count) { la[f + it] }, DoubleArray(count) { lo[f + it] },
            FloatArray(count) { e[f + it] }, FloatArray(count) { s[f + it] },
        )
    }
}
