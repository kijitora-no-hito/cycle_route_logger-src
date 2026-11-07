package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

/** テスト用: 基準点から北へ [northM] m・東へ [eastM] m の地点（局所の平面近似）。 */
fun offset(base: LatLon, northM: Double, eastM: Double): LatLon =
    LatLon(base.lat + northM / Geo.METERS_PER_DEG_LAT, base.lon + eastM / Geo.metersPerDegLon(base.lat))

val TOKYO = LatLon(35.681236, 139.767125)

fun parseGpx(xml: String, parser: GpxParser = GpxParser()): GpxData =
    parser.parse(KXmlParser(), ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
