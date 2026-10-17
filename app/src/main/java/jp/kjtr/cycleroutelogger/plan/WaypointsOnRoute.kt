package jp.kjtr.cycleroutelogger.plan

import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher

/** ルート沿いに並べた経由地 1 件。 */
data class OnRoute<T>(
    val item: T,
    /** ルート上の始点からの道のり [m]（経由地からルートへ下ろした足の位置）。ルートが無ければ null。 */
    val progressM: Double?,
    /** 経由地からルートまでの距離 [m]。 */
    val offsetM: Double?,
)

/**
 * 経由地をルート沿いの順に並べる・次の経由地を決める（純ロジック。単体テスト: WaypointsOnRouteTest）。
 */
object WaypointsOnRoute {
    /** これより遠くルートを外れた経由地は「次の経由地」の候補にしない [m]。 */
    const val MAX_OFFSET_FOR_NEXT_M = 1_000.0

    /** 経由地のこの距離手前まで来たら「通過した」とみなす前の猶予 [m]（通り過ぎて 20 m で次へ）。 */
    const val PASSED_MARGIN_M = 20.0

    /**
     * ルート沿いの道のり順に並べる。ルートが無い・空なら元の順（[fallbackOrder] の順）のまま。
     * 同じ道のりなら [fallbackOrder] の順。
     */
    fun <T> sort(
        matcher: RouteMatcher?,
        items: List<T>,
        position: (T) -> LatLon,
        fallbackOrder: (T) -> Long,
    ): List<OnRoute<T>> {
        if (matcher == null || matcher.isEmpty) {
            return items.sortedBy(fallbackOrder).map { OnRoute(it, null, null) }
        }
        return items.map { item ->
            val m = matcher.match(position(item))
            OnRoute(item, m?.progressM, m?.deviationM)
        }.sortedWith(compareBy<OnRoute<T>> { it.progressM ?: Double.MAX_VALUE }.thenBy { fallbackOrder(it.item) })
    }

    /**
     * 現在の進捗 [currentProgressM] の先にある最初の経由地。通り過ぎて [PASSED_MARGIN_M] までは「次」のまま。
     * ルートから [MAX_OFFSET_FOR_NEXT_M] より離れた経由地は飛ばす。
     */
    fun <T> next(sorted: List<OnRoute<T>>, currentProgressM: Double): OnRoute<T>? =
        sorted.firstOrNull { w ->
            val p = w.progressM ?: return@firstOrNull false
            (w.offsetM ?: 0.0) <= MAX_OFFSET_FOR_NEXT_M && p >= currentProgressM - PASSED_MARGIN_M
        }

    /** 次の経由地までのルート沿いの残り [m]（通り過ぎた直後は 0）。 */
    fun alongDistanceM(target: OnRoute<*>, currentProgressM: Double): Double? =
        target.progressM?.let { (it - currentProgressM).coerceAtLeast(0.0) }
}
