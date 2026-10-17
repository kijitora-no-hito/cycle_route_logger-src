package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.stats.MapTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** まとめの全走行の地図をタイルで描くための計算（0.10.2）: 座標の変換・範囲・タップした記録の判定。 */
class MapTilesTest {
    @Test
    fun encodeRoundTrip() {
        val pts = listOf(LatLon(35.681236, 139.767125), LatLon(-33.8688, 151.2093), LatLon(0.0, -0.000001))
        val back = MapTiles.decode(MapTiles.encode(pts))
        for ((a, b) in pts.zip(back)) {
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
        // LatLon のリストより小さい（1 点 8 バイト）
        assertEquals(6, MapTiles.encode(pts).size)
    }

    @Test
    fun worldCoordinatesAndTiles() {
        assertEquals(0.5, MapTiles.worldX(0.0), 1e-12)
        assertEquals(0.5, MapTiles.worldY(0.0), 1e-12)
        assertEquals(0.0, MapTiles.worldX(-180.0), 1e-12)
        assertTrue(MapTiles.worldY(35.0) < 0.5) // 北は上（小さい）
        // 東京駅はズーム 10 のタイル (909, 403)
        val x = (MapTiles.worldX(139.767125) * 1024).toInt()
        val y = (MapTiles.worldY(35.681236) * 1024).toInt()
        assertEquals(909, x)
        assertEquals(403, y)
        val w = MapTiles.tileWorld(909, 403, 10)
        val b = GeoBounds(35.68, 139.76, 35.69, 139.77)
        assertTrue(MapTiles.intersects(b, w, 0.0))
        assertFalse(MapTiles.intersects(b, MapTiles.tileWorld(911, 403, 10), 0.0))
        // 余白を広げれば隣のタイルにもかかる
        assertTrue(MapTiles.intersects(GeoBounds(35.68, 140.0975, 35.69, 140.0976), MapTiles.tileWorld(909, 403, 10), 1.0 / 1024))
    }

    @Test
    fun boundsOfLines() {
        val a = MapTiles.encode(listOf(LatLon(35.0, 139.0), LatLon(35.5, 139.8)))
        val b = MapTiles.encode(listOf(LatLon(34.9, 139.2)))
        val r = MapTiles.boundsOf(listOf(a, b))!!
        assertEquals(34.9, r.south, 1e-9)
        assertEquals(139.0, r.west, 1e-9)
        assertEquals(35.5, r.north, 1e-9)
        assertEquals(139.8, r.east, 1e-9)
        assertNull(MapTiles.boundsOf(emptyList()))
        val u = MapTiles.union(listOf(r, null, GeoBounds(36.0, 140.0, 36.1, 140.1)))!!
        assertEquals(36.1, u.north, 1e-9)
        assertEquals(140.1, u.east, 1e-9)
    }

    @Test
    fun nearestRideByTap() {
        // 東西の線（北緯 35.68）と、その 1 km 北の線
        fun ride(id: Long, lat: Double): MapTiles.HitLine {
            val l = listOf(MapTiles.encode(listOf(LatLon(lat, 139.70), LatLon(lat, 139.80))))
            return MapTiles.HitLine(id, MapTiles.boundsOf(l), l)
        }
        val rides = listOf(ride(1, 35.68), ride(2, 35.689))
        // ズーム 15（1 dp ≒ 3.9 m）: 線の 20 m 南は 5 dp → 1 番
        assertEquals(1L, MapTiles.nearest(rides, 35.68 - 0.00018, 139.75, 15f, 14.0))
        // 2 本の間でも近い方
        assertEquals(2L, MapTiles.nearest(rides, 35.6888, 139.75, 15f, 14.0))
        // どちらからも 400 m（約 100 dp）→ なし
        assertNull(MapTiles.nearest(rides, 35.6845, 139.75, 15f, 14.0))
        // ズーム 9 なら同じ所でも 14 dp 以内（1 dp ≒ 250 m）
        assertTrue(MapTiles.nearest(rides, 35.6845, 139.75, 9f, 14.0) != null)
        // 線の端の外（東へ 2 km）は届かない
        assertNull(MapTiles.nearest(rides, 35.68, 139.822, 15f, 14.0))
    }

    @Test
    fun manyRidesStayCompact() {
        // 1200 本 × 細かい線 900 点: IntArray なら 1 本 7.2 KB、合計 9 MB 足らず（LatLon のリストなら 1 点 40 バイト以上）
        val bytes = (0 until 1200).sumOf { i ->
            val pts = List(900) { k -> LatLon(35.0 + i * 0.001 + k * 0.0001, 139.0 + k * 0.0001) }
            MapTiles.encode(pts).size * 4L
        }
        assertTrue(bytes < 9_000_000)
    }
}
