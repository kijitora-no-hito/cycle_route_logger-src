package jp.kjtr.cycleroutelogger.data

import android.content.Context
import android.util.Log
import jp.kjtr.cycleroutelogger.stats.TrackFilter
import jp.kjtr.cycleroutelogger.stats.TrackFilters
import jp.kjtr.cycleroutelogger.stats.TrackSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/** 一覧に出すもの（絞り込み・並べ替えの結果）。 */
data class BrowseResult(
    /** 絞り込んで並べた記録。 */
    val items: List<TrackEntity>,
    /** 終えた記録の全部（「1108 件中 37 件」の 1108）。 */
    val all: List<TrackEntity>,
    val filter: TrackFilter,
    val sort: TrackSort,
    /** 場所の条件があるのに索引を作っている途中で外した記録の数。 */
    val geoPending: Int,
    /** 絞り込みにかかった時間 [ms]（ログ・確認用）。 */
    val tookMs: Long,
)

/**
 * 記録の一覧の絞り込みと並べ替え（0.11.0。DESIGN.md §4.11）。条件は SharedPreferences `track_filter` に JSON で保存し、次に開いても残る。
 * 計算はメモリの中だけ（記録の行・写真の数・場所の索引 [TrackGeoIndex]）。
 */
class TrackBrowser(context: Context, private val db: AppDatabase, private val geo: TrackGeoIndex, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("track_filter", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _filter = MutableStateFlow(decode(prefs.getString(K_FILTER, null)))
    val filter: StateFlow<TrackFilter> = _filter
    private val _sort = MutableStateFlow(TrackSort.entries.firstOrNull { it.name == prefs.getString(K_SORT, null) } ?: TrackSort.NEWEST)
    val sort: StateFlow<TrackSort> = _sort

    fun setFilter(f: TrackFilter) {
        _filter.value = f
        prefs.edit().putString(K_FILTER, json.encodeToString(TrackFilter.serializer(), f)).apply()
    }

    fun update(f: (TrackFilter) -> TrackFilter) = setFilter(f(_filter.value))

    fun clear() = setFilter(TrackFilter())

    fun setSort(s: TrackSort) {
        _sort.value = s
        prefs.edit().putString(K_SORT, s.name).apply()
    }

    private val photoCounts = db.photos().countsByTrack().map { list -> list.associate { it.trackId to it.n } }

    /** 場所の条件があるときだけ索引の変化で計算し直す（作っている途中の 0.5 秒ごとの知らせで、場所の条件の無い一覧を何度も作り直さない）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val geoForFilter = combine(_filter.map { it.place != null }.distinctUntilChanged(), geo.state) { needed, st ->
        if (needed) st.entries else emptyMap()
    }.distinctUntilChanged { a, b -> a === b }

    @OptIn(ExperimentalCoroutinesApi::class)
    val result: StateFlow<BrowseResult?> = combine(db.tracks().finished(), _filter, _sort, photoCounts, geoForFilter) { all, f, s, photos, g ->
        Input(all, f, s, photos, g)
    }.mapLatest { (all, f, s, photos, g) ->
        val t0 = System.nanoTime()
        val out = TrackFilters.apply(all, f, s, photos, g)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (f.isActive) Log.d(TAG, "filter ${out.items.size}/${all.size} in $ms ms (place=${f.place != null}, pending=${out.geoPending})")
        BrowseResult(out.items, all, f, s, out.geoPending, ms)
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Input(
        val all: List<TrackEntity>, val f: TrackFilter, val s: TrackSort, val photos: Map<Long, Int>, val g: Map<Long, jp.kjtr.cycleroutelogger.stats.TrackGeo>,
    )

    private fun decode(s: String?): TrackFilter =
        s?.let { runCatching { json.decodeFromString(TrackFilter.serializer(), it) }.getOrNull() } ?: TrackFilter()

    companion object {
        private const val TAG = "CRL-Filter"
        private const val K_FILTER = "filter"
        private const val K_SORT = "sort"
    }
}
