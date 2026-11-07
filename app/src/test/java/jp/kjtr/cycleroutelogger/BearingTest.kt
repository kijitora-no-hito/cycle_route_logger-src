package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.Bearing
import jp.kjtr.cycleroutelogger.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BearingTest {
    @Test
    fun initialBearing_cardinal() {
        assertEquals(0.0, Bearing.initialDeg(TOKYO, offset(TOKYO, 1000.0, 0.0)), 0.01)
        assertEquals(90.0, Bearing.initialDeg(TOKYO, offset(TOKYO, 0.0, 1000.0)), 0.05)
        assertEquals(180.0, Bearing.initialDeg(TOKYO, offset(TOKYO, -1000.0, 0.0)), 0.01)
        assertEquals(270.0, Bearing.initialDeg(TOKYO, offset(TOKYO, 0.0, -1000.0)), 0.05)
        assertEquals(45.0, Bearing.initialDeg(TOKYO, offset(TOKYO, 1000.0, 1000.0)), 0.1)
        // 赤道上で東はちょうど 90°
        assertEquals(90.0, Bearing.initialDeg(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-9)
    }

    @Test
    fun octantNames() {
        assertEquals(0, Bearing.octant(0.0))
        assertEquals(0, Bearing.octant(22.0))
        assertEquals(1, Bearing.octant(23.0))
        assertEquals(1, Bearing.octant(44.0))
        assertEquals(2, Bearing.octant(90.0))
        assertEquals(4, Bearing.octant(200.0))
        assertEquals(7, Bearing.octant(315.0))
        assertEquals(0, Bearing.octant(350.0))
        assertEquals(0, Bearing.octant(-5.0))
        assertEquals(6, Bearing.octant(-90.0))
    }

    @Test
    fun screenRotation_followsMapBearing() {
        // 北が上の地図: 方位角そのまま
        assertEquals(45.0, Bearing.screenRotation(45.0, 0.0), 1e-9)
        // 進行方向（東）が上の地図で、目的地が東 → 真上
        assertEquals(0.0, Bearing.screenRotation(90.0, 90.0), 1e-9)
        // 地図が 350° 回っていて目的地が 10° → 右へ 20°
        assertEquals(20.0, Bearing.screenRotation(10.0, 350.0), 1e-9)
        // 目的地が北・地図が東向き → 左（270°）
        assertEquals(270.0, Bearing.screenRotation(0.0, 90.0), 1e-9)
        assertEquals(-20.0, Bearing.diff(350.0, 10.0), 1e-9)
    }

    @Test
    fun edgeMarker_positions() {
        val w = 1000f
        val h = 2000f
        val m = 50f
        assertNull(Bearing.edgeMarker(w, h, m, 100f, 100f)) // 画面内
        val right = Bearing.edgeMarker(w, h, m, 5000f, 0f)!!
        assertEquals(950f, right.x, 0.01f)
        assertEquals(1000f, right.y, 0.01f)
        assertEquals(90f, right.rotationDeg, 0.01f)
        val up = Bearing.edgeMarker(w, h, m, 0f, -5000f)!!
        assertEquals(500f, up.x, 0.01f)
        assertEquals(50f, up.y, 0.01f)
        assertEquals(0f, up.rotationDeg, 0.01f)
        val diag = Bearing.edgeMarker(w, h, m, 1000f, 1000f)!!
        assertEquals(950f, diag.x, 0.01f)
        assertEquals(1450f, diag.y, 0.01f)
        assertEquals(135f, diag.rotationDeg, 0.01f)
        val downLeft = Bearing.edgeMarker(w, h, m, -300f, 3000f)!!
        assertEquals(1950f, downLeft.y, 0.01f)
        assertEquals(500f - 300f * (950f / 3000f), downLeft.x, 0.01f)
        assertEquals(180f + 5.71f, downLeft.rotationDeg, 0.05f)
    }

    @Test
    fun screenVector_matchesRotation() {
        val (x, y) = Bearing.screenVector(90.0)
        assertEquals(1f, x, 1e-6f)
        assertEquals(0f, y, 1e-6f)
        val (x2, y2) = Bearing.screenVector(0.0)
        assertEquals(0f, x2, 1e-6f)
        assertEquals(-1f, y2, 1e-6f)
    }
}
