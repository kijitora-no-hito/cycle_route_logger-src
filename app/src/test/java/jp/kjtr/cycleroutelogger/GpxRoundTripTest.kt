package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxTime
import jp.kjtr.cycleroutelogger.gpx.GpxTrack
import jp.kjtr.cycleroutelogger.gpx.GpxWaypoint
import jp.kjtr.cycleroutelogger.gpx.GpxWriter
import jp.kjtr.cycleroutelogger.plan.WaypointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxRoundTripTest {

    private fun write(d: GpxData, time: Long? = null): String = StringBuilder().also { GpxWriter.write(it, d, time) }.toString()

    @Test
    fun time_parseAndFormat() {
        assertEquals(0L, GpxTime.parse("1970-01-01T00:00:00Z"))
        assertEquals(1_714_532_645_000L, GpxTime.parse("2024-05-01T03:04:05Z"))
        assertEquals(1_714_532_645_123L, GpxTime.parse("2024-05-01T03:04:05.123456Z"))
        assertEquals(1_714_532_645_000L, GpxTime.parse("2024-05-01T12:04:05+09:00"))
        assertEquals(1_714_532_645_000L, GpxTime.parse("2024-05-01T12:04:05+0900"))
        assertEquals(1_714_532_645_000L, GpxTime.parse("2024-04-30T22:04:05-05:00"))
        assertEquals(1_714_532_645_000L, GpxTime.parse("2024-05-01T03:04:05")) // タイムゾーン無し = UTC
        assertNull(GpxTime.parse("yesterday"))
        assertNull(GpxTime.parse("2024-13-01T00:00:00Z"))
        assertNull(GpxTime.parse(null))
        assertEquals("2024-05-01T03:04:05Z", GpxTime.format(1_714_532_645_000L))
        assertEquals("2024-05-01T03:04:05.007Z", GpxTime.format(1_714_532_645_007L))
        assertEquals("2000-02-29T23:59:59Z", GpxTime.format(GpxTime.parse("2000-02-29T23:59:59Z")!!))
        for (ms in listOf(0L, 951_782_399_000L, 1_799_999_999_999L, 4_102_444_800_000L)) {
            assertEquals(ms, GpxTime.parse(GpxTime.format(ms)))
        }
    }

    @Test
    fun writeThenRead_tracksAndWaypoints() {
        val segs = listOf(
            (0 until 50).map { GpxPoint(35.0 + it * 1e-4, 139.0 + it * 2e-4, 10.0 + it * 0.25, 1_714_532_645_000L + it * 1000) },
            (0 until 20).map { GpxPoint(36.0 + it * 1e-4, 140.0, null, null) },
        )
        val d = GpxData(
            name = "名前 & <テスト> \"引用\"",
            desc = "メモ\n2 行目",
            tracks = listOf(GpxTrack("1 日目", segs), GpxTrack("経路", listOf(segs[1]), isRoute = true)),
            waypoints = listOf(GpxWaypoint(35.5, 139.5, 12.25, 1_714_600_000_000L, "カフェ", "休憩 'ここ'", "food", "Restaurant")),
            version = "1.1",
        )
        val back = parseGpx(write(d, 1_714_532_645_000L))
        assertEquals(d.name, back.name)
        assertEquals(d.desc, back.desc)
        assertEquals(2, back.tracks.size)
        // rte はスキーマの順で trk より前に書かれる
        val rte = back.tracks.first { it.isRoute }
        val trk = back.tracks.first { !it.isRoute }
        assertEquals("1 日目", trk.name)
        assertEquals("経路", rte.name)
        assertEquals(2, trk.segments.size)
        for ((a, b) in segs.flatten().zip(trk.segments.flatten())) {
            assertEquals(a.lat, b.lat, 1e-7)
            assertEquals(a.lon, b.lon, 1e-7)
            if (a.ele == null) assertNull(b.ele) else assertEquals(a.ele!!, b.ele!!, 0.005)
            assertEquals(a.timeMs, b.timeMs)
        }
        val w = back.waypoints.single()
        assertEquals("カフェ", w.name)
        assertEquals("休憩 'ここ'", w.desc)
        assertEquals("food", w.type)
        assertEquals("Restaurant", w.sym)
        assertEquals(1_714_600_000_000L, w.timeMs)
        assertEquals(12.25, w.ele!!, 0.005)
    }

    @Test
    fun trackExport_roundTrip() {
        val t = TrackEntity(id = 3, name = "朝の走行", startedAt = 1_714_532_645_000L, status = TrackEntity.STATUS_FINISHED, mode = TrackEntity.MODE_FREE, autoPause = true)
        val pts = (0 until 30).map {
            TrackPointEntity(id = it.toLong(), trackId = 3, segment = if (it < 20) 0 else 1, time = t.startedAt + it * 1000L, lat = 35.0 + it * 1e-5, lon = 139.0, ele = 5.0 + it, accuracy = 4f, deviceSpeed = null, speed = null)
        }
        val back = parseGpx(write(TrackRepository.trackToGpx(t, pts), t.startedAt))
        assertEquals("朝の走行", back.name)
        val segs = back.tracks.single().segments
        assertEquals(listOf(20, 10), segs.map { it.size })
        assertEquals(pts.map { it.time }, segs.flatten().map { it.timeMs })
        assertEquals(pts.last().ele!!, segs.last().last().ele!!, 0.005)
    }

    @Test
    fun planWaypoints_roundTripSameValues() {
        val originals = WaypointType.entries.mapIndexed { i, type ->
            WaypointEntity(
                id = i + 1L, planId = 9, name = "地点$i", type = type.code, memo = if (i % 2 == 0) "メモ$i" else "",
                plannedAt = if (i % 3 == 0) 1_714_532_645_000L + i * 60_000L else null,
                lat = 35.0 + i * 0.01, lon = 139.0 - i * 0.01, ele = if (i % 2 == 0) i * 1.5 else null, createdAt = 100L + i,
            )
        }
        val data = GpxData("プラン", "プランのメモ", listOf(GpxTrack("ルート", listOf(listOf(GpxPoint(35.0, 139.0), GpxPoint(35.1, 139.1))))), originals.map { PlanRepository.waypointToGpx(it) }, "1.1")
        val back = parseGpx(write(data))
        assertEquals("プランのメモ", back.desc)
        val restored = back.waypoints.mapIndexed { i, w -> PlanRepository.waypointFromGpx(9, w, 100L + i) }
        assertEquals(originals.size, restored.size)
        for ((a, b) in originals.zip(restored)) {
            assertEquals(a.name, b.name)
            assertEquals(a.type, b.type)
            assertEquals(a.memo, b.memo)
            assertEquals(a.plannedAt, b.plannedAt)
            assertEquals(a.lat, b.lat, 1e-7)
            assertEquals(a.lon, b.lon, 1e-7)
            if (a.ele == null) assertNull(b.ele) else assertEquals(a.ele!!, b.ele!!, 0.005)
            assertEquals(a.copy(id = 0, lat = 0.0, lon = 0.0, ele = null), b.copy(lat = 0.0, lon = 0.0, ele = null))
        }
    }

    @Test
    fun escapeDropsControlChars() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", GpxWriter.escape("a&b<c>\"'"))
        assertEquals("ab", GpxWriter.escape("a\u0001b"))
        assertTrue(GpxWriter.escape("改行\nOK").contains("\n"))
    }
}
