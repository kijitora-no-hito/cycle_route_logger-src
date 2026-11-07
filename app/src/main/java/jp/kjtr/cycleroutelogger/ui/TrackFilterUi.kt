package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.BrowseResult
import jp.kjtr.cycleroutelogger.importer.ActivityFormat
import jp.kjtr.cycleroutelogger.stats.DayPart
import jp.kjtr.cycleroutelogger.stats.ModeFilter
import jp.kjtr.cycleroutelogger.stats.NumRange
import jp.kjtr.cycleroutelogger.stats.PlaceArea
import jp.kjtr.cycleroutelogger.stats.PlaceMatch
import jp.kjtr.cycleroutelogger.stats.PlaceShape
import jp.kjtr.cycleroutelogger.stats.Season
import jp.kjtr.cycleroutelogger.stats.SourceFilter
import jp.kjtr.cycleroutelogger.stats.TrackFilter
import jp.kjtr.cycleroutelogger.stats.TrackFilters
import jp.kjtr.cycleroutelogger.stats.TrackSort
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * 記録の一覧の絞り込み（0.11.0。DESIGN.md §4.11）: 一覧の上の帯（［絞り込み］・並べ替え・件数・条件のチップ）と、条件を選ぶ画面。
 * 条件は選んだその場で一覧に効き、保存される（［適用］は無い。下のボタンで一覧に戻る）。
 */

/** 数の表示（整数なら小数を出さない）。 */
private fun num(v: Double): String =
    if (v == Math.floor(v) && abs(v) < 1e12) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

private fun parseNum(s: String): Double? = s.trim().replace('，', '.').replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/** 日数 → "2024/05/01"。 */
private fun dayText(day: Long): String = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(TrackFilters.pickerMsOfDay(day)))

/** 範囲の欄の決まり（スライダーの刻みと上端の決め方）。 */
private enum class RangeKind(val labelRes: Int, val unitRes: Int, val step: Double, val topStep: Double, val minTop: Double) {
    DISTANCE(R.string.filter_distance, R.string.unit_km, 1.0, 10.0, 50.0),
    AVG(R.string.filter_avg, R.string.unit_kmh, 0.5, 5.0, 30.0),
    MAX(R.string.filter_max_speed, R.string.unit_kmh, 1.0, 10.0, 50.0),
    MOVING(R.string.filter_moving, R.string.unit_min, 5.0, 60.0, 240.0),
    ASCENT(R.string.filter_ascent, R.string.unit_m, 10.0, 100.0, 500.0),
    ;

    fun get(f: TrackFilter): NumRange? = when (this) {
        DISTANCE -> f.distanceKm
        AVG -> f.avgKmh
        MAX -> f.maxKmh
        MOVING -> f.movingMin
        ASCENT -> f.ascentM
    }

    fun set(f: TrackFilter, r: NumRange?): TrackFilter = when (this) {
        DISTANCE -> f.copy(distanceKm = r)
        AVG -> f.copy(avgKmh = r)
        MAX -> f.copy(maxKmh = r)
        MOVING -> f.copy(movingMin = r)
        ASCENT -> f.copy(ascentM = r)
    }
}

@Composable
private fun rangeText(kind: RangeKind, r: NumRange): String {
    val label = stringResource(kind.labelRes)
    val unit = stringResource(kind.unitRes)
    return when {
        r.min != null && r.max != null -> stringResource(R.string.filter_range_both, label, num(r.min), num(r.max), unit)
        r.min != null -> stringResource(R.string.filter_range_min, label, num(r.min), unit)
        else -> stringResource(R.string.filter_range_max, label, num(r.max ?: 0.0), unit)
    }
}

@Composable
fun seasonLabel(s: Season) = stringResource(
    when (s) {
        Season.SPRING -> R.string.season_spring
        Season.SUMMER -> R.string.season_summer
        Season.AUTUMN -> R.string.season_autumn
        Season.WINTER -> R.string.season_winter
    },
)

@Composable
fun dayPartLabel(d: DayPart) = stringResource(
    when (d) {
        DayPart.MORNING -> R.string.daypart_morning
        DayPart.DAYTIME -> R.string.daypart_day
        DayPart.EVENING -> R.string.daypart_evening
        DayPart.NIGHT -> R.string.daypart_night
    },
)

/** 一覧の帯の並べ替えのボタン用の短い名前。 */
@Composable
fun sortShortLabel(s: TrackSort) = stringResource(
    when (s) {
        TrackSort.NEWEST -> R.string.sort_newest
        TrackSort.OLDEST -> R.string.sort_oldest
        TrackSort.DISTANCE -> R.string.sort_distance_short
        TrackSort.MOVING -> R.string.sort_moving_short
        TrackSort.AVG_SPEED -> R.string.sort_avg_short
        TrackSort.ASCENT -> R.string.sort_ascent_short
    },
)

@Composable
fun sortLabel(s: TrackSort) = stringResource(
    when (s) {
        TrackSort.NEWEST -> R.string.sort_newest
        TrackSort.OLDEST -> R.string.sort_oldest
        TrackSort.DISTANCE -> R.string.sort_distance
        TrackSort.MOVING -> R.string.sort_moving
        TrackSort.AVG_SPEED -> R.string.sort_avg
        TrackSort.ASCENT -> R.string.sort_ascent
    },
)

@Composable
private fun filterModeLabel(m: ModeFilter) = stringResource(
    when (m) {
        ModeFilter.ALL -> R.string.summary_mode_all
        ModeFilter.FREE -> R.string.summary_mode_free
        ModeFilter.ROUTE -> R.string.summary_mode_route
    },
)

@Composable
private fun sourceLabel(s: SourceFilter) = stringResource(
    when (s) {
        SourceFilter.ALL -> R.string.source_all
        SourceFilter.OWN -> R.string.source_own
        SourceFilter.IMPORTED -> R.string.source_imported
    },
)

/** 場所の条件の文（チップ・画面）。 */
@Composable
fun placeText(p: PlaceArea): String {
    val where = when (p.shape) {
        PlaceShape.CIRCLE -> stringResource(R.string.place_area_circle, p.label ?: stringResource(R.string.place_label_map), Format.distance(p.radius()))
        PlaceShape.RECT -> p.label ?: stringResource(R.string.place_label_rect)
    }
    return stringResource(
        when (p.match) {
            PlaceMatch.PASS -> R.string.place_text_pass
            PlaceMatch.START -> R.string.place_text_start
            PlaceMatch.END -> R.string.place_text_end
        },
        where,
    )
}

/** 一覧の上のチップ 1 つ分（文と、外したときの条件）。 */
private data class CondChip(val key: String, val text: String, val remove: (TrackFilter) -> TrackFilter)

@Composable
private fun conditionChips(f: TrackFilter): List<CondChip> {
    val out = ArrayList<CondChip>()
    val weekdays = stringArrayResource(R.array.weekday_short)
    if (f.year != null || f.month != null) {
        val t = when {
            f.year != null && f.month != null -> stringResource(R.string.filter_year_month, f.year, f.month)
            f.year != null -> stringResource(R.string.filter_year_n, f.year)
            else -> stringResource(R.string.filter_every_month, f.month ?: 1)
        }
        out.add(CondChip("ym", t) { it.copy(year = null, month = null) })
    }
    if (f.fromDay != null || f.toDay != null) {
        out.add(CondChip("range", stringResource(R.string.filter_range_value, f.fromDay?.let { dayText(it) } ?: "", f.toDay?.let { dayText(it) } ?: "")) {
            it.copy(fromDay = null, toDay = null)
        })
    }
    if (f.seasons.isNotEmpty()) {
        val t = Season.entries.filter { it in f.seasons }.map { seasonShort(it) }.joinToString("・")
        out.add(CondChip("season", t) { it.copy(seasons = emptySet()) })
    }
    if (f.weekdays.isNotEmpty()) {
        out.add(CondChip("wd", (1..7).filter { it in f.weekdays }.joinToString("・") { weekdays[it - 1] } + stringResource(R.string.filter_weekday_suffix)) { it.copy(weekdays = emptySet()) })
    }
    if (f.dayParts.isNotEmpty()) {
        out.add(CondChip("dp", DayPart.entries.filter { it in f.dayParts }.map { dayPartShort(it) }.joinToString("・")) { it.copy(dayParts = emptySet()) })
    }
    f.place?.let { out.add(CondChip("place", placeText(it)) { x -> x.copy(place = null) }) }
    for (k in RangeKind.entries) {
        val r = k.get(f) ?: continue
        out.add(CondChip("r${k.name}", rangeText(k, r)) { x -> k.set(x, null) })
    }
    if (f.mode != ModeFilter.ALL) out.add(CondChip("mode", filterModeLabel(f.mode)) { it.copy(mode = ModeFilter.ALL) })
    if (f.source != SourceFilter.ALL) {
        val fm = if (f.source == SourceFilter.IMPORTED && f.formats.isNotEmpty()) {
            "（" + ActivityFormat.entries.filter { it.code in f.formats }.joinToString("・") { it.label } + "）"
        } else ""
        out.add(CondChip("src", sourceLabel(f.source) + fm) { it.copy(source = SourceFilter.ALL, formats = emptySet()) })
    }
    if (f.withPhotos) out.add(CondChip("photo", stringResource(R.string.filter_photos)) { it.copy(withPhotos = false) })
    if (f.name.isNotBlank()) out.add(CondChip("name", stringResource(R.string.filter_name_chip, f.name.trim())) { it.copy(name = "") })
    return out
}

/** 条件の要約（まとめの画面の上に出す）。 */
@Composable
fun filterSummaryText(f: TrackFilter): String = conditionChips(f).joinToString(" / ") { it.text }

@Composable
private fun seasonShort(s: Season) = stringResource(
    when (s) {
        Season.SPRING -> R.string.season_spring_short
        Season.SUMMER -> R.string.season_summer_short
        Season.AUTUMN -> R.string.season_autumn_short
        Season.WINTER -> R.string.season_winter_short
    },
)

@Composable
private fun dayPartShort(d: DayPart) = stringResource(
    when (d) {
        DayPart.MORNING -> R.string.daypart_morning_short
        DayPart.DAYTIME -> R.string.daypart_day_short
        DayPart.EVENING -> R.string.daypart_evening_short
        DayPart.NIGHT -> R.string.daypart_night_short
    },
)

/**
 * 記録の一覧の上の帯: ［絞り込み（n）］・並べ替え・件数、条件のチップ（× で外す）と［すべて解除］、場所の索引を作っている途中の知らせ。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackFilterBar(vm: MainViewModel, result: BrowseResult?, onOpenFilter: () -> Unit, modifier: Modifier = Modifier) {
    val browser = vm.app.trackBrowser
    val filter by browser.filter.collectAsState()
    val sort by browser.sort.collectAsState()
    val geo by vm.app.geoIndex.state.collectAsState()
    var sortMenu by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val n = filter.count
            FilledTonalButton(onClick = onOpenFilter) {
                Icon(Icons.Filled.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (n > 0) stringResource(R.string.filter_button_n, n) else stringResource(R.string.filter_button))
            }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_label), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sortShortLabel(sort), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    TrackSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(s), fontWeight = if (s == sort) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                            onClick = { sortMenu = false; browser.setSort(s) },
                        )
                    }
                }
            }
            if (result != null) {
                Text(
                    if (filter.isActive) stringResource(R.string.filter_count, result.all.size, result.items.size) else stringResource(R.string.filter_count_all, result.all.size),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        val chips = conditionChips(filter)
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (c in chips) {
                    InputChip(
                        selected = true,
                        onClick = onOpenFilter,
                        label = { Text(c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.filter_remove, c.text),
                                modifier = Modifier.size(20.dp).clickable { browser.update(c.remove) },
                            )
                        },
                    )
                }
                TextButton(onClick = { browser.clear() }) { Text(stringResource(R.string.filter_clear_all)) }
            }
        }
        if (filter.place != null && geo.building) {
            Text(
                stringResource(R.string.filter_geo_pending, geo.built, geo.toBuild),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 名前の検索欄（一覧の上。虫眼鏡で開く。入れたそばから絞り込む）。 */
@Composable
fun TrackNameSearchField(vm: MainViewModel, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val filter by vm.app.trackBrowser.filter.collectAsState()
    var text by remember { mutableStateOf(filter.name) }
    LaunchedEffect(filter.name) { if (filter.name != text) text = filter.name }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; vm.app.trackBrowser.update { f -> f.copy(name = it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
        placeholder = { Text(stringResource(R.string.filter_name_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            IconButton(onClick = {
                text = ""
                vm.app.trackBrowser.update { f -> f.copy(name = "") }
                onClose()
            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
        },
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/**
 * 条件を選ぶ画面。選んだそばから一覧の条件になり保存される（下のボタンは件数を見せて一覧へ戻るだけ）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackFilterScreen(vm: MainViewModel, onBack: () -> Unit, onPickPlace: () -> Unit) {
    val browser = vm.app.trackBrowser
    val f by browser.filter.collectAsState()
    val sort by browser.sort.collectAsState()
    val result by browser.result.collectAsState()
    val all = result?.all.orEmpty()
    val weekdays = stringArrayResource(R.array.weekday_short)
    var picking by remember { mutableStateOf(false) }
    fun set(x: (TrackFilter) -> TrackFilter) = browser.update(x)

    // 選べる年（記録のある年。新しい順）と件数
    val years = remember(all) {
        val cal = Calendar.getInstance()
        all.groupingBy { cal.timeInMillis = it.startedAt; cal.get(Calendar.YEAR) }.eachCount().toSortedMap(compareByDescending { it })
    }
    val tops = remember(all) {
        mapOf(
            RangeKind.DISTANCE to TrackFilters.niceTop((all.maxOfOrNull { it.distanceM } ?: 0.0) / 1000.0, RangeKind.DISTANCE.topStep, RangeKind.DISTANCE.minTop),
            RangeKind.AVG to TrackFilters.niceTop(all.maxOfOrNull { TrackFilters.avgKmh(it) } ?: 0.0, RangeKind.AVG.topStep, RangeKind.AVG.minTop),
            RangeKind.MAX to TrackFilters.niceTop((all.maxOfOrNull { it.maxSpeedMps } ?: 0.0) * 3.6, RangeKind.MAX.topStep, RangeKind.MAX.minTop),
            RangeKind.MOVING to TrackFilters.niceTop((all.maxOfOrNull { it.movingMs } ?: 0L) / 60_000.0, RangeKind.MOVING.topStep, RangeKind.MOVING.minTop),
            RangeKind.ASCENT to TrackFilters.niceTop(all.maxOfOrNull { it.ascentM } ?: 0.0, RangeKind.ASCENT.topStep, RangeKind.ASCENT.minTop),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.filter_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = { if (f.isActive) TextButton(onClick = { browser.clear() }) { Text(stringResource(R.string.filter_clear_all)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result?.let { if (f.isActive) stringResource(R.string.filter_count, it.all.size, it.items.size) else stringResource(R.string.filter_count_all, it.all.size) } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onBack) { Text(stringResource(R.string.filter_show, result?.items?.size ?: 0)) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 名前
            var nameText by remember { mutableStateOf(f.name) }
            LaunchedEffect(f.name) { if (f.name != nameText) nameText = f.name }
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it; set { x -> x.copy(name = it) } },
                label = { Text(stringResource(R.string.filter_name)) },
                placeholder = { Text(stringResource(R.string.filter_name_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 時期 ----
            Section(stringResource(R.string.filter_section_period))
            SubTitle(stringResource(R.string.filter_year))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((y, n) in years) {
                    FilterChip(
                        selected = f.year == y,
                        onClick = { set { it.copy(year = if (it.year == y) null else y, fromDay = null, toDay = null) } },
                        label = { Text(stringResource(R.string.filter_year_count, y, n)) },
                    )
                }
            }
            SubTitle(stringResource(if (f.year != null) R.string.filter_month_of_year else R.string.filter_month_every, f.year ?: 0))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in 1..12) {
                    FilterChip(
                        selected = f.month == m,
                        onClick = { set { it.copy(month = if (it.month == m) null else m, fromDay = null, toDay = null) } },
                        label = { Text(stringResource(R.string.filter_month_n, m)) },
                    )
                }
            }
            SubTitle(stringResource(R.string.filter_range))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = true }) {
                    Text(
                        if (f.fromDay != null || f.toDay != null) stringResource(R.string.filter_range_value, f.fromDay?.let { dayText(it) } ?: "", f.toDay?.let { dayText(it) } ?: "")
                        else stringResource(R.string.filter_range_pick),
                    )
                }
                if (f.fromDay != null || f.toDay != null) {
                    IconButton(onClick = { set { it.copy(fromDay = null, toDay = null) } }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.filter_range_clear)) }
                }
            }
            Text(stringResource(R.string.filter_period_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SubTitle(stringResource(R.string.filter_season))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Season.entries) {
                    FilterChip(selected = s in f.seasons, onClick = { set { it.copy(seasons = it.seasons.toggle(s)) } }, label = { Text(seasonLabel(s)) })
                }
            }
            SubTitle(stringResource(R.string.filter_weekday))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 1..7) {
                    FilterChip(selected = d in f.weekdays, onClick = { set { it.copy(weekdays = it.weekdays.toggle(d)) } }, label = { Text(weekdays[d - 1]) })
                }
                FilterChip(
                    selected = f.weekdays == setOf(1, 7),
                    onClick = { set { it.copy(weekdays = if (it.weekdays == setOf(1, 7)) emptySet() else setOf(1, 7)) } },
                    label = { Text(stringResource(R.string.filter_weekend)) },
                )
            }
            SubTitle(stringResource(R.string.filter_daypart))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in DayPart.entries) {
                    FilterChip(selected = d in f.dayParts, onClick = { set { it.copy(dayParts = it.dayParts.toggle(d)) } }, label = { Text(dayPartLabel(d)) })
                }
            }

            // ---- 場所 ----
            Section(stringResource(R.string.filter_section_place))
            val place = f.place
            Text(if (place != null) placeText(place) else stringResource(R.string.filter_place_none), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = onPickPlace) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (place != null) R.string.filter_place_change else R.string.filter_place_pick))
                }
                if (place != null) TextButton(onClick = { set { it.copy(place = null) } }) { Text(stringResource(R.string.filter_place_remove)) }
            }
            if (place != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in PlaceMatch.entries) {
                        FilterChip(selected = place.match == m, onClick = { set { it.copy(place = place.copy(match = m)) } }, label = { Text(placeMatchLabel(m)) })
                    }
                }
            }
            Text(stringResource(R.string.filter_place_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- 距離・速度・時間・標高 ----
            Section(stringResource(R.string.filter_section_stats))
            for (k in RangeKind.entries) {
                RangeFilterRow(k, k.get(f), tops[k] ?: k.minTop, onChange = { r -> set { k.set(it, r) } })
            }

            // ---- 種類 ----
            Section(stringResource(R.string.filter_section_kind))
            SubTitle(stringResource(R.string.filter_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in ModeFilter.entries) FilterChip(selected = f.mode == m, onClick = { set { it.copy(mode = m) } }, label = { Text(filterModeLabel(m)) })
            }
            SubTitle(stringResource(R.string.filter_source))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in SourceFilter.entries) {
                    FilterChip(
                        selected = f.source == s,
                        onClick = { set { it.copy(source = s, formats = if (s == SourceFilter.IMPORTED) it.formats else emptySet()) } },
                        label = { Text(sourceLabel(s)) },
                    )
                }
            }
            if (f.source == SourceFilter.IMPORTED) {
                SubTitle(stringResource(R.string.filter_formats))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (fm in ActivityFormat.entries) {
                        FilterChip(selected = fm.code in f.formats, onClick = { set { it.copy(formats = it.formats.toggle(fm.code)) } }, label = { Text(fm.label) })
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(selected = f.withPhotos, onClick = { set { it.copy(withPhotos = !it.withPhotos) } }, label = { Text(stringResource(R.string.filter_photos)) })
            }

            // ---- 並べ替え ----
            Section(stringResource(R.string.sort_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in TrackSort.entries) FilterChip(selected = sort == s, onClick = { browser.setSort(s) }, label = { Text(sortLabel(s)) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = f.fromDay?.let { TrackFilters.pickerMsOfDay(it) },
            initialSelectedEndDateMillis = f.toDay?.let { TrackFilters.pickerMsOfDay(it) },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    val s = state.selectedStartDateMillis
                    if (s != null) {
                        // DatePicker は UTC の 0 時 = その日付。日数にして端末のタイムゾーンの日付と比べる
                        val from = TrackFilters.dayOfPickerMs(s)
                        val to = TrackFilters.dayOfPickerMs(state.selectedEndDateMillis ?: s)
                        set { it.copy(fromDay = from, toDay = to, year = null, month = null) }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(500.dp))
        }
    }
}

@Composable
fun placeMatchLabel(m: PlaceMatch) = stringResource(
    when (m) {
        PlaceMatch.PASS -> R.string.place_match_pass
        PlaceMatch.START -> R.string.place_match_start
        PlaceMatch.END -> R.string.place_match_end
    },
)

/** 入力中の範囲（下限 > 上限でも入れ替えない。入れ替えると打っている途中の数が動くため。そのときは何も当てはまらない）。 */
private fun typedRange(min: Double?, max: Double?): NumRange? = if (min == null && max == null) null else NumRange(min, max)

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

@Composable
private fun Section(text: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

/**
 * 範囲の欄（スライダー＋下限・上限の数値）。スライダーは離したときに条件にする。端まで動かした側は「区切りなし」。
 * 数値は入れたそばから条件にする（空なら区切りなし）。
 */
@Composable
private fun RangeFilterRow(kind: RangeKind, value: NumRange?, top: Double, onChange: (NumRange?) -> Unit) {
    val lo = (value?.min ?: 0.0).coerceIn(0.0, top).toFloat()
    val hi = (value?.max ?: top).coerceIn(0.0, top).toFloat()
    var slider by remember(value, top) { mutableStateOf(lo..hi) }
    var minText by remember { mutableStateOf(value?.min?.let { num(it) } ?: "") }
    var maxText by remember { mutableStateOf(value?.max?.let { num(it) } ?: "") }
    LaunchedEffect(value) {
        if (parseNum(minText) != value?.min) minText = value?.min?.let { num(it) } ?: ""
        if (parseNum(maxText) != value?.max) maxText = value?.max?.let { num(it) } ?: ""
    }
    fun snap(v: Float): Double = (v / kind.step).roundToLong() * kind.step
    val unit = stringResource(kind.unitRes)
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(kind.labelRes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            val s0 = snap(slider.start)
            val s1 = snap(slider.endInclusive)
            Text(
                when {
                    s0 <= 0.0 && s1 >= top -> stringResource(R.string.filter_range_any)
                    s1 >= top -> stringResource(R.string.filter_range_ge, num(s0), unit)
                    s0 <= 0.0 -> stringResource(R.string.filter_range_le, num(s1), unit)
                    else -> stringResource(R.string.filter_range_span, num(s0), num(s1), unit)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // 画面を縦にスクロールしようとしてスライダーに触れた（押した所へつまみが跳ぶ）だけなら、条件にせず元に戻す
        var vertical by remember { mutableStateOf(false) }
        RangeSlider(
            value = slider,
            onValueChange = { slider = it },
            valueRange = 0f..top.toFloat(),
            onValueChangeFinished = {
                if (vertical) {
                    slider = lo..hi
                } else {
                    val a = snap(slider.start)
                    val b = snap(slider.endInclusive)
                    onChange(NumRange.of(a.takeIf { it > 0.0 }, b.takeIf { it < top }))
                }
            },
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    vertical = false
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        val d = c.position - c.previousPosition
                        dx += d.x
                        dy += d.y
                        if (!vertical && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) vertical = true
                        if (!c.pressed) break
                    }
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = minText,
                onValueChange = { t -> minText = t; if (t.isBlank() || parseNum(t) != null) onChange(typedRange(parseNum(t), value?.max)) },
                label = { Text(stringResource(R.string.filter_min)) },
                suffix = { Text(unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Text("〜")
            OutlinedTextField(
                value = maxText,
                onValueChange = { t -> maxText = t; if (t.isBlank() || parseNum(t) != null) onChange(typedRange(value?.min, parseNum(t))) },
                label = { Text(stringResource(R.string.filter_max)) },
                suffix = { Text(unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
