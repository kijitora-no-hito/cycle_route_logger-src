package jp.kjtr.cycleroutelogger.draw

import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.hypot

/** 局所平面（東 x・北 y、m）のベクトル。 */
@Serializable
data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec(x * k, y * k)
    operator fun unaryMinus() = Vec(-x, -y)
    val length: Double get() = hypot(x, y)
    fun unit(): Vec = length.let { if (it < 1e-12) Vec(0.0, 0.0) else Vec(x / it, y / it) }

    companion object {
        val ZERO = Vec(0.0, 0.0)
        fun lerp(a: Vec, b: Vec, t: Double) = Vec(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}

/**
 * アンカー（点）の種類。
 * - [CORNER]: 角。前後のハンドルは別々（無ければ直線）。
 * - [SMOOTH]: なめらか。前後のハンドルが一直線（長さは別）。
 * - [SYMMETRIC]: 対称。一直線かつ同じ長さ。
 */
@Serializable
enum class AnchorKind { CORNER, SMOOTH, SYMMETRIC }

/**
 * 描いたルートの点。位置は緯度経度、ハンドルはアンカーからの相対（東・北の m。null = ハンドル無し）。
 * 前の区間側が [inH]、次の区間側が [outH]。
 */
@Serializable
data class Anchor(
    val lat: Double,
    val lon: Double,
    val inH: Vec? = null,
    val outH: Vec? = null,
    val kind: AnchorKind = AnchorKind.CORNER,
) {
    val latLon: LatLon get() = LatLon(lat, lon)
}

/**
 * 地図に描いたルートの「編集できる形」（Room の route.editShape に JSON で持つ）。
 * [methods] は区間ごとの引き方（[SegmentRouter.id]。足りなければ [StraightRouter]）。今は直線（とベジエ）だけ。
 * 道に沿う方式を足すときは、区間ごとに別の id を入れる（GPX・route_point は引いた結果の点なので影響しない）。
 */
@Serializable
data class DrawnShape(
    val version: Int = 1,
    val anchors: List<Anchor> = emptyList(),
    val methods: List<String> = emptyList(),
) {
    val segmentCount: Int get() = (anchors.size - 1).coerceAtLeast(0)
    fun method(i: Int): String = methods.getOrNull(i) ?: StraightRouter.ID

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun fromJson(s: String?): DrawnShape? = s?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** 緯度経度 ↔ 局所平面（原点 [origin] の正距円筒。数十 km の範囲なら十分）。 */
class Enu(val origin: LatLon) {
    private val kx = Geo.metersPerDegLon(origin.lat)
    private val ky = Geo.METERS_PER_DEG_LAT

    fun toVec(p: LatLon) = Vec((p.lon - origin.lon) * kx, (p.lat - origin.lat) * ky)
    fun toVec(a: Anchor) = Vec((a.lon - origin.lon) * kx, (a.lat - origin.lat) * ky)
    fun toLatLon(v: Vec) = LatLon(origin.lat + v.y / ky, origin.lon + v.x / kx)
}
