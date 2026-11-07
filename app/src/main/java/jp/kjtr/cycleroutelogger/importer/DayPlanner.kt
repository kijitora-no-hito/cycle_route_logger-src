package jp.kjtr.cycleroutelogger.importer

import jp.kjtr.cycleroutelogger.geo.Geo
import java.util.TimeZone
import java.util.TreeMap

/** 1 日分の材料の 1 片（ある線の、重複と日付の境目で切った残り）。点は [PointStore] の [first] から [count] 個。 */
data class Piece(val run: Int, val file: Int, val format: ActivityFormat, val first: Long, val count: Int, val start: Long, val end: Long, val activity: Int = -1)

/**
 * 取り込みの 1 単位（0.10.1）: 1 つのファイルの中で、あいだが [DayPlanner.ACTIVITY_GAP_MS] 以下の線のまとまり（FIT・Strava の 1 ファイルなら普通は 1 つ）。
 * 重複（同じ走行・取り込み済み・既存の記録と同じ）はこの単位で判定する。
 */
data class Activity(
    val id: Int,
    val file: Int,
    val format: ActivityFormat,
    val sport: Sport,
    val runs: List<RunInfo>,
) {
    val start: Long get() = runs.first().start
    val end: Long get() = runs.last().end
    val durationMs: Long get() = end - start
    val points: Int get() = runs.sumOf { it.count }
}

/** 比べるための既存の記録（自分で記録したもの・前に取り込んだもの）。[points] は時刻の順。 */
class ExistingTrack(
    val id: Long,
    val name: String,
    val start: Long,
    val end: Long,
    /** ファイルから取り込んだ記録か（その日の新しい走行を区間として足せる）。 */
    val imported: Boolean,
    val points: PointBlock,
)

/** 1 つの活動を、時間の重なる相手（ほかの活動・既存の記録）と比べた結果。 */
enum class TrackMatch {
    /** 重なる時間の位置が近い（[DayPlanner.SAME_MEDIAN_M] 以内）。重なりが十分なら同じ走行。 */
    CLOSE,
    /** 重なる時間に別の場所（[DayPlanner.FAR_MEDIAN_M] より遠い）。両方残す。 */
    DIFFERENT,
    /** 比べられる点が少ない・どちらとも言えない。重なる時間の点だけ捨てる（0.10.0 と同じ）。 */
    UNKNOWN,
}

/** 重複の判定の記録（確認画面の日の詳細・全体の行に出す）。ファイルは [FileReport] の添字。 */
sealed interface DupNote {
    /** 同じ走行が複数のファイルにあった → [kept] を丸ごと採用、[dropped] は使わない。 */
    data class SameRide(val kept: Int, val dropped: List<Int>, val reason: KeepReason) : DupNote
    /** 時間が重なるが別の場所の記録（ほかのファイル・既存の記録の名前）。 */
    data class Elsewhere(val file: Int, val other: String) : DupNote
}

enum class KeepReason { LONGER, FORMAT, MORE_POINTS }

/** 1 日分（端末のタイムゾーンの日付）。[pieces] は時刻の順（重ならない）。 */
data class DayPlan(
    /** 1970-01-01 からの日数（その地の日付）。 */
    val day: Long,
    val pieces: List<Piece>,
    /** 同じ日の何番目の記録か（0 = その日の記録。時間が重なる別の場所の走行は 1 以降の別の記録）。 */
    val slot: Int = 0,
    /** 前に取り込んだその日の記録に区間として足すときの記録の id（null なら新しい記録）。 */
    val appendTo: Long? = null,
    val appendToName: String? = null,
    val notes: List<DupNote> = emptyList(),
    /** この日に入る活動の取り込みの印（保存したら [ActivityKeys] として覚える）。 */
    val keys: List<String> = emptyList(),
) {
    /** 確認画面の選択に使うキー（日と slot）。 */
    val key: Long get() = day * SLOTS + slot
    val start: Long get() = pieces.first().start
    val end: Long get() = pieces.last().end
    val points: Int get() = pieces.sumOf { it.count }
    val files: Set<Int> get() = pieces.mapTo(LinkedHashSet()) { it.file }
    val formats: Set<ActivityFormat> get() = pieces.mapTo(sortedSetOf(compareBy { it.rank })) { it.format }

    companion object {
        const val SLOTS = 16L
    }
}

/** 計画の結果（日ごとの材料と、全体の重複の数）。 */
data class PlanResult(
    val days: List<DayPlan>,
    /** 取り込み済み（前に取り込んで保存した活動と同じ印）で黙って外した活動の数。 */
    val alreadyImported: Int = 0,
    /** 既存の記録と同じ走行なので外した活動（ファイルの添字 → 記録の名前）。 */
    val sameAsExisting: List<Pair<Int, String>> = emptyList(),
    /** ほかのファイルと同じ走行なので外した活動の数。 */
    val sameRideDropped: Int = 0,
)

/**
 * 読んだ線（[RunInfo]）を日付ごとにまとめる（純ロジック。単体テスト: ActivityImportTest）。
 *
 * 0.10.1〜 重複は**活動（[Activity]）単位**で見る:
 * 1. **取り込み済み**: 活動の印（[ActivityKeys]: ファイルの内容の SHA-256・FIT の file_id・Strava の Activity ID ＋ 始まりの時刻）が
 *    前に保存したものと同じなら黙って外す。
 * 2. **既存の記録と同じ走行**: 時間が重なる既存の記録と軌跡を比べ（[compare]）、近くて重なりが活動の半分以上なら外す。
 *    別の場所なら両方残す（別の記録）。どちらとも言えない・重なりが少しなら、重なる時間の点だけ捨てる。
 * 3. **複数の機器の同じ走行**: 活動を優先の順（[score]: 時間の長さに形式の重み（FIT > TCX > GPX > KML）→ 点の多い順）に見て、
 *    先に採った活動と時間が重なり、軌跡が近く、重なりが短い方の半分以上なら**丸ごと**捨てる（端の数分の切れ端を残さない）。
 *    別の場所なら両方残し、遅い方は同じ日の別の記録（slot 1〜）。どちらとも言えないときは重なる時間の点だけ捨てる（前後 [DUP_MARGIN_MS]）。
 * 4. **日付で切る**: [tz] の日付が変わるところで切る（0 時をまたぐ 1 本の走行も 2 日に分ける）。夏時間も点ごとのオフセットで見る。
 * 5. 同じ日の片を時刻の順に並べて 1 日分にする（片ごとに区間を分け、線をつながない）。前に取り込んだその日の記録があれば、そこへ足す（[DayPlan.appendTo]）。
 *    自分で記録したものしか無い日は、取り込んだ分だけの記録を別に作る（自分の記録には手を入れない）。
 */
object DayPlanner {
    /** これ以上あいたら同じファイルでも線を分ける [ms]（10 分）。 */
    const val GAP_SPLIT_MS = 10 * 60_000L
    /** 同じファイルの線を 1 つの活動とみなすあいだ [ms]（2 時間。これより離れた線は別の活動）。 */
    const val ACTIVITY_GAP_MS = 2 * 3_600_000L
    /** 重複とみなす時間帯の前後の余裕 [ms]（機器ごとの記録の始まり・終わりの数秒の差を吸収する）。 */
    const val DUP_MARGIN_MS = 30_000L
    /** 同じ走行とみなす、重なる時間の同じ時刻の位置の距離の中央値 [m]。 */
    const val SAME_MEDIAN_M = 50.0
    /** これより遠ければ別の場所 [m]。 */
    const val FAR_MEDIAN_M = 200.0
    /** 同じ走行とみなす重なりの割合（短い方の時間に対して）。 */
    const val SAME_OVERLAP = 0.5
    /** 位置を比べるとき、前後の点がこれ以上あいていたらその時刻は使わない [ms]。 */
    private const val COMPARE_GAP_MS = 120_000L
    private const val COMPARE_SAMPLES = 200
    private const val COMPARE_MIN_SAMPLES = 5
    const val DAY_MS = 86_400_000L

    fun dayOf(timeMs: Long, tz: TimeZone): Long = Math.floorDiv(timeMs + tz.getOffset(timeMs), DAY_MS)

    /** 0.10.0 と同じ呼び方（既存の記録・取り込み済みの印なし）。 */
    fun plan(
        runs: List<RunInfo>,
        store: PointStore,
        tz: TimeZone,
        include: (RunInfo) -> Boolean = { true },
        check: () -> Unit = {},
    ): List<DayPlan> = planAll(activities(runs.filter(include)), store, tz, check = check).days

    /** 線を活動にまとめる（ファイルごと、あいだが [ACTIVITY_GAP_MS] 以下の線を続ける）。 */
    fun activities(runs: List<RunInfo>): List<Activity> {
        val out = ArrayList<Activity>()
        for ((_, list) in runs.groupBy { it.file }) {
            var cur = ArrayList<RunInfo>()
            for (r in list.sortedBy { it.start }) {
                if (cur.isNotEmpty() && r.start - cur.last().end > ACTIVITY_GAP_MS) {
                    out.add(Activity(out.size, cur[0].file, cur[0].format, sportOf(cur), cur))
                    cur = ArrayList()
                }
                cur.add(r)
            }
            if (cur.isNotEmpty()) out.add(Activity(out.size, cur[0].file, cur[0].format, sportOf(cur), cur))
        }
        return out
    }

    private fun sportOf(runs: List<RunInfo>): Sport = runs.firstOrNull { it.sport != Sport.UNKNOWN }?.sport ?: Sport.UNKNOWN

    /** 活動の印（[fileKeys] はファイルの添字 → そのファイルの印（"sha:…" など））。 */
    fun keysOf(a: Activity, fileKeys: (Int) -> List<String>): List<String> = fileKeys(a.file).map { ActivityKeys.of(it, a.start) }

    /** 優先の順の点数（大きいほど優先）: 時間の長さ × 形式の重み（FIT +10%・TCX +5%・GPX ±0・KML −5%）。 */
    fun score(a: Activity): Double = a.durationMs * when (a.format) {
        ActivityFormat.FIT -> 1.10
        ActivityFormat.TCX -> 1.05
        ActivityFormat.GPX -> 1.0
        ActivityFormat.KML -> 0.95
    }

    fun planAll(
        acts: List<Activity>,
        store: PointStore,
        tz: TimeZone,
        existing: List<ExistingTrack> = emptyList(),
        importedKeys: Set<String> = emptySet(),
        fileKeys: (Int) -> List<String> = { emptyList() },
        fileLabel: (Int) -> String = { "#$it" },
        check: () -> Unit = {},
    ): PlanResult {
        // 1. 取り込み済み
        var already = 0
        val keyOf = HashMap<Int, List<String>>()
        val fresh = acts.filter { a ->
            val k = keysOf(a, fileKeys)
            keyOf[a.id] = k
            val dup = k.any { it in importedKeys }
            if (dup) already++
            !dup
        }
        val blocks = HashMap<Int, PointBlock>()
        fun pts(a: Activity): PointBlock = blocks.getOrPut(a.id) { concat(a.runs.map { store.read(it.first, it.count) }) }

        // 2. 既存の記録と比べる
        val sameAsExisting = ArrayList<Pair<Int, String>>()
        val trimByExisting = HashMap<Int, MutableList<LongArray>>() // 活動 → 捨てる時間帯（既存の記録の続いた時間）
        val elsewhere = HashMap<Int, MutableList<DupNote.Elsewhere>>()
        val existingIntervals = HashMap<Long, List<LongArray>>()
        fun intervalsOf(t: ExistingTrack) = existingIntervals.getOrPut(t.id) { intervals(t.points.time) }
        val afterExisting = fresh.filter { a ->
            check()
            var keep = true
            for (t in existing) {
                if (t.end < a.start || t.start > a.end) continue
                val c = compare(pts(a), t.points)
                if (c.overlapMs <= 0) continue
                when {
                    c.match == TrackMatch.CLOSE && c.overlapMs >= SAME_OVERLAP * a.durationMs -> {
                        sameAsExisting.add(a.file to t.name)
                        keep = false
                    }
                    c.match == TrackMatch.DIFFERENT -> elsewhere.getOrPut(a.id) { ArrayList() }.add(DupNote.Elsewhere(a.file, t.name))
                    else -> trimByExisting.getOrPut(a.id) { ArrayList() }.addAll(intervalsOf(t))
                }
                if (!keep) break
            }
            if (!keep) blocks.remove(a.id)
            keep
        }

        // 3. 活動どうし（優先の順）
        val order = afterExisting.sortedWith(compareByDescending<Activity> { score(it) }.thenByDescending { it.points }.thenBy { it.format.rank }.thenBy { it.id })
        val accepted = ArrayList<Activity>()
        val keptIntervals = HashMap<Int, List<LongArray>>() // 採った活動 → 実際に使った時間帯
        val dupsOf = HashMap<Int, MutableList<Int>>() // 採った活動 → 同じ走行で捨てたファイル
        val dupActs = HashMap<Int, MutableList<Int>>() // 採った活動 → 同じ走行で捨てた活動（印は一緒に覚える）
        val reasonOf = HashMap<Int, KeepReason>()
        val conflictWith = HashMap<Int, MutableSet<Int>>() // 別の場所で時間が重なる、先に採った活動
        val pieces = ArrayList<Piece>()
        var sameRideDropped = 0
        for (a in order) {
            check()
            val trim = ArrayList<LongArray>(trimByExisting[a.id].orEmpty())
            val trimmedBy = ArrayList<Int>() // 重なる点を捨てる相手（先に採った活動）
            var dupOf: Activity? = null
            for (b in accepted) {
                if (b.end < a.start - DUP_MARGIN_MS || b.start > a.end + DUP_MARGIN_MS) continue
                val c = compare(pts(a), pts(b))
                when {
                    c.overlapMs > 0 && c.match == TrackMatch.CLOSE && c.overlapMs >= SAME_OVERLAP * minOf(a.durationMs, b.durationMs) -> { dupOf = b; break }
                    c.overlapMs > 0 && c.match == TrackMatch.DIFFERENT -> {
                        conflictWith.getOrPut(a.id) { HashSet() }.add(b.id)
                        elsewhere.getOrPut(a.id) { ArrayList() }.add(DupNote.Elsewhere(a.file, fileLabel(b.file)))
                    }
                    else -> {
                        trim.addAll(keptIntervals[b.id].orEmpty())
                        trimmedBy.add(b.id)
                    }
                }
            }
            if (dupOf != null) {
                dupsOf.getOrPut(dupOf.id) { ArrayList() }.add(a.file)
                dupActs.getOrPut(dupOf.id) { ArrayList() }.add(a.id)
                if (dupOf.id !in reasonOf) reasonOf[dupOf.id] = reason(dupOf, a)
                sameRideDropped++
                blocks.remove(a.id)
                continue
            }
            accepted.add(a)
            val blocked = TreeMap<Long, Long>()
            for (iv in trim) addInterval(blocked, iv[0], iv[1])
            val used = ArrayList<LongArray>()
            for (run in a.runs) {
                val times = store.read(run.first, run.count).time
                for (r in uncoveredRanges(times, blocked)) {
                    if (r.last - r.first + 1 < 2) continue
                    used.add(longArrayOf(times[r.first], times[r.last]))
                    // 日付で切る
                    var s = r.first
                    var day = dayOf(times[s], tz)
                    for (i in r.first + 1..r.last) {
                        val d = dayOf(times[i], tz)
                        if (d != day) {
                            pieces.add(Piece(run.id, run.file, run.format, run.first + s, i - s, times[s], times[i - 1], a.id))
                            s = i
                            day = d
                        }
                    }
                    pieces.add(Piece(run.id, run.file, run.format, run.first + s, r.last + 1 - s, times[s], times[r.last], a.id))
                }
            }
            keptIntervals[a.id] = used
            // 重なりで点が全部なくなった活動は、相手の日の印と一緒に覚える（2 回目の取り込みで相手が外れたときに出てこないように）
            if (used.isEmpty()) trimmedBy.firstOrNull { !keptIntervals[it].isNullOrEmpty() }?.let { dupActs.getOrPut(it) { ArrayList() }.add(a.id) }
            blocks.remove(a.id) // 比べるのは時間の近い活動だけなので、使い終わった点は捨ててよい（必要ならまた読む）
        }

        // 4〜5. 日ごと・slot ごと
        val byId = acts.associateBy { it.id }
        val rank = order.withIndex().associate { it.value.id to it.index }
        val days = ArrayList<DayPlan>()
        for ((day, list) in pieces.groupBy { dayOf(it.start, tz) }.toSortedMap()) {
            // その日の前に取り込んだ記録（最初のもの）。その続いた時間は slot 0 を使っているとみなす
            val target = existing.filter { it.imported && dayOf(it.start, tz) == day }.minByOrNull { it.start }
            val slots = ArrayList<MutableList<Piece>>()
            val slotBusy = ArrayList<MutableList<LongArray>>()
            slots.add(ArrayList()); slotBusy.add(ArrayList(target?.let { intervalsOf(it) }.orEmpty()))
            for ((_, ps) in list.groupBy { it.activity }.entries.sortedBy { rank[it.key] ?: Int.MAX_VALUE }) {
                val span = ps.map { longArrayOf(it.start, it.end) }
                var slot = 0
                while (slot < slots.size && slotBusy[slot].any { b -> span.any { s -> s[0] <= b[1] && s[1] >= b[0] } }) slot++
                if (slot >= DayPlan.SLOTS) slot = (DayPlan.SLOTS - 1).toInt()
                while (slots.size <= slot) { slots.add(ArrayList()); slotBusy.add(ArrayList()) }
                slots[slot].addAll(ps)
                slotBusy[slot].addAll(span)
            }
            slots.forEachIndexed { slot, ps ->
                if (ps.isEmpty()) return@forEachIndexed
                val aids = ps.map { it.activity }.distinct()
                val notes = ArrayList<DupNote>()
                for (aid in aids) {
                    dupsOf[aid]?.let { notes.add(DupNote.SameRide(byId[aid]!!.file, it, reasonOf[aid] ?: KeepReason.LONGER)) }
                    elsewhere[aid]?.let { notes.addAll(it) }
                }
                val append = if (slot == 0) target else null
                days.add(
                    DayPlan(
                        day = day,
                        pieces = ps.sortedWith(compareBy({ it.start }, { it.run })),
                        slot = slot,
                        appendTo = append?.id,
                        appendToName = append?.name,
                        notes = notes,
                        // 同じ走行で捨てた活動の印も覚える（次に同じ書き出しを読んだとき、それだけ取り込まれないように）
                        keys = (aids + aids.flatMap { dupActs[it].orEmpty() }).flatMap { keyOf[it].orEmpty() }.distinct(),
                    ),
                )
            }
        }
        return PlanResult(days, already, sameAsExisting, sameRideDropped)
    }

    private fun reason(kept: Activity, dropped: Activity): KeepReason = when {
        kept.durationMs > dropped.durationMs * 1.1 -> KeepReason.LONGER
        kept.format.rank < dropped.format.rank -> KeepReason.FORMAT
        kept.points > dropped.points -> KeepReason.MORE_POINTS
        else -> KeepReason.LONGER
    }

    /** 比べた結果（[overlapMs] は時間の重なり。0 以下なら重ならない）。 */
    data class Comparison(val match: TrackMatch, val overlapMs: Long, val medianM: Double, val samples: Int)

    /**
     * 2 つの軌跡を、時間の重なる範囲の同じ時刻の位置（前後の点から線形に補間）で比べる。
     * 重なりを最大 [COMPARE_SAMPLES] の時刻に分け、どちらかの点が [COMPARE_GAP_MS] 以上あいている時刻は使わない。
     * 距離の中央値が [SAME_MEDIAN_M] 以内なら [TrackMatch.CLOSE]、[FAR_MEDIAN_M] より遠ければ [TrackMatch.DIFFERENT]。
     */
    fun compare(a: PointBlock, b: PointBlock): Comparison {
        if (a.size < 2 || b.size < 2) return Comparison(TrackMatch.UNKNOWN, 0, Double.NaN, 0)
        val from = maxOf(a.time[0], b.time[0])
        val to = minOf(a.time[a.size - 1], b.time[b.size - 1])
        val overlap = to - from
        if (overlap <= 0) return Comparison(TrackMatch.UNKNOWN, overlap, Double.NaN, 0)
        val n = COMPARE_SAMPLES
        val d = ArrayList<Double>(n + 1)
        val pa = DoubleArray(2)
        val pb = DoubleArray(2)
        for (k in 0..n) {
            val t = from + overlap * k / n
            if (!at(a, t, pa) || !at(b, t, pb)) continue
            d.add(Geo.distanceM(pa[0], pa[1], pb[0], pb[1]))
        }
        if (d.size < COMPARE_MIN_SAMPLES) return Comparison(TrackMatch.UNKNOWN, overlap, Double.NaN, d.size)
        d.sort()
        val med = if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        val m = when {
            med <= SAME_MEDIAN_M -> TrackMatch.CLOSE
            med > FAR_MEDIAN_M -> TrackMatch.DIFFERENT
            else -> TrackMatch.UNKNOWN
        }
        return Comparison(m, overlap, med, d.size)
    }

    /** 時刻 [t] の位置（前後の点の線形補間）。点があいていれば false。 */
    private fun at(p: PointBlock, t: Long, out: DoubleArray): Boolean {
        var i = p.time.binarySearch(t)
        if (i >= 0) { out[0] = p.lat[i]; out[1] = p.lon[i]; return true }
        i = -i - 1 // t より後の最初の点
        if (i <= 0 || i >= p.size) return false
        val t0 = p.time[i - 1]
        val t1 = p.time[i]
        if (t1 - t0 > COMPARE_GAP_MS) return false
        val f = (t - t0).toDouble() / (t1 - t0)
        out[0] = p.lat[i - 1] + (p.lat[i] - p.lat[i - 1]) * f
        out[1] = p.lon[i - 1] + (p.lon[i] - p.lon[i - 1]) * f
        return true
    }

    /** 時刻の並びを、[GAP_SPLIT_MS] 以上のあきで切った続いた時間帯 [start, end]。 */
    fun intervals(times: LongArray): List<LongArray> {
        if (times.isEmpty()) return emptyList()
        val out = ArrayList<LongArray>()
        var s = times[0]
        for (i in 1 until times.size) {
            if (times[i] - times[i - 1] >= GAP_SPLIT_MS) {
                out.add(longArrayOf(s, times[i - 1]))
                s = times[i]
            }
        }
        out.add(longArrayOf(s, times[times.size - 1]))
        return out
    }

    /** 点をつなげる（活動の線を 1 つの並びに。時刻の順）。 */
    private fun concat(list: List<PointBlock>): PointBlock {
        if (list.size == 1) return list[0]
        val n = list.sumOf { it.size }
        val t = LongArray(n); val la = DoubleArray(n); val lo = DoubleArray(n); val e = FloatArray(n); val s = FloatArray(n)
        var o = 0
        for (b in list) {
            System.arraycopy(b.time, 0, t, o, b.size); System.arraycopy(b.lat, 0, la, o, b.size); System.arraycopy(b.lon, 0, lo, o, b.size)
            System.arraycopy(b.ele, 0, e, o, b.size); System.arraycopy(b.speed, 0, s, o, b.size)
            o += b.size
        }
        return PointBlock(t, la, lo, e, s)
    }

    /** [blocked] の時間帯（前後 [DUP_MARGIN_MS] を含む）に入らない、続いた点の並び（添字の範囲）。 */
    private fun uncoveredRanges(times: LongArray, blocked: TreeMap<Long, Long>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var from = -1
        for (i in times.indices) {
            val covered = isCovered(blocked, times[i])
            if (!covered) {
                if (from < 0) from = i
            } else if (from >= 0) {
                ranges.add(from until i)
                from = -1
            }
        }
        if (from >= 0) ranges.add(from until times.size)
        return ranges
    }

    private fun isCovered(kept: TreeMap<Long, Long>, t: Long): Boolean {
        if (kept.isEmpty()) return false
        val e = kept.floorEntry(t + DUP_MARGIN_MS) ?: return false
        return e.value + DUP_MARGIN_MS >= t
    }

    /** [s, e] を足して、重なる・接する時間帯をまとめる。 */
    private fun addInterval(kept: TreeMap<Long, Long>, s0: Long, e0: Long) {
        var s = s0
        var e = e0
        kept.floorEntry(s)?.let { if (it.value >= s) { s = it.key; e = maxOf(e, it.value) } }
        while (true) {
            val next = kept.ceilingEntry(s) ?: break
            if (next.key > e) break
            e = maxOf(e, next.value)
            kept.remove(next.key)
        }
        kept[s] = e
    }
}

/** 取り込みの印（0.10.1。Room の import_source に保存し、同じものの再取り込みを黙って外す）。 */
object ActivityKeys {
    /** ファイルの印 [base]（"sha:…"・"fit:…"・"strava:…"）と活動の始まり [startMs] から。 */
    fun of(base: String, startMs: Long): String = "$base@${startMs / 1000}"
}
