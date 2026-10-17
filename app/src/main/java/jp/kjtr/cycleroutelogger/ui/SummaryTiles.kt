package jp.kjtr.cycleroutelogger.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import jp.kjtr.cycleroutelogger.data.RideLines
import jp.kjtr.cycleroutelogger.stats.CellRect
import jp.kjtr.cycleroutelogger.stats.MapTiles
import java.io.ByteArrayOutputStream
import kotlin.math.pow

/**
 * まとめの「全走行の地図」の軌跡と開拓したマスをタイルの画像に描く（0.10.2。DESIGN.md §4.7.1）。
 * 記録が何千本あっても、地図が要求した見えている数枚のタイルを描くだけ（Polyline・Polygon を地図に足さないので Maps のメモリが増えない）。
 * [getTile] は Maps のバックグラウンドのスレッドから並んで呼ばれるが、中身は読むだけなので同期は要らない。
 *
 * @param lines 描く記録の線（[colors] と同じ順。色は ARGB）
 * @param fineFromZoom このズーム以上は細かい線（[RideLines.fine]）
 * @param widthDp 線の太さ（dp = タイルの 256 単位のうちの 1）
 */
class RideTileProvider(
    private val lines: List<RideLines>,
    private val colors: IntArray,
    private val widthDp: Float,
    private val cells: List<CellRect>,
    private val cellFill: Int,
    private val newCells: List<CellRect>,
    private val newCellFill: Int,
    private val fineFromZoom: Int = 13,
) : TileProvider {

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val w = MapTiles.tileWorld(x, y, zoom)
        val n = 2.0.pow(zoom)
        // 1 dp の世界座標（線の太さの分だけ外のものも拾う）
        val unitWorld = 1.0 / (MapTiles.TILE_UNITS * n)
        val margin = unitWorld * (widthDp + 2)
        val rideIdx = lines.indices.filter { i -> lines[i].bounds?.let { MapTiles.intersects(it, w, margin) } == true }
        val cellHit = cells.filter { intersects(it, w) }
        val newHit = newCells.filter { intersects(it, w) }
        if (rideIdx.isEmpty() && cellHit.isEmpty() && newHit.isEmpty()) return TileProvider.NO_TILE

        val bmp = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val pxPerWorld = TILE_PX * n
            fun px(lon: Double) = ((MapTiles.worldX(lon) - w[0]) * pxPerWorld).toFloat()
            fun py(lat: Double) = ((MapTiles.worldY(lat) - w[1]) * pxPerWorld).toFloat()
            val scale = TILE_PX / MapTiles.TILE_UNITS.toFloat()

            // マス（下に。塗りと少し濃い縁）
            val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
            val edge = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
            for ((list, color) in listOf(cellHit to cellFill, newHit to newCellFill)) {
                if (list.isEmpty()) continue
                fill.color = color
                edge.color = withAlpha(color, (((color ushr 24) and 0xFF) * 1.8f).coerceAtMost(255f).toInt())
                for (r in list) {
                    val l = px(r.west)
                    val t = py(r.north)
                    val rr = px(r.east)
                    val b = py(r.south)
                    canvas.drawRect(l, t, rr, b, fill)
                    canvas.drawRect(l, t, rr, b, edge)
                }
            }

            // 軌跡
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = widthDp * scale
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                isAntiAlias = true
            }
            val fine = zoom >= fineFromZoom
            val path = Path()
            for (i in rideIdx) {
                val segs = if (fine) lines[i].fine else lines[i].coarse
                path.rewind()
                for (seg in segs) {
                    if (seg.size < 4) continue
                    path.moveTo(px(seg[1] / 1e6), py(seg[0] / 1e6))
                    var k = 2
                    while (k + 1 < seg.size) {
                        path.lineTo(px(seg[k + 1] / 1e6), py(seg[k] / 1e6))
                        k += 2
                    }
                }
                paint.color = colors[i]
                canvas.drawPath(path, paint)
            }
            val out = ByteArrayOutputStream(16 * 1024)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            return Tile(TILE_PX, TILE_PX, out.toByteArray())
        } finally {
            bmp.recycle()
        }
    }

    private fun intersects(r: CellRect, w: DoubleArray): Boolean {
        val x0 = MapTiles.worldX(r.west)
        val x1 = MapTiles.worldX(r.east)
        val y0 = MapTiles.worldY(r.north)
        val y1 = MapTiles.worldY(r.south)
        return x1 >= w[0] && x0 <= w[2] && y1 >= w[1] && y0 <= w[3]
    }

    private fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    companion object {
        /** タイル 1 枚の画素（256 dp を 512 px に。高密度の画面でもぼやけにくい）。 */
        const val TILE_PX = 512
    }
}
