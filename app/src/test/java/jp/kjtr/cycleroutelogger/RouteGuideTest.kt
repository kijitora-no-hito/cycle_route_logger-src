package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher
import jp.kjtr.cycleroutelogger.plan.WaypointsOnRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ルートからの外れ距離・進捗と、ルート沿いの経由地の並び・次の経由地。 */
class RouteGuideTest {
    /** 東へ 1 km（10 m おき）。 */
    private val east1km = (0..100).map { offset(TOKYO, 0.0, it * 10.0) }

    @Test
    fun deviationAndProgress_onStraightLine() {
        val m = RouteMatcher(listOf(east1km))
        assertEquals(1000.0, m.totalM, 0.5)
        val r = m.match(offset(TOKYO, 100.0, 500.0))!!
        assertEquals(100.0, r.deviationM, 0.5)
        assertEquals(500.0, r.progressM, 0.5)
        assertEquals(500.0, r.remainingM, 0.5)
        // 線の中間（点と点の間）でも足の位置で数える
        val r2 = m.match(offset(TOKYO, -20.0, 333.0))!!
        assertEquals(20.0, r2.deviationM, 0.5)
        assertEquals(333.0, r2.progressM, 0.5)
    }

    @Test
    fun beforeStartAndAfterEnd() {
        val m = RouteMatcher(listOf(east1km))
        val before = m.match(offset(TOKYO, 0.0, -50.0))!!
        assertEquals(50.0, before.deviationM, 0.5)
        assertEquals(0.0, before.progressM, 1e-6)
        val after = m.match(offset(TOKYO, 0.0, 1080.0))!!
        assertEquals(80.0, after.deviationM, 0.5)
        assertEquals(1000.0, after.progressM, 0.5)
        assertEquals(0.0, after.remainingM, 0.5)
    }

    @Test
    fun multipleSegments_progressSkipsGap() {
        val far = offset(TOKYO, 5000.0, 0.0)
        val seg2 = (0..50).map { offset(far, 0.0, it * 10.0) } // 500 m
        val m = RouteMatcher(listOf(east1km, seg2))
        assertEquals(1500.0, m.totalM, 1.0)
        val r = m.match(offset(far, 5.0, 300.0))!!
        assertEquals(5.0, r.deviationM, 0.5)
        assertEquals(1300.0, r.progressM, 1.0)
        assertEquals(200.0, r.remainingM, 1.0)
    }

    @Test
    fun lShapedRoute_nearestLeg() {
        // 東へ 500 m → 北へ 500 m
        val corner = offset(TOKYO, 0.0, 500.0)
        val line = (0..50).map { offset(TOKYO, 0.0, it * 10.0) } + (1..50).map { offset(corner, it * 10.0, 0.0) }
        val m = RouteMatcher(listOf(line))
        val r = m.match(offset(corner, 300.0, 30.0))!!
        assertEquals(30.0, r.deviationM, 0.5)
        assertEquals(800.0, r.progressM, 1.0)
    }

    @Test
    fun emptyRoute() {
        val m = RouteMatcher(emptyList())
        assertTrue(m.isEmpty)
        assertNull(m.match(TOKYO))
    }

    private data class W(val id: Long, val p: LatLon)

    @Test
    fun waypointsAreSortedAlongRoute() {
        val m = RouteMatcher(listOf(east1km))
        val ws = listOf(
            W(1, offset(TOKYO, 30.0, 800.0)),
            W(2, offset(TOKYO, -10.0, 200.0)),
            W(3, offset(TOKYO, 0.0, 500.0)),
        )
        val sorted = WaypointsOnRoute.sort(m, ws, { it.p }, { it.id })
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.item.id })
        assertEquals(200.0, sorted[0].progressM!!, 0.5)
        assertEquals(10.0, sorted[0].offsetM!!, 0.5)
        assertEquals(800.0, sorted[2].progressM!!, 0.5)
    }

    @Test
    fun nextWaypoint_andAlongDistance() {
        val m = RouteMatcher(listOf(east1km))
        val ws = listOf(
            W(1, offset(TOKYO, 0.0, 200.0)),
            W(2, offset(TOKYO, 0.0, 500.0)),
            W(3, offset(TOKYO, 2000.0, 600.0)), // ルートから 2 km 離れている → 次の候補にしない
            W(4, offset(TOKYO, 0.0, 800.0)),
        )
        val sorted = WaypointsOnRoute.sort(m, ws, { it.p }, { it.id })
        assertEquals(1L, WaypointsOnRoute.next(sorted, 0.0)!!.item.id)
        val n = WaypointsOnRoute.next(sorted, 300.0)!!
        assertEquals(2L, n.item.id)
        assertEquals(200.0, WaypointsOnRoute.alongDistanceM(n, 300.0)!!, 0.5)
        // 10 m 通り過ぎた直後はまだ同じ経由地（残り 0）
        val justPassed = WaypointsOnRoute.next(sorted, 510.0)!!
        assertEquals(2L, justPassed.item.id)
        assertEquals(0.0, WaypointsOnRoute.alongDistanceM(justPassed, 510.0)!!, 1e-9)
        // 30 m 過ぎたら次へ（離れた 3 は飛ばして 4）
        assertEquals(4L, WaypointsOnRoute.next(sorted, 530.0)!!.item.id)
        assertNull(WaypointsOnRoute.next(sorted, 900.0))
    }

    @Test
    fun withoutRoute_keepsCreationOrder() {
        val ws = listOf(W(5, TOKYO), W(2, TOKYO), W(9, TOKYO))
        val sorted = WaypointsOnRoute.sort(null, ws, { it.p }, { it.id })
        assertEquals(listOf(2L, 5L, 9L), sorted.map { it.item.id })
        assertNull(sorted[0].progressM)
        assertNull(WaypointsOnRoute.next(sorted, 0.0))
    }
}
