package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.gpx.GpxWaypoint
import jp.kjtr.cycleroutelogger.gpx.GpxWriter
import jp.kjtr.cycleroutelogger.record.TrackExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import kotlin.math.sin

class TrackExportTest {
    private val t0 = 1_700_000_000_000L

    /** 曲がりくねった道（1 秒ごと・約 5 m/s）を [n] 点。[segAt] 以降は区間 1。 */
    private fun wiggly(n: Int, segAt: Int = Int.MAX_VALUE, stopFrom: Int = -1, stopTo: Int = -1): List<TrackPointEntity> {
        val out = ArrayList<TrackPointEntity>()
        var lat = 35.0
        var lon = 139.0
        for (i in 0 until n) {
            val stopped = i in stopFrom until stopTo
            if (!stopped) {
                lat += 0.00004
                lon += 0.00003 * sin(i / 15.0) + 0.00001
            }
            out.add(
                TrackPointEntity(
                    id = i.toLong() + 1, trackId = 1, segment = if (i >= segAt) 1 else 0, time = t0 + i * 1000L,
                    lat = lat + if (stopped) (i % 3) * 0.000005 else 0.0, lon = lon, ele = 10.0 + i * 0.1,
                    accuracy = 5f, deviceSpeed = if (stopped) 0f else 5f, speed = if (stopped) 0.2f else 5f,
                ),
            )
        }
        return out
    }

    @Test
    fun noOptions_keepsAllPointsAndSegments() {
        val pts = wiggly(300, segAt = 200)
        val segs = TrackExport.segments(pts)
        assertEquals(2, segs.size)
        assertEquals(200, segs[0].size)
        assertEquals(100, segs[1].size)
        // 時刻・標高も残る
        assertEquals(t0, segs[0][0].timeMs)
        assertEquals(10.0, segs[0][0].ele!!, 1e-9)
    }

    @Test
    fun douglasPeucker_reducesPointsButKeepsShapeWithinTolerance() {
        val pts = wiggly(1000)
        for (tol in listOf(3.0, 5.0, 10.0)) {
            val segs = TrackExport.segments(pts, toleranceM = tol)
            val s = segs.single()
            assertTrue("tol=$tol size=${s.size}", s.size < pts.size / 3)
            // 始点・終点は残る
            assertEquals(pts.first().time, s.first().timeMs)
            assertEquals(pts.last().time, s.last().timeMs)
            // 元のすべての点が、間引いた線から許容誤差（+ 局所平面の誤差の余裕）以内
            val m = RouteMatcher(listOf(s.map { LatLon(it.lat, it.lon) }))
            val worst = pts.maxOf { m.match(LatLon(it.lat, it.lon))!!.deviationM }
            assertTrue("tol=$tol worst=$worst", worst <= tol + 0.5)
            // 長さはほとんど変わらない（カーブの内側を通る分だけ短くなる。2% 以内）
            val full = Geo.lengthM(pts.map { LatLon(it.lat, it.lon) })
            assertEquals(full, TrackExport.lengthM(segs), full * 0.02)
        }
    }

    @Test
    fun dropStops_removesStationaryPointsButKeepsEnds() {
        val pts = wiggly(300, stopFrom = 100, stopTo = 160)
        val segs = TrackExport.segments(pts, dropStops = true)
        assertEquals(300 - 60, segs.single().size)
        // 区間の終わりが止まっている点でも残す
        val endStop = wiggly(100, stopFrom = 90, stopTo = 100)
        val s = TrackExport.segments(endStop, dropStops = true).single()
        assertEquals(endStop.last().time, s.last().timeMs)
        assertEquals(91, s.size)
    }

    @Test
    fun export_writeAndReadBack_sameDistanceSegmentsAndWaypoints() {
        val pts = wiggly(500, segAt = 250)
        val t = TrackEntity(
            id = 1, name = "2026-10-11 09:00 川沿い", startedAt = t0, endedAt = t0 + 500_000, status = TrackEntity.STATUS_FINISHED,
            mode = TrackEntity.MODE_ROUTE, planId = 3, planName = "川沿い", autoPause = true,
        )
        val wp = GpxWaypoint(35.005, 139.003, null, t0 + 120_000, "09:02 カフェ", null, "food", "Restaurant")
        val data = TrackExport.gpx(t, pts, listOf(wp))
        val text = StringBuilder().also { GpxWriter.write(it, data, t.startedAt) }.toString()
        val back = GpxParser().parse(KXmlParser(), text.byteInputStream())
        assertEquals(1, back.tracks.size)
        assertEquals(2, back.tracks[0].segments.size)
        assertEquals(500, back.pointCount)
        val before = TrackExport.lengthM(TrackExport.segments(pts))
        val after = back.tracks[0].segments.sumOf { s -> Geo.lengthM(s.map { it.latLon() }) }
        assertEquals(before, after, 0.05)
        assertEquals("09:02 カフェ", back.waypoints.single().name)
        assertEquals("food", back.waypoints.single().type)
        assertEquals(t0 + 250_000, back.tracks[0].segments[1][0].timeMs)
    }

    @Test
    fun passedWaypoints_onlyNearTrack_inPassingOrder() {
        val pts = wiggly(400)
        val near1 = pts[300].let { LatLon(it.lat + 0.0003, it.lon) } // 約 33 m
        val near2 = pts[50].let { LatLon(it.lat, it.lon + 0.0005) } // 約 46 m
        val far = LatLon(35.2, 139.2)
        val r = TrackExport.passed(pts, listOf(near1, far, near2)) { it }
        assertEquals(listOf(near2, near1), r.map { it.first })
        assertEquals(pts[50].time, r[0].second)
    }

    @Test
    fun fileBaseName() {
        assertEquals("2026-10-11_フリー", TrackExport.fileBaseName("2026-10-11", "2026-10-11 09:00", "2026-10-11 09:00 フリー"))
        assertEquals("2026-10-11_朝練", TrackExport.fileBaseName("2026-10-11", "2026-10-11 09:00", "朝練"))
        assertEquals("2026-10-11", TrackExport.fileBaseName("2026-10-11", "2026-10-11 09:00", "2026-10-11 09:00"))
    }
}
