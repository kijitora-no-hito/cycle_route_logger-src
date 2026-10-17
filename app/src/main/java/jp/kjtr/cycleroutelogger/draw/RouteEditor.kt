package jp.kjtr.cycleroutelogger.draw

import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify

/** 描いたルートの形の計算（純ロジック）。原点は最初のアンカー。 */
object DrawnGeometry {
    fun enu(shape: DrawnShape): Enu = Enu(shape.anchors.firstOrNull()?.latLon ?: LatLon(0.0, 0.0))

    /** 区間 [i]（アンカー i → i+1）のベジエ。 */
    fun cubic(shape: DrawnShape, i: Int, enu: Enu = enu(shape)): Cubic {
        val a = shape.anchors[i]
        val b = shape.anchors[i + 1]
        val p0 = enu.toVec(a)
        val p3 = enu.toVec(b)
        return Cubic(p0, a.outH?.let { p0 + it } ?: p0, b.inH?.let { p3 + it } ?: p3, p3)
    }

    /** 区間ごとの点列（引き方は [SegmentRouter]）。 */
    fun segmentPoints(shape: DrawnShape): List<List<LatLon>> {
        if (shape.anchors.size < 2) return emptyList()
        val enu = enu(shape)
        return (0 until shape.segmentCount).map { i ->
            Routers.of(shape.method(i)).route(cubic(shape, i, enu)).map { enu.toLatLon(it) }
        }
    }

    /** ルート全体の点列（区間のつなぎ目は 1 点）。アンカーが 1 つならその点。 */
    fun path(shape: DrawnShape): List<LatLon> {
        if (shape.anchors.size == 1) return listOf(shape.anchors[0].latLon)
        val out = ArrayList<LatLon>()
        for (seg in segmentPoints(shape)) {
            if (out.isEmpty()) out.addAll(seg) else out.addAll(seg.drop(1))
        }
        return out
    }

    fun segmentLengths(shape: DrawnShape): List<Double> {
        if (shape.anchors.size < 2) return emptyList()
        val enu = enu(shape)
        return (0 until shape.segmentCount).map { cubic(shape, it, enu).length(256) }
    }

    /** 点 [p] に最も近い区間と t、距離（m）。区間が無ければ null。 */
    fun nearest(shape: DrawnShape, p: LatLon): Triple<Int, Double, Double>? {
        if (shape.anchors.size < 2) return null
        val enu = enu(shape)
        val q = enu.toVec(p)
        var best: Triple<Int, Double, Double>? = null
        for (i in 0 until shape.segmentCount) {
            val (t, d) = cubic(shape, i, enu).nearest(q)
            if (best == null || d < best.third) best = Triple(i, t, d)
        }
        return best
    }

    /**
     * GPX などの点列から描いたルートを作る（角の点だけ。ハンドル無し）。点が多ければ Douglas–Peucker の許容誤差を
     * 3 m から倍にしていき、[maxAnchors] 以下にする。
     */
    fun fromPoints(points: List<LatLon>, maxAnchors: Int = MAX_EDIT_ANCHORS): DrawnShape {
        var tol = 3.0
        var pts = if (points.size > 2) Simplify.douglasPeucker(points, tol) else points
        while (pts.size > maxAnchors && tol < 100_000) {
            tol *= 2
            pts = Simplify.douglasPeucker(points, tol)
        }
        if (pts.size > maxAnchors) pts = Simplify.stride(pts, maxAnchors)
        return DrawnShape(anchors = pts.map { Anchor(it.lat, it.lon) })
    }

    /** 編集できるアンカーの数の目安（これを超える GPX は間引いてから描いたルートに変換する）。 */
    const val MAX_EDIT_ANCHORS = 300
}

/**
 * 描いたルートの編集（点の追加・挿入・移動・削除・ハンドル・種類・元に戻す / やり直し）。純ロジック。
 * 変更は 1 回ごとに履歴に積む。ドラッグ中は [beginChange] で 1 回だけ積み、`record = false` で動かす。
 */
class RouteEditor(initial: DrawnShape = DrawnShape()) {
    var shape: DrawnShape = initial
        private set

    private val undoStack = ArrayDeque<DrawnShape>()
    private val redoStack = ArrayDeque<DrawnShape>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** ドラッグなどの続く変更の始まり（履歴に今の形を 1 回だけ積む）。 */
    fun beginChange() = push()

    private fun push() {
        undoStack.addLast(shape)
        if (undoStack.size > HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun set(new: DrawnShape, record: Boolean) {
        if (record) push()
        shape = new
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(shape)
        shape = prev
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(shape)
        shape = next
        return true
    }

    private fun methodsPadded(n: Int): MutableList<String> =
        MutableList(n) { shape.methods.getOrNull(it) ?: StraightRouter.ID }

    /** 終点の後に点を足す。 */
    fun add(p: LatLon, record: Boolean = true) {
        val m = methodsPadded(shape.segmentCount)
        if (shape.anchors.isNotEmpty()) m.add(StraightRouter.ID)
        set(shape.copy(anchors = shape.anchors + Anchor(p.lat, p.lon), methods = m), record)
    }

    /** 区間 [seg] の t の位置に点を入れる（de Casteljau。形は変えない）。新しい点の番号を返す。 */
    fun insertAt(seg: Int, t: Double, record: Boolean = true): Int {
        require(seg in 0 until shape.segmentCount)
        val enu = DrawnGeometry.enu(shape)
        val c = DrawnGeometry.cubic(shape, seg, enu)
        val list = shape.anchors.toMutableList()
        val a = list[seg]
        val b = list[seg + 1]
        val newAnchor: Anchor
        if (c.isLine) {
            val pos = enu.toLatLon(c.point(t))
            newAnchor = Anchor(pos.lat, pos.lon)
        } else {
            val (l, r) = c.split(t)
            val s = l.p3
            val pos = enu.toLatLon(s)
            list[seg] = a.copy(outH = handle(l.p1 - l.p0))
            list[seg + 1] = b.copy(inH = handle(r.p2 - r.p3))
            newAnchor = Anchor(pos.lat, pos.lon, inH = handle(l.p2 - s), outH = handle(r.p1 - s), kind = AnchorKind.SMOOTH)
        }
        list.add(seg + 1, newAnchor)
        val m = methodsPadded(shape.segmentCount)
        m.add(seg + 1, m[seg])
        set(shape.copy(anchors = list, methods = m), record)
        return seg + 1
    }

    private fun handle(v: Vec): Vec? = if (v.length < 1e-6) null else v

    /** 点を動かす（ハンドルは一緒に動く）。 */
    fun move(i: Int, p: LatLon, record: Boolean = true) {
        val list = shape.anchors.toMutableList()
        list[i] = list[i].copy(lat = p.lat, lon = p.lon)
        set(shape.copy(anchors = list), record)
    }

    /**
     * ハンドルを動かす（[out] = true なら次の区間側）。[h] はアンカーからの相対（m）。null で消す。
     * なめらか: 反対のハンドルを一直線に（長さはそのまま。無ければ同じ長さで作る）。対称: 反対を -h。
     */
    fun moveHandle(i: Int, out: Boolean, h: Vec?, record: Boolean = true) {
        val list = shape.anchors.toMutableList()
        val a = list[i]
        val other = if (out) a.inH else a.outH
        val newOther = when {
            h == null -> other
            a.kind == AnchorKind.SMOOTH -> if (other == null || other.length < 1e-9) -h else -h.unit() * other.length
            a.kind == AnchorKind.SYMMETRIC -> -h
            else -> other
        }
        list[i] = if (out) a.copy(outH = h, inH = newOther) else a.copy(inH = h, outH = newOther)
        set(shape.copy(anchors = list), record)
    }

    /** 点を消す（2 区間は 1 区間になる。前の点の後ろ側・次の点の前側のハンドルはそのまま）。 */
    fun delete(i: Int, record: Boolean = true) {
        if (i !in shape.anchors.indices) return
        val list = shape.anchors.toMutableList()
        list.removeAt(i)
        val m = methodsPadded(shape.segmentCount)
        if (m.isNotEmpty()) m.removeAt((i - 1).coerceAtLeast(0).coerceAtMost(m.size - 1))
        if (list.isNotEmpty()) {
            list[0] = list[0].copy(inH = null)
            list[list.size - 1] = list.last().copy(outH = null)
        }
        set(shape.copy(anchors = list, methods = m.take((list.size - 1).coerceAtLeast(0))), record)
    }

    /** 種類を変える。なめらか・対称にするとき、ハンドルが無ければ前後の点から自動で付ける。 */
    fun setKind(i: Int, kind: AnchorKind, record: Boolean = true) {
        val list = shape.anchors.toMutableList()
        var a = list[i].copy(kind = kind)
        if (kind != AnchorKind.CORNER) {
            val auto = autoHandle(i)
            var outH = a.outH ?: if (i < list.size - 1) auto else null
            var inH = a.inH ?: if (i > 0) auto?.let { -it } else null
            when {
                outH != null && inH != null && kind == AnchorKind.SYMMETRIC -> inH = -outH
                outH != null && inH != null -> inH = -outH.unit() * inH.length
                outH == null && inH != null && i < list.size - 1 -> outH = -inH
            }
            a = a.copy(inH = inH, outH = outH)
        }
        list[i] = a
        set(shape.copy(anchors = list), record)
    }

    /** 角 ⇔ なめらか（選んだ点をもう一度タップしたとき）。 */
    fun toggleSmooth(i: Int) = setKind(i, if (shape.anchors[i].kind == AnchorKind.CORNER) AnchorKind.SMOOTH else AnchorKind.CORNER)

    /** ハンドルを消して角にする（前後とも直線）。 */
    fun clearHandles(i: Int, record: Boolean = true) {
        val list = shape.anchors.toMutableList()
        list[i] = list[i].copy(inH = null, outH = null, kind = AnchorKind.CORNER)
        set(shape.copy(anchors = list), record)
    }

    /** Catmull-Rom 風の自動ハンドル（次の区間側）: (次 − 前) / 6。端は隣との差 / 6。 */
    private fun autoHandle(i: Int): Vec? {
        val n = shape.anchors.size
        if (n < 2) return null
        val enu = DrawnGeometry.enu(shape)
        val prev = enu.toVec(shape.anchors[(i - 1).coerceAtLeast(0)])
        val next = enu.toVec(shape.anchors[(i + 1).coerceAtMost(n - 1)])
        val d = (next - prev) * (1.0 / 6.0)
        return if (d.length < 1e-9) null else d
    }

    /** 全体をなめらかに（すべての点に Catmull-Rom からハンドルを付ける。中の点は「なめらか」）。 */
    fun smoothAll(record: Boolean = true) {
        val n = shape.anchors.size
        if (n < 3) return
        val enu = DrawnGeometry.enu(shape)
        val v = shape.anchors.map { enu.toVec(it) }
        val list = shape.anchors.mapIndexed { i, a ->
            when (i) {
                0 -> a.copy(inH = null, outH = handle((v[1] - v[0]) * (1.0 / 6.0)), kind = AnchorKind.CORNER)
                n - 1 -> a.copy(inH = handle((v[n - 2] - v[n - 1]) * (1.0 / 6.0)), outH = null, kind = AnchorKind.CORNER)
                else -> {
                    val d = (v[i + 1] - v[i - 1]) * (1.0 / 6.0)
                    a.copy(inH = handle(-d), outH = handle(d), kind = AnchorKind.SMOOTH)
                }
            }
        }
        set(shape.copy(anchors = list), record)
    }

    /** 折り返し（来た道を戻る）: 終点から逆順に点を足す（ハンドルは前後を入れ替える）。 */
    fun outAndBack(record: Boolean = true) {
        val n = shape.anchors.size
        if (n < 2) return
        val list = shape.anchors.toMutableList()
        val last = list[n - 1]
        // 折り返し点は同じ向きに戻る（とがった点）ので角
        list[n - 1] = last.copy(outH = last.inH, kind = AnchorKind.CORNER)
        for (i in n - 2 downTo 0) {
            val a = shape.anchors[i]
            list.add(a.copy(inH = a.outH, outH = if (i == 0) null else a.inH))
        }
        val m = methodsPadded(shape.segmentCount)
        val back = m.reversed()
        set(shape.copy(anchors = list, methods = m + back), record)
    }

    /** 閉じる（始点に戻る区間を足す）。 */
    fun close(record: Boolean = true) {
        val n = shape.anchors.size
        if (n < 3) return
        val first = shape.anchors[0]
        if (first.lat == shape.anchors.last().lat && first.lon == shape.anchors.last().lon) return
        add(first.latLon, record)
    }

    companion object {
        const val HISTORY = 200
    }
}
