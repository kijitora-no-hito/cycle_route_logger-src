package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.draw.AnchorKind
import jp.kjtr.cycleroutelogger.draw.Cubic
import jp.kjtr.cycleroutelogger.draw.DrawnGeometry
import jp.kjtr.cycleroutelogger.draw.DrawnShape
import jp.kjtr.cycleroutelogger.draw.Enu
import jp.kjtr.cycleroutelogger.draw.RouteEditor
import jp.kjtr.cycleroutelogger.draw.Vec
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxTrack
import jp.kjtr.cycleroutelogger.gpx.GpxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import kotlin.math.abs

class RouteDrawTest {
    private val o = LatLon(35.68, 139.76)
    private val enu = Enu(o)
    private fun at(x: Double, y: Double) = enu.toLatLon(Vec(x, y))

    private fun threePoints(): RouteEditor = RouteEditor().apply {
        add(at(0.0, 0.0)); add(at(1000.0, 0.0)); add(at(1000.0, 1000.0))
    }

    @Test
    fun add_insert_delete_undo_redo() {
        val e = threePoints()
        assertEquals(3, e.shape.anchors.size)
        assertEquals(2, e.shape.segmentCount)
        assertEquals(2000.0, DrawnGeometry.segmentLengths(e.shape).sum(), 1.0)

        // 直線の区間の真ん中に挿入 → 4 点、長さは同じ
        val i = e.insertAt(0, 0.5)
        assertEquals(1, i)
        assertEquals(4, e.shape.anchors.size)
        assertEquals(500.0, enu.toVec(e.shape.anchors[1]).x, 0.01)
        assertEquals(2000.0, DrawnGeometry.segmentLengths(e.shape).sum(), 1.0)
        assertEquals(3, e.shape.methods.size)

        e.delete(3)
        assertEquals(3, e.shape.anchors.size)
        assertEquals(1000.0, DrawnGeometry.segmentLengths(e.shape).sum(), 1.0)

        assertTrue(e.undo())
        assertEquals(4, e.shape.anchors.size)
        assertTrue(e.undo())
        assertEquals(3, e.shape.anchors.size)
        assertTrue(e.redo())
        assertEquals(4, e.shape.anchors.size)
        // 新しい変更でやり直しは消える
        e.move(0, at(-100.0, 0.0))
        assertFalse(e.canRedo)
        assertEquals(-100.0, enu.toVec(e.shape.anchors[0]).x, 0.01)
    }

    @Test
    fun dragRecordsHistoryOnce() {
        val e = threePoints()
        e.beginChange()
        for (k in 1..20) e.move(1, at(1000.0 + k, 0.0), record = false)
        assertTrue(e.undo())
        assertEquals(1000.0, enu.toVec(e.shape.anchors[1]).x, 0.01)
    }

    /** 曲線を作り、de Casteljau で分割しても形が変わらない。 */
    @Test
    fun deCasteljauSplitKeepsShape() {
        val e = threePoints()
        e.smoothAll()
        e.moveHandle(1, out = true, h = Vec(0.0, 400.0))
        val before = DrawnGeometry.path(e.shape)
        val lenBefore = DrawnGeometry.segmentLengths(e.shape).sum()
        for (t in listOf(0.2, 0.5, 0.77)) e.insertAt(0, t)
        e.insertAt(e.shape.segmentCount - 1, 0.4)
        val after = DrawnGeometry.path(e.shape)
        val m = RouteMatcher(listOf(before))
        val worst = after.maxOf { m.match(it)!!.deviationM }
        assertTrue("worst=$worst", worst < 0.3) // 折れ線近似の差だけ
        assertEquals(lenBefore, DrawnGeometry.segmentLengths(e.shape).sum(), lenBefore * 0.001)
        // 分割点は曲線上（なめらか）
        assertEquals(AnchorKind.SMOOTH, e.shape.anchors[1].kind)
    }

    @Test
    fun cubicSplitExact() {
        val c = Cubic(Vec(0.0, 0.0), Vec(100.0, 300.0), Vec(400.0, -200.0), Vec(500.0, 100.0))
        val (l, r) = c.split(0.3)
        for (k in 0..10) {
            val u = k / 10.0
            val pl = l.point(u)
            val ql = c.point(0.3 * u)
            assertEquals(ql.x, pl.x, 1e-9); assertEquals(ql.y, pl.y, 1e-9)
            val pr = r.point(u)
            val qr = c.point(0.3 + 0.7 * u)
            assertEquals(qr.x, pr.x, 1e-9); assertEquals(qr.y, pr.y, 1e-9)
        }
    }

    @Test
    fun smoothAndSymmetricConstraints() {
        val e = threePoints()
        e.setKind(1, AnchorKind.SMOOTH)
        val a = e.shape.anchors[1]
        assertTrue(a.inH != null && a.outH != null)
        e.moveHandle(1, out = true, h = Vec(300.0, 400.0))
        val s = e.shape.anchors[1]
        // 一直線で反対向き、反対側の長さは変わらない
        val cross = s.outH!!.x * s.inH!!.y - s.outH!!.y * s.inH!!.x
        assertEquals(0.0, cross, 1e-6)
        assertTrue(s.outH!!.x * s.inH!!.x + s.outH!!.y * s.inH!!.y < 0)
        assertEquals(a.inH!!.length, s.inH!!.length, 1e-6)

        e.setKind(1, AnchorKind.SYMMETRIC)
        e.moveHandle(1, out = false, h = Vec(-50.0, 20.0))
        val y = e.shape.anchors[1]
        assertEquals(50.0, y.outH!!.x, 1e-9)
        assertEquals(-20.0, y.outH!!.y, 1e-9)

        // 角: 別々
        e.setKind(1, AnchorKind.CORNER)
        e.moveHandle(1, out = true, h = Vec(10.0, 10.0))
        assertEquals(-50.0, e.shape.anchors[1].inH!!.x, 1e-9)

        e.clearHandles(1)
        assertNull(e.shape.anchors[1].inH)
        assertNull(e.shape.anchors[1].outH)
    }

    @Test
    fun samplingLengthError() {
        // 直線は 2 点で正確
        val line = Cubic(Vec(0.0, 0.0), Vec(0.0, 0.0), Vec(300.0, 400.0), Vec(300.0, 400.0))
        assertEquals(2, line.sample().size)
        assertEquals(500.0, line.length(), 1e-9)
        // 曲線: サンプリング（2〜5 m 間隔）の長さと細かい長さの差は 0.1% 未満、間隔は 5 m 以下
        val c = Cubic(Vec(0.0, 0.0), Vec(0.0, 800.0), Vec(1000.0, 800.0), Vec(1000.0, 0.0))
        val s = c.sample()
        var len = 0.0
        var maxGap = 0.0
        for (i in 1 until s.size) {
            val d = (s[i] - s[i - 1]).length
            len += d; maxGap = maxOf(maxGap, d)
        }
        val fine = c.length(20_000)
        assertTrue("len=$len fine=$fine", abs(len - fine) / fine < 0.001)
        assertTrue("maxGap=$maxGap", maxGap <= 5.5)
    }

    @Test
    fun outAndBackAndClose() {
        val e = threePoints()
        e.outAndBack()
        assertEquals(5, e.shape.anchors.size)
        assertEquals(4000.0, DrawnGeometry.segmentLengths(e.shape).sum(), 1.0)
        val last = e.shape.anchors.last()
        assertEquals(o.lat, last.lat, 1e-9)

        val c = threePoints()
        c.close()
        assertEquals(4, c.shape.anchors.size)
        assertEquals(2000.0 + 1414.2, DrawnGeometry.segmentLengths(c.shape).sum(), 1.0)
    }

    @Test
    fun nearestFindsSegmentAndT() {
        val e = threePoints()
        val (seg, t, d) = DrawnGeometry.nearest(e.shape, at(1010.0, 250.0))!!
        assertEquals(1, seg)
        assertEquals(0.25, t, 0.01)
        assertEquals(10.0, d, 0.1)
    }

    @Test
    fun saveAndReeditRoundTrip_andGpxExport() {
        val e = threePoints()
        e.smoothAll()
        e.moveHandle(1, out = true, h = Vec(100.0, 300.0))
        val json = e.shape.toJson()
        val back = DrawnShape.fromJson(json)!!
        assertEquals(e.shape, back)
        // 読み戻した形で編集を続けられる
        val e2 = RouteEditor(back)
        e2.insertAt(1, 0.5)
        assertEquals(4, e2.shape.anchors.size)

        // GPX はサンプリングした点。書き出し → 読み戻しで同じ長さ
        val pts = DrawnGeometry.path(back)
        val data = GpxData("drawn", null, listOf(GpxTrack("drawn", listOf(pts.map { GpxPoint(it.lat, it.lon) }))), emptyList(), "1.1")
        val text = StringBuilder().also { GpxWriter.write(it, data) }.toString()
        val parsed = GpxParser().parse(KXmlParser(), text.byteInputStream())
        val l1 = Geo.lengthM(pts)
        val l2 = Geo.lengthM(parsed.tracks[0].segments[0].map { it.latLon() })
        assertEquals(l1, l2, 0.05)
        assertEquals(DrawnGeometry.segmentLengths(back).sum(), l1, l1 * 0.002)
    }

    @Test
    fun fromPointsCapsAnchors() {
        val pts = (0 until 5000).map { at(it * 2.0, 30 * kotlin.math.sin(it / 40.0)) }
        val s = DrawnGeometry.fromPoints(pts, maxAnchors = 300)
        assertTrue(s.anchors.size in 2..300)
        assertEquals(pts.first().lat, s.anchors.first().lat, 1e-12)
        assertEquals(pts.last().lon, s.anchors.last().lon, 1e-12)
    }
}
