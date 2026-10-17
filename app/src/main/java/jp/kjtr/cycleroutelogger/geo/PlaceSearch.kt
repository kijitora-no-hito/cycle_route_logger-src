package jp.kjtr.cycleroutelogger.geo

import jp.kjtr.cycleroutelogger.net.RemoteContent
import jp.kjtr.cycleroutelogger.plan.WaypointType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 場所名で探した 1 件。[bounds] は場所の広がり（市・公園など。分からなければ null）。
 * [category] / [type] は検索サービスの分類（Nominatim なら `category` と `type`。経由地の種類の推定に使うだけ）。
 */
data class PlaceResult(
    val name: String,
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val bounds: GeoBounds? = null,
    val category: String? = null,
    val type: String? = null,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

/** 検索に失敗した理由（メッセージは画面で決める）。 */
class PlaceSearchException(val reason: Reason, val httpCode: Int = 0, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** 端末がネットにつながっていない（送らずに止めた）・名前解決できない。 */
        OFFLINE,
        TIMEOUT,
        /** 429 / 403（混雑・利用の制限）。 */
        RATE_LIMITED,
        NETWORK,
        HTTP_ERROR,
        /** 応答が読めない（JSON の配列でない等）。 */
        BAD_RESPONSE,
    }
}

/**
 * 場所名の検索（ジオコーディング）の差し替え口。今は [NominatimSearch] だけ。
 * Google Places などに替えるときは、これを実装して `CycleApp.placeSearch` を差し替える（画面は `query → List<PlaceResult>` しか見ない）。
 * 呼ぶのは利用者が検索ボタン / IME の検索キーを押したときだけ（入力中の自動補完には使わない）。
 */
interface PlaceSearch {
    /** 検索結果を出す画面に必ず添える出典。 */
    val attribution: String

    /** [near] は今の地図の表示範囲（近い結果を優先するだけで、範囲の外も返る）。失敗は [PlaceSearchException]。 */
    suspend fun search(query: String, near: GeoBounds? = null): List<PlaceResult>
}

/** 1 回の GET の結果。 */
class HttpText(val code: Int, val body: String)

/**
 * 前の呼び出しが終わってから [minIntervalMs] あけて次を通す（同時には 1 つだけ）。
 * Nominatim の「1 秒に 1 回まで」を守るため。時計と待ち方は差し替えられる（単体テスト用）。
 */
class RateGate(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastEndMs: Long? = null

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        lastEndMs?.let { last ->
            val wait = last + minIntervalMs - nowMs()
            if (wait > 0) sleep(wait)
        }
        try {
            block()
        } finally {
            lastEndMs = nowMs()
        }
    }
}

/**
 * OSM Nominatim（`nominatim.openstreetmap.org`、API キー不要）で場所名から地点を探す。
 * 利用ポリシー（https://operations.osmfoundation.org/policies/nominatim/）に合わせて:
 * - アプリを識別できる User-Agent（[userAgent] = `CycleRouteLogger/<版> (jp.kjtr.cycleroutelogger)`）。
 * - 1 秒に 1 回まで（[RateGate]。前の検索が終わってから 1 秒あける）。
 * - 同じ検索（語 + 丸めた表示範囲）の結果はメモリに [CACHE_SIZE] 件まで覚えて、繰り返し送らない（プロセスが続く間だけ。保存しない）。
 * - 自動補完には使わない（呼ぶ側の約束）。
 * 通信は HttpURLConnection（https・GET 1 回・接続 10 秒 / 読み込み 15 秒・1 MB まで）。[online] が false なら送らない。
 */
class NominatimSearch(
    private val userAgent: String,
    private val online: () -> Boolean = { true },
    private val http: (url: String, userAgent: String) -> HttpText = ::httpGet,
    private val gate: RateGate = RateGate(MIN_INTERVAL_MS),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaceSearch {
    override val attribution: String = ATTRIBUTION

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, List<PlaceResult>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PlaceResult>>?): Boolean = size > CACHE_SIZE
    }

    /** 実際に送った回数（確認用）。 */
    @Volatile var requestCount: Int = 0
        private set

    override suspend fun search(query: String, near: GeoBounds?): List<PlaceResult> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val url = buildUrl(q, near)
        cached(url)?.let { return it }
        if (!online()) throw PlaceSearchException(PlaceSearchException.Reason.OFFLINE)
        return gate.run {
            // 待っている間に同じ検索が終わっていれば、それを使う
            cached(url) ?: fetch(url).also { synchronized(cacheLock) { cache[url] = it } }
        }
    }

    private fun cached(url: String): List<PlaceResult>? = synchronized(cacheLock) { cache[url] }

    private suspend fun fetch(url: String): List<PlaceResult> {
        val res = try {
            requestCount++
            withContext(io) { http(url, userAgent) }
        } catch (e: PlaceSearchException) {
            throw e
        } catch (e: UnknownHostException) {
            throw PlaceSearchException(PlaceSearchException.Reason.OFFLINE, cause = e)
        } catch (e: SocketTimeoutException) {
            throw PlaceSearchException(PlaceSearchException.Reason.TIMEOUT, cause = e)
        } catch (e: InterruptedIOException) {
            throw PlaceSearchException(PlaceSearchException.Reason.TIMEOUT, cause = e)
        } catch (e: IOException) {
            throw PlaceSearchException(PlaceSearchException.Reason.NETWORK, cause = e)
        }
        when {
            res.code == 429 || res.code == 403 -> throw PlaceSearchException(PlaceSearchException.Reason.RATE_LIMITED, res.code)
            res.code !in 200..299 -> throw PlaceSearchException(PlaceSearchException.Reason.HTTP_ERROR, res.code)
        }
        return parse(res.body) ?: throw PlaceSearchException(PlaceSearchException.Reason.BAD_RESPONSE)
    }

    companion object {
        const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
        const val LIMIT = 10
        const val MIN_INTERVAL_MS = 1_000L
        const val CACHE_SIZE = 50

        /** 検索結果を出す画面に添える出典（Nominatim のデータは OpenStreetMap 由来で ODbL。地図が Google でも別に要る）。 */
        const val ATTRIBUTION = "場所検索: Nominatim / © OpenStreetMap contributors"

        fun userAgent(versionName: String): String = "CycleRouteLogger/$versionName (jp.kjtr.cycleroutelogger)"

        private val json = Json { ignoreUnknownKeys = true }

        /** 前後の空白を除き、続く空白（全角を含む）を 1 つにする。 */
        fun normalize(query: String): String = query.replace('　', ' ').trim().replace(Regex("\\s+"), " ")

        /**
         * 検索の URL。`format=jsonv2`・`limit=10`・`accept-language=ja`。[near] があれば `viewbox`（西,北,東,南。0.1 度単位で外側に丸める）と
         * `bounded=0`（範囲の外も返す）。日付変更線をまたぐ・おかしな範囲は付けない。
         */
        fun buildUrl(query: String, near: GeoBounds?): String {
            val q = URLEncoder.encode(normalize(query), "UTF-8").replace("+", "%20")
            val sb = StringBuilder(ENDPOINT)
                .append("?q=").append(q)
                .append("&format=jsonv2&limit=").append(LIMIT)
                .append("&accept-language=ja")
            viewbox(near)?.let { sb.append("&viewbox=").append(it).append("&bounded=0") }
            return sb.toString()
        }

        /** `viewbox` の値（西,北,東,南）。使えない範囲なら null。 */
        fun viewbox(b: GeoBounds?): String? {
            b ?: return null
            if (listOf(b.south, b.west, b.north, b.east).any { it.isNaN() || it.isInfinite() }) return null
            if (b.west > b.east || b.south > b.north) return null
            val w = (floor(b.west * 10) / 10).coerceIn(-180.0, 180.0)
            val e = (ceil(b.east * 10) / 10).coerceIn(-180.0, 180.0)
            val s = (floor(b.south * 10) / 10).coerceIn(-90.0, 90.0)
            val n = (ceil(b.north * 10) / 10).coerceIn(-90.0, 90.0)
            if (e - w >= 360.0) return null
            // + 0.0 で -0.0 を 0.0 に
            return String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", w + 0.0, n + 0.0, e + 0.0, s + 0.0)
        }

        /**
         * Nominatim（jsonv2）の応答を読む。lat/lon は文字列。name が空なら display_name の先頭（カンマの前）。
         * 位置の読めない要素は飛ばす。配列でなければ null。
         */
        fun parse(body: String): List<PlaceResult>? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return null
            return root.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = o.str("lat")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
                val lon = o.str("lon")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
                val display = o.str("display_name").orEmpty().trim()
                val name = o.str("name").orEmpty().trim().ifEmpty { display.substringBefore(",").trim() }
                // boundingbox = [南, 北, 西, 東]（文字列）
                val bb = (o["boundingbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                val bounds = if (bb != null && bb.size == 4 && bb[0] <= bb[1] && bb[2] <= bb[3]) GeoBounds(bb[0], bb[2], bb[1], bb[3]) else null
                PlaceResult(
                    name = name.ifEmpty { display },
                    displayName = display,
                    lat = lat,
                    lon = lon,
                    bounds = bounds,
                    category = o.str("category") ?: o.str("class"),
                    type = o.str("type"),
                )
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

        /**
         * 一覧に出す住所の要約。display_name から名前・郵便番号・番地を除き、日本なら国名を除いて大きい方から（「東京都 千代田区 丸の内」）、
         * それ以外はカンマ区切りのまま 4 つまで。
         */
        fun addressSummary(r: PlaceResult): String {
            val parts = r.displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (parts.firstOrNull() == r.name) parts.removeAt(0)
            // 郵便番号と番地だけの要素（「1」「2-3」）は要約に要らない
            parts.removeAll { POSTCODE.matches(it) || it.all { c -> c.isDigit() || c == '-' } }
            val japan = parts.lastOrNull() == "日本"
            if (japan) parts.removeAt(parts.size - 1)
            parts.removeAll { it == r.name }
            return if (japan) parts.asReversed().take(4).joinToString(" ") else parts.take(4).joinToString(", ")
        }

        private val POSTCODE = Regex("[0-9０-９]{3}[-－]?[0-9０-９]{4}|[0-9]{4,6}")

        /** 分類があてにできるもの（自然物・地名・境界の type は経由地の種類にしない。例: natural/water = 湖）。 */
        private val POI_CATEGORIES = setOf("amenity", "shop", "tourism", "railway", "leisure", "public_transport", "highway", "building")

        /** 経由地の種類の推定（分類と名前から。分からなければ OTHER）。 */
        fun guessType(r: PlaceResult): WaypointType =
            WaypointType.infer(type = r.type?.takeIf { r.category in POI_CATEGORIES }?.replace('_', ' '), sym = null, name = r.name)

        /** HttpURLConnection の GET 1 回（IO スレッドで呼ぶ）。https のみ。 */
        fun httpGet(url: String, userAgent: String): HttpText {
            require(url.startsWith("https://"))
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.useCaches = false
                conn.instanceFollowRedirects = false
                // Nominatim の利用ポリシーでアプリを識別できる User-Agent が必須
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code !in 200..299) return HttpText(code, "")
                val bytes = conn.inputStream.use { RemoteContent.readCapped(it, MAX_BYTES) }
                return HttpText(code, String(bytes, Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }

        private const val MAX_BYTES = 1L * 1024 * 1024
    }
}
