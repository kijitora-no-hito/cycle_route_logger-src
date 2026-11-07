package jp.kjtr.cycleroutelogger.draw

import kotlin.math.ceil

/** 3 次ベジエ 1 区間（局所平面 m）。ハンドルが無い区間は p1 = p0・p2 = p3（= 直線）。 */
data class Cubic(val p0: Vec, val p1: Vec, val p2: Vec, val p3: Vec) {
    val isLine: Boolean get() = p1 == p0 && p2 == p3

    /** t の位置。直線（ハンドル無し）は t に比例（ベジエの式だと端に寄るため）。 */
    fun point(t: Double): Vec {
        if (isLine) return Vec.lerp(p0, p3, t)
        val u = 1 - t
        val a = u * u * u
        val b = 3 * u * u * t
        val c = 3 * u * t * t
        val d = t * t * t
        return Vec(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }

    /** de Casteljau 法で t の位置で 2 つに分ける（形は変わらない）。 */
    fun split(t: Double): Pair<Cubic, Cubic> {
        val q0 = Vec.lerp(p0, p1, t)
        val q1 = Vec.lerp(p1, p2, t)
        val q2 = Vec.lerp(p2, p3, t)
        val r0 = Vec.lerp(q0, q1, t)
        val r1 = Vec.lerp(q1, q2, t)
        val s = Vec.lerp(r0, r1, t)
        return Cubic(p0, q0, r0, s) to Cubic(s, r1, q2, p3)
    }

    /** 長さ（[steps] 分割の折れ線で近似）。 */
    fun length(steps: Int = 64): Double {
        if (isLine) return (p3 - p0).length
        var len = 0.0
        var prev = p0
        for (i in 1..steps) {
            val q = point(i.toDouble() / steps)
            len += (q - prev).length
            prev = q
        }
        return len
    }

    /**
     * 地図表示・距離・GPX 用の点列（始点と終点を含む）。直線は 2 点。曲線は区間の長さに応じて
     * [minSpacingM]〜[maxSpacingM] 間隔（長さ / 100 を目安）で、道のりがほぼ等間隔になるように取る。
     */
    fun sample(minSpacingM: Double = 2.0, maxSpacingM: Double = 5.0): List<Vec> {
        if (isLine) return listOf(p0, p3)
        val len = length()
        val spacing = (len / 100).coerceIn(minSpacingM, maxSpacingM)
        // 細かく取ってから、道のりで等間隔に拾い直す（t の等分だと曲がり方で間隔がばらつく）
        val dense = (ceil(len / spacing).toInt() * 32).coerceIn(64, 200_000)
        val out = ArrayList<Vec>()
        out.add(p0)
        var prev = p0
        var acc = 0.0
        for (i in 1..dense) {
            val q = point(i.toDouble() / dense)
            acc += (q - prev).length
            prev = q
            if (acc >= spacing && i < dense) {
                out.add(q)
                acc = 0.0
            }
        }
        out.add(p3)
        return out
    }

    /** 点 [q] に最も近い t（粗く探してから狭める）と、その距離。 */
    fun nearest(q: Vec): Pair<Double, Double> {
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        val coarse = 64
        for (i in 0..coarse) {
            val t = i.toDouble() / coarse
            val d = (point(t) - q).length
            if (d < bestD) {
                bestD = d; bestT = t
            }
        }
        var lo = (bestT - 1.0 / coarse).coerceAtLeast(0.0)
        var hi = (bestT + 1.0 / coarse).coerceAtMost(1.0)
        repeat(40) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            if ((point(m1) - q).length < (point(m2) - q).length) hi = m2 else lo = m1
        }
        val t = (lo + hi) / 2
        val d = (point(t) - q).length
        return if (d < bestD) t to d else bestT to bestD
    }
}

/**
 * 2 点（アンカー）の間の線の引き方。今は [StraightRouter]（ハンドルが無ければ直線、あればベジエ曲線）だけ。
 * 「道に沿う」方式を足すときは、この interface を実装して [Routers] に登録し、区間の [DrawnShape.methods] にその id を入れる
 * （UI の切り替えはまだ出さない）。戻り値は局所平面の点列（始点と終点を含む）。
 */
interface SegmentRouter {
    val id: String
    fun route(segment: Cubic): List<Vec>
}

/** 直線（とベジエのハンドルによる曲線）。 */
object StraightRouter : SegmentRouter {
    const val ID = "straight"
    override val id: String = ID
    override fun route(segment: Cubic): List<Vec> = segment.sample()
}

object Routers {
    private val all: Map<String, SegmentRouter> = mapOf(StraightRouter.ID to StraightRouter)
    fun of(id: String?): SegmentRouter = all[id] ?: StraightRouter
}
