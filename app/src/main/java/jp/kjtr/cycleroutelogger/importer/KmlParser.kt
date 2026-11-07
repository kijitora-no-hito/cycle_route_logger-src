package jp.kjtr.cycleroutelogger.importer

import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.gpx.GpxTime
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * KML のストリーミング解析（KMZ は zip の中の .kml として [ActivityReader] が渡す）。
 *
 * - **gx:Track**（`when` と `gx:coord`「経度 緯度 高度」の組。gx:MultiTrack の中も）: 1 本ずつ時刻つきの線にする。
 * - **LineString**（`coordinates`「経度,緯度[,高度]」。時刻なし）: その Placemark か、外側の Folder / Document の **TimeSpan**（begin・end）があれば、
 *   begin〜end を**道のりに比例して**割り振る（一定の速さで走ったとみなす。道のりが 0 なら点の順に等間隔）。
 *   TimeSpan が無ければ（TimeStamp だけ・何も無い）日付も走った時間も決められないので取り込まない（[Result.untimedLines] に数える）。
 * - Point・Polygon は使わない。名前空間・接頭辞は見ない。
 */
object KmlParser {
    data class Result(val points: Int, val tracks: Int, val interpolatedLines: Int, val untimedLines: Int, val partial: Boolean)

    class NotKml : Exception("not KML")

    private class Span(val depth: Int, var begin: Long? = null, var end: Long? = null)

    fun parse(parser: XmlPullParser, input: InputStream, sink: PointSink, check: () -> Unit = {}): Result {
        var points = 0
        var tracks = 0
        var interpolated = 0
        var untimed = 0
        var partial = false
        var sawRoot = false
        val stack = ArrayList<String>()
        val text = StringBuilder()
        // TimeSpan は入れ物（Document / Folder / Placemark）の深さに結びつける
        val spans = ArrayList<Span>()
        var spanBegin: Long? = null
        var spanEnd: Long? = null
        // gx:Track の組み立て
        var inTrack = false
        val whens = ArrayList<Long?>()
        val coords = ArrayList<DoubleArray>()
        // Placemark の中の LineString（TimeSpan はあとから来ることもあるので、Placemark の終わりで決める）
        val lines = ArrayList<List<DoubleArray>>()
        var placemarkDepth = -1

        fun emitTrack() {
            val n = minOf(whens.size, coords.size)
            sink.beginRun()
            var any = false
            for (i in 0 until n) {
                val t = whens[i] ?: continue
                val c = coords[i]
                sink.point(t, c[1], c[0], c.getOrNull(2), null)
                points++
                any = true
                if (points % 4096 == 0) check()
            }
            if (any) tracks++
            whens.clear()
            coords.clear()
        }

        fun spanFor(): Pair<Long, Long>? {
            for (i in spans.indices.reversed()) {
                val s = spans[i]
                val b = s.begin
                val e = s.end
                if (b != null && e != null && e > b) return b to e
            }
            return null
        }

        fun emitLines() {
            if (lines.isEmpty()) return
            val span = spanFor()
            if (span == null) {
                untimed += lines.size
                lines.clear()
                return
            }
            // 全部の線を通した道のりで begin〜end を割り振る
            val all = lines.flatten()
            val cum = DoubleArray(all.size)
            for (i in 1 until all.size) cum[i] = cum[i - 1] + Geo.distanceM(all[i - 1][1], all[i - 1][0], all[i][1], all[i][0])
            val total = cum.lastOrNull() ?: 0.0
            val (b, e) = span
            var k = 0
            for (line in lines) {
                sink.beginRun()
                for (c in line) {
                    val f = if (total > 0) cum[k] / total else if (all.size > 1) k.toDouble() / (all.size - 1) else 0.0
                    sink.point(b + Math.round((e - b) * f), c[1], c[0], c.getOrNull(2), null)
                    points++
                    k++
                }
            }
            interpolated += lines.size
            lines.clear()
        }

        try {
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            parser.setInput(input, null)
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val name = local(parser.name)
                        if (stack.isEmpty()) {
                            if (name != "kml") throw NotKml()
                            sawRoot = true
                        }
                        stack.add(name)
                        text.setLength(0)
                        when (name) {
                            "Document", "Folder", "Placemark" -> {
                                spans.add(Span(stack.size))
                                if (name == "Placemark") placemarkDepth = stack.size
                            }
                            "TimeSpan" -> { spanBegin = null; spanEnd = null }
                            "Track" -> { inTrack = true; whens.clear(); coords.clear() }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> parser.text?.let { text.append(it) }
                    XmlPullParser.END_TAG -> {
                        val depth = stack.size
                        val name = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else local(parser.name)
                        val parent = stack.lastOrNull()
                        val v = text.toString().trim()
                        when (name) {
                            "when" -> if (inTrack && parent == "Track") whens.add(GpxTime.parse(v))
                            "coord" -> if (inTrack) parseGxCoord(v)?.let { coords.add(it) }
                            "Track" -> { if (inTrack) emitTrack(); inTrack = false }
                            "begin" -> if (parent == "TimeSpan") spanBegin = GpxTime.parse(v)
                            "end" -> if (parent == "TimeSpan") spanEnd = GpxTime.parse(v)
                            "TimeSpan" -> spans.lastOrNull()?.let { it.begin = spanBegin; it.end = spanEnd }
                            "coordinates" -> if (parent == "LineString") {
                                val line = parseCoordinates(v)
                                if (line.size >= 2) {
                                    if (placemarkDepth > 0) lines.add(line) else { lines.add(line); emitLines() }
                                }
                            }
                            "Placemark" -> { emitLines(); placemarkDepth = -1 }
                        }
                        if (name == "Document" || name == "Folder" || name == "Placemark") {
                            while (spans.isNotEmpty() && spans.last().depth >= depth) spans.removeAt(spans.size - 1)
                        }
                        text.setLength(0)
                    }
                }
                ev = parser.next()
            }
        } catch (e: NotKml) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!sawRoot) throw NotKml()
            partial = true
        }
        return Result(points, tracks, interpolated, untimed, partial)
    }

    /** gx:coord「経度 緯度 [高度]」（空白区切り）。 */
    private fun parseGxCoord(s: String): DoubleArray? {
        val parts = s.split(WS).filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val lon = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull()
        return if (alt != null) doubleArrayOf(lon, lat, alt) else doubleArrayOf(lon, lat)
    }

    /** coordinates「経度,緯度[,高度] 経度,緯度…」。 */
    private fun parseCoordinates(s: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (tuple in s.split(WS)) {
            if (tuple.isEmpty()) continue
            val p = tuple.split(',')
            if (p.size < 2) continue
            val lon = p[0].toDoubleOrNull() ?: continue
            val lat = p[1].toDoubleOrNull() ?: continue
            val alt = p.getOrNull(2)?.toDoubleOrNull()
            out.add(if (alt != null) doubleArrayOf(lon, lat, alt) else doubleArrayOf(lon, lat))
        }
        return out
    }

    private val WS = Regex("\\s+")

    private fun local(n: String?): String {
        val s = n ?: return ""
        val i = s.indexOf(':')
        return if (i >= 0) s.substring(i + 1) else s
    }
}
