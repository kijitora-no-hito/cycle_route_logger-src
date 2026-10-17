package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.gpx.GpxException
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.gpx.GpxTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GpxParserTest {

    private val gpx11 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Doc name</name><desc>Doc desc</desc></metadata>
          <wpt lat="35.1" lon="139.1"><ele>12.5</ele><name>Cafe &amp; Bar</name><desc>coffee</desc><sym>Restaurant</sym></wpt>
          <rte><name>My route</name>
            <rtept lat="35.0" lon="139.0"/>
            <rtept lat="35.001" lon="139.001"><ele>5</ele></rtept>
          </rte>
          <trk><name>Day 1</name>
            <trkseg>
              <trkpt lat="35.0" lon="139.0"><ele>10</ele><time>2024-05-01T03:04:05Z</time>
                <extensions><gpxtpx:TrackPointExtension xmlns:gpxtpx="x"><gpxtpx:hr>120</gpxtpx:hr><name>ignored</name></gpxtpx:TrackPointExtension></extensions>
              </trkpt>
              <trkpt lat="35.0005" lon="139.0005"><ele>11.5</ele><time>2024-05-01T03:04:06.500Z</time></trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="35.01" lon="139.01"/>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()

    @Test
    fun gpx11_tracksRoutesWaypoints() {
        val d = parseGpx(gpx11)
        assertEquals("1.1", d.version)
        assertEquals("Doc name", d.name)
        assertEquals("Doc desc", d.desc)
        assertEquals(2, d.tracks.size)
        val rte = d.tracks[0]
        assertTrue(rte.isRoute)
        assertEquals("My route", rte.name)
        assertEquals(1, rte.segments.size)
        assertEquals(2, rte.segments[0].size)
        assertNull(rte.segments[0][0].ele)
        assertEquals(5.0, rte.segments[0][1].ele!!, 1e-9)

        val trk = d.tracks[1]
        assertFalse(trk.isRoute)
        assertEquals("Day 1", trk.name)
        assertEquals(2, trk.segments.size)
        assertEquals(10.0, trk.segments[0][0].ele!!, 1e-9)
        assertEquals(GpxTime.parse("2024-05-01T03:04:05Z"), trk.segments[0][0].timeMs)
        assertEquals(GpxTime.parse("2024-05-01T03:04:05Z")!! + 1500, trk.segments[0][1].timeMs)
        assertNull(trk.segments[1][0].ele)
        assertNull(trk.segments[1][0].timeMs)

        assertEquals(1, d.waypoints.size)
        val w = d.waypoints[0]
        assertEquals("Cafe & Bar", w.name)
        assertEquals("coffee", w.desc)
        assertEquals("Restaurant", w.sym)
        assertEquals(12.5, w.ele!!, 1e-9)
        assertFalse(d.partial)
        assertFalse(d.decimated)
    }

    @Test
    fun gpx10_topLevelNameAndSpeedIgnored() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.0" creator="old" xmlns="http://www.topografix.com/GPX/1/0">
              <name>Old file</name>
              <time>2020-01-01T00:00:00Z</time>
              <trk><name>T</name><trkseg>
                <trkpt lat="34.5" lon="135.5"><ele>3</ele><time>2020-01-01T00:00:00Z</time><speed>4.5</speed><course>90</course></trkpt>
                <trkpt lat="34.5001" lon="135.5001"><time>2020-01-01T00:00:01Z</time></trkpt>
              </trkseg></trk>
              <wpt lat="34.6" lon="135.6"><name>W</name><type>Toilet</type></wpt>
            </gpx>
        """.trimIndent()
        val d = parseGpx(xml)
        assertEquals("1.0", d.version)
        assertEquals("Old file", d.name)
        assertEquals(1, d.tracks.size)
        assertEquals(2, d.tracks[0].pointCount)
        assertEquals("Toilet", d.waypoints[0].type)
    }

    @Test
    fun prefixedNamespaceAndNoEleNoTime() {
        val xml = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" version="1.1"><g:trk><g:trkseg>
            <g:trkpt lat="1" lon="2"/><g:trkpt lat="1.001" lon="2.001"></g:trkpt></g:trkseg></g:trk></g:gpx>"""
        val d = parseGpx(xml)
        assertEquals(2, d.pointCount)
        assertNull(d.tracks[0].segments[0][0].ele)
        assertNull(d.tracks[0].segments[0][0].timeMs)
        assertNull(d.tracks[0].name)
    }

    @Test
    fun invalidCoordinatesAreSkipped() {
        val xml = """<gpx version="1.1"><trk><trkseg>
            <trkpt lat="abc" lon="2"/><trkpt lat="95" lon="2"/><trkpt lon="2"/><trkpt lat="1" lon="2"/>
            </trkseg></trk><wpt lat="1" lon="999"><name>x</name></wpt></gpx>"""
        val d = parseGpx(xml)
        assertEquals(1, d.pointCount)
        assertEquals(0, d.waypoints.size)
    }

    @Test
    fun brokenFile_returnsWhatWasRead() {
        val xml = """<gpx version="1.1"><trk><name>T</name><trkseg>
            <trkpt lat="35" lon="139"/><trkpt lat="35.001" lon="139.001"/><trkpt lat="35.002" lo"""
        val d = parseGpx(xml)
        assertTrue(d.partial)
        assertEquals(2, d.pointCount)
    }

    @Test
    fun mismatchedTags_returnsPartial() {
        val xml = """<gpx><trk><trkseg><trkpt lat="1" lon="1"></trkpt></trk></gpx>"""
        val d = parseGpx(xml)
        assertTrue(d.partial)
        assertEquals(1, d.pointCount)
    }

    @Test
    fun notGpx_throws() {
        expectReason(GpxException.Reason.NOT_GPX) { parseGpx("""<kml><Document/></kml>""") }
        expectReasonIn(setOf(GpxException.Reason.NOT_GPX, GpxException.Reason.BROKEN)) { parseGpx("hello, this is not xml") }
        expectReasonIn(setOf(GpxException.Reason.NOT_GPX, GpxException.Reason.BROKEN)) { parseGpx("") }
    }

    @Test
    fun emptyGpx_throws() {
        expectReason(GpxException.Reason.EMPTY) { parseGpx("""<gpx version="1.1"><metadata><name>x</name></metadata><trk><trkseg/></trk></gpx>""") }
    }

    @Test
    fun tooLarge_throws() {
        val sb = StringBuilder("<gpx><trk><trkseg>")
        repeat(2000) { sb.append("<trkpt lat=\"1\" lon=\"1\"/>") }
        sb.append("</trkseg></trk></gpx>")
        expectReason(GpxException.Reason.TOO_LARGE) { parseGpx(sb.toString(), GpxParser(maxBytes = 10_000)) }
    }

    @Test
    fun manyPoints_areDecimatedKeepingEnds() {
        val n = 10_000
        val sb = StringBuilder("<gpx version=\"1.1\"><trk><trkseg>")
        for (i in 0 until n) sb.append("<trkpt lat=\"35.0\" lon=\"").append(139.0 + i * 1e-5).append("\"/>")
        sb.append("</trkseg></trk></gpx>")
        val d = parseGpx(sb.toString(), GpxParser(maxPoints = 1000))
        assertTrue(d.decimated)
        assertTrue("count=${d.pointCount}", d.pointCount in 400..1000)
        val seg = d.tracks[0].segments[0]
        assertEquals(139.0, seg.first().lon, 1e-12)
        assertEquals(139.0 + (n - 1) * 1e-5, seg.last().lon, 1e-9)
        // 順番は保たれている
        assertTrue(seg.zipWithNext().all { (a, b) -> a.lon < b.lon })
    }

    @Test
    fun largeFile_defaultLimit() {
        // 30 万点（約 15 MB）: 既定の上限 20 万点に収まり、時間内に読める
        val n = 300_000
        val sb = StringBuilder(n * 50)
        sb.append("<gpx version=\"1.1\"><trk><trkseg>")
        for (i in 0 until n) sb.append("<trkpt lat=\"35.0\" lon=\"").append(139.0 + i * 1e-6).append("\"><ele>1</ele></trkpt>")
        sb.append("</trkseg></trk></gpx>")
        val t0 = System.currentTimeMillis()
        val d = parseGpx(sb.toString())
        val ms = System.currentTimeMillis() - t0
        assertTrue(d.decimated)
        assertTrue(d.pointCount <= GpxParser.DEFAULT_MAX_POINTS)
        assertTrue(d.pointCount >= GpxParser.DEFAULT_MAX_POINTS / 2)
        assertTrue("took $ms ms", ms < 20_000)
    }

    @Test
    fun waypointLimit() {
        val sb = StringBuilder("<gpx>")
        repeat(30) { sb.append("<wpt lat=\"1\" lon=\"1\"><name>w$it</name></wpt>") }
        sb.append("</gpx>")
        val d = parseGpx(sb.toString(), GpxParser(maxWaypoints = 10))
        assertEquals(10, d.waypoints.size)
        assertTrue(d.waypointsTruncated)
    }

    private fun expectReason(r: GpxException.Reason, block: () -> Unit) = expectReasonIn(setOf(r), block)

    private fun expectReasonIn(rs: Set<GpxException.Reason>, block: () -> Unit) {
        try {
            block()
            fail("expected $rs")
        } catch (e: GpxException) {
            assertTrue("got ${e.reason}", e.reason in rs)
        }
    }
}
