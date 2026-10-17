package jp.kjtr.cycleroutelogger.importer

import jp.kjtr.cycleroutelogger.gpx.GpxTime
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * TCX（Garmin Training Center。Strava の一括書き出しにもある）のストリーミング解析。
 * Activity@Sport・Track（= 続いた線）・Trackpoint の Time / Position（LatitudeDegrees・LongitudeDegrees）/ AltitudeMeters /
 * 拡張の TPX/Speed を読む。位置の無い Trackpoint は数えるだけ。名前空間・接頭辞は見ない。
 */
object TcxParser {
    data class Result(val points: Int, val noPosition: Int, val partial: Boolean, val sawRoot: Boolean)

    fun parse(parser: XmlPullParser, input: InputStream, sink: PointSink, check: () -> Unit = {}): Result {
        var points = 0
        var noPos = 0
        var partial = false
        var sawRoot = false
        val stack = ArrayList<String>()
        val text = StringBuilder()
        var inPoint = false
        var time: Long? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var ele: Double? = null
        var speed: Float? = null
        try {
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            parser.setInput(input, null)
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val name = local(parser.name)
                        if (stack.isEmpty()) {
                            if (name != "TrainingCenterDatabase") throw NotTcx()
                            sawRoot = true
                        }
                        stack.add(name)
                        text.setLength(0)
                        when (name) {
                            "Activity" -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (local(parser.getAttributeName(i)) == "Sport") sink.sport(Sport.of(parser.getAttributeValue(i)))
                                }
                            }
                            "Track" -> sink.beginRun()
                            "Trackpoint" -> {
                                inPoint = true
                                time = null; lat = Double.NaN; lon = Double.NaN; ele = null; speed = null
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> parser.text?.let { text.append(it) }
                    XmlPullParser.END_TAG -> {
                        val name = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else local(parser.name)
                        val v = text.toString().trim()
                        if (inPoint) {
                            when (name) {
                                "Time" -> time = GpxTime.parse(v)
                                "LatitudeDegrees" -> lat = v.toDoubleOrNull() ?: Double.NaN
                                "LongitudeDegrees" -> lon = v.toDoubleOrNull() ?: Double.NaN
                                "AltitudeMeters" -> ele = v.toDoubleOrNull()?.takeIf { it.isFinite() }
                                "Speed" -> speed = v.toFloatOrNull()
                                "Trackpoint" -> {
                                    inPoint = false
                                    val t = time
                                    if (t != null) {
                                        if (!lat.isNaN() && !lon.isNaN()) {
                                            sink.point(t, lat, lon, ele, speed)
                                            points++
                                            if (points % 4096 == 0) check()
                                        } else {
                                            noPos++
                                        }
                                    }
                                }
                            }
                        }
                        text.setLength(0)
                    }
                }
                ev = parser.next()
            }
        } catch (e: NotTcx) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!sawRoot) throw NotTcx()
            partial = true
        }
        return Result(points, noPos, partial, sawRoot)
    }

    class NotTcx : Exception("not TCX")

    private fun local(n: String?): String {
        val s = n ?: return ""
        val i = s.indexOf(':')
        return if (i >= 0) s.substring(i + 1) else s
    }
}
