package jp.kjtr.cycleroutelogger.importer

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import jp.kjtr.cycleroutelogger.CycleApp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/**
 * ファイル（zip / GPX / FIT / TCX / KML / KMZ / .gz）から記録を取り込む流れ（0.10.0。DESIGN.md §4.10）。
 * アプリ全体で 1 つ（[CycleApp.activityImport]）。アプリのスコープで動くので、画面の回転・ほかの画面への移動では止まらない（プロセスが続く間）。
 *
 * 読む（[State.Reading]）→ 日ごとに分けて統計を出す（[State.Planning]）→ 確認（[State.Review]）→ 保存（[State.Importing]）→ [State.Done]。
 * 点は一時ファイル（cache/activity_import/points.bin）に置き、終わる・やめると消す。
 */
class ActivityImporter(private val app: CycleApp, private val scope: CoroutineScope) {

    /** 確認画面の 1 行（1 日分。同じ日に時間の重なる別の場所の走行があれば slot ごとに別の行）。 */
    data class DayRow(
        /** 選択のキー（[DayPlan.key]: 日 × 16 + slot）。 */
        val key: Long,
        val day: Long,
        val label: String,
        val start: Long,
        val end: Long,
        val distanceM: Double,
        val movingMs: Long,
        val points: Int,
        val files: List<String>,
        val formats: List<ActivityFormat>,
        /** 時間が重なるが別の場所の記録・ファイル（0.10.1。両方残す。印だけ）。 */
        val overlaps: List<String>,
        /** 自転車以外（ランなど）・バーチャルと書かれたファイルを含む。 */
        val hasOther: Boolean,
        /** 書き出しの目録（activities.csv）の名前（0.10.1。重複を除いて時刻の順）。 */
        val titles: List<String> = emptyList(),
        /** 保存するときの記録名。 */
        val recordName: String = label,
        /** 前に取り込んだその日の記録に区間として足すときの記録名（0.10.1）。 */
        val appendTo: String? = null,
        /** 重複の判定の説明（「同じ走行 2 件 → FIT（…）を採用（時間が長い）」など。0.10.1）。 */
        val notes: List<String> = emptyList(),
    )

    data class Analysis(
        val sources: List<String>,
        val days: List<DayRow>,
        val files: List<FileReport>,
        /** 対象外として飛ばしたファイル（画像・CSV など）。 */
        val ignored: Int,
        /** 自転車以外・バーチャルと書かれたファイルの数。 */
        val otherFiles: Int,
        /** 読んだ点（重複を除く前）。 */
        val readPoints: Long,
        /** 書き出しの目録（activities.csv）に載っていたファイルの数（0.10.1。目録が無ければ 0）。 */
        val catalogFiles: Int = 0,
        /** 取り込み済みで外した活動の数（0.10.1）。 */
        val alreadyImported: Int = 0,
        /** 既存の記録と同じ走行なので外した活動（「ファイル → 記録の名前」）。 */
        val sameAsExisting: List<String> = emptyList(),
        /** ほかのファイルと同じ走行なので外した活動の数。 */
        val sameRideDropped: Int = 0,
    ) {
        val okFiles: Int get() = files.count { it.status == FileStatus.OK || it.status == FileStatus.PARTIAL }
        val routeOnly: Int get() = files.count { it.status == FileStatus.ROUTE_ONLY }
        val problems: List<FileReport> get() = files.filter { it.status != FileStatus.OK }
    }

    sealed interface State {
        data object Idle : State
        data class Reading(val bytes: Long, val total: Long?, val files: Int, val points: Long, val current: String?) : State
        data class Planning(val done: Int, val total: Int) : State
        data class Review(val analysis: Analysis, val selected: Set<Long>, val includeOther: Boolean) : State
        data class Importing(val done: Int, val total: Int) : State
        data class Done(val imported: Int, val distanceM: Double) : State
        data class Failed(val message: String) : State
        /** 選んだファイルがこのアプリのバックアップ（manifest.json 入りの zip）だった（0.10.2）。［バックアップを読み込む］へ案内する。 */
        data class LooksLikeBackup(val uri: Uri, val name: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var store: PointStore? = null
    private var runs: List<RunInfo> = emptyList()
    private var reports: List<FileReport> = emptyList()
    private var ignored = 0
    private var readPoints = 0L
    private var catalogFiles = 0
    private var sources: List<String> = emptyList()
    private var plans: Map<Long, DayPlan> = emptyMap()

    private val dir get() = File(app.cacheDir, "activity_import")

    init {
        // 前のプロセスで残った一時ファイル
        runCatching { dir.deleteRecursively() }
    }

    /**
     * [uris] を読んで日ごとに分ける。[skipBackupCheck] = false なら、1 ファイルだけのとき先頭を覗いて
     * このアプリのバックアップなら [State.LooksLikeBackup] にして止まる（0.10.2）。
     */
    fun start(uris: List<Uri>, skipBackupCheck: Boolean = false) {
        if (uris.isEmpty()) return
        cancel()
        _state.value = State.Reading(0, null, 0, 0, null)
        job = scope.launch(Dispatchers.IO) {
            try {
                if (!skipBackupCheck && uris.size == 1) {
                    val uri = uris[0]
                    val name = displayName(uri)
                    val kind = runCatching { app.contentResolver.openInputStream(uri)?.use { FileKind.classify(name, it) } }.getOrNull()
                    if (kind == FileKind.Kind.BACKUP) {
                        _state.value = State.LooksLikeBackup(uri, name)
                        return@launch
                    }
                }
                read(uris)
                plan(includeOther = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch // やめた（一時ファイルを閉じたので読み書きが失敗した）
                Log.w(TAG, "import failed", e)
                cleanup()
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 自転車以外のファイルも入れる / 入れないを切り替える（分け直す）。 */
    fun setIncludeOther(on: Boolean) {
        val r = _state.value as? State.Review ?: return
        if (r.includeOther == on) return
        job = scope.launch(Dispatchers.IO) {
            try {
                plan(on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch
                Log.w(TAG, "plan failed", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun toggle(key: Long) {
        val r = _state.value as? State.Review ?: return
        _state.value = r.copy(selected = if (key in r.selected) r.selected - key else r.selected + key)
    }

    /** [keys] の日をまとめて選ぶ / 外す（確認画面の年・月の見出しのチェック）。 */
    fun selectDays(keys: Collection<Long>, on: Boolean) {
        val r = _state.value as? State.Review ?: return
        _state.value = r.copy(selected = if (on) r.selected + keys else r.selected - keys.toSet())
    }

    fun selectAll(on: Boolean, includeOverlaps: Boolean = false) {
        val r = _state.value as? State.Review ?: return
        _state.value = r.copy(
            selected = if (!on) emptySet() else r.analysis.days.filter { includeOverlaps || it.overlaps.isEmpty() }.map { it.key }.toSet(),
        )
    }

    fun importSelected() {
        val r = _state.value as? State.Review ?: return
        val chosen = r.analysis.days.filter { it.key in r.selected }
        if (chosen.isEmpty()) return
        val s = store ?: return
        job = scope.launch(Dispatchers.IO) {
            var done = 0
            var dist = 0.0
            try {
                val autoPause = app.settings.settings.value.autoPause
                val smooth = TrackRepository.smoothConfig()
                for (row in chosen) {
                    coroutineContext.ensureActive()
                    _state.value = State.Importing(done, chosen.size)
                    val plan = plans[row.key] ?: continue
                    val target = plan.appendTo?.let { app.tracks.trackNow(it) }
                    val built = if (target != null) {
                        // 前に取り込んだその日の記録に区間として足す
                        ImportBuilder.appendTo(target, app.tracks.points(target.id), plan, s, autoPause, smooth)
                    } else {
                        ImportBuilder.build(plan, s, autoPause, smooth, row.recordName)
                    } ?: continue
                    app.tracks.insertImported(built, plan.keys)
                    done++
                    dist += built.addedDistanceM
                }
                cleanup()
                _state.value = State.Done(done, dist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isActive) return@launch
                Log.w(TAG, "save failed", e)
                cleanup()
                _state.value = State.Failed(app.getString(R.string.import_save_failed, done, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** やめる（読んでいる途中・確認画面・保存の途中。保存の途中なら、それまでに保存した日は残る）。 */
    fun cancel() {
        job?.cancel()
        job = null
        cleanup()
        _state.value = State.Idle
    }

    /** 終わった・失敗した表示を閉じる。 */
    fun dismiss() {
        if (_state.value is State.Done || _state.value is State.Failed || _state.value is State.LooksLikeBackup) _state.value = State.Idle
    }

    private fun cleanup() {
        runCatching { store?.close() }
        store = null
        plans = emptyMap()
        runs = emptyList()
        runCatching { dir.deleteRecursively() }
    }

    // ---- 読む ----

    private suspend fun read(uris: List<Uri>) {
        val ctx = coroutineContext
        val check: () -> Unit = { ctx.ensureActive() }
        var charset: Charset = Charsets.UTF_8
        while (true) {
            cleanup()
            dir.mkdirs()
            val spool = FileSpool(File(dir, "points.bin"))
            store = spool
            val collector = RunCollector(spool, check = check)
            var current: String? = null
            var bytes = 0L
            var lastPublish = 0L
            val total = uris.sumOf { sizeOf(it) ?: 0L }.takeIf { it > 0 }
            val reader = ActivityReader({ Xml.newPullParser() }, collector, charset, check) { current = it }
            fun publish(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastPublish < 200) return
                lastPublish = now
                _state.value = State.Reading(bytes, total, reader.reports.size, collector.totalPoints, current)
            }
            try {
                val names = ArrayList<String>()
                for (uri in uris) {
                    check()
                    val name = displayName(uri) ?: uri.lastPathSegment ?: "file"
                    names.add(name)
                    val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException(app.getString(R.string.import_open_failed, name))
                    Counting(input) { n -> bytes += n; publish() }.use { reader.read(name, it) }
                    publish(force = true)
                }
                sources = names
                runs = collector.runs.toList()
                reports = reader.reports.toList()
                catalogFiles = 0
                reader.catalog?.let { cat ->
                    // Strava の activities.csv: 種目（ファイルの種目より優先）と名前
                    val (r2, f2) = cat.applyTo(runs, reports)
                    runs = r2
                    reports = f2
                    catalogFiles = f2.count { it.activityType != null || it.title != null }
                }
                ignored = reader.ignored
                readPoints = collector.totalPoints
                return
            } catch (e: ActivityReader.ZipNameException) {
                // 名前が UTF-8 でない zip（Windows の「送る → 圧縮」など）は MS932 で読み直す
                if (charset == Charsets.UTF_8) {
                    charset = runCatching { Charset.forName("MS932") }.getOrDefault(Charsets.ISO_8859_1)
                    continue
                }
                throw e
            }
        }
    }

    // ---- 日ごとに分ける ----

    private suspend fun plan(includeOther: Boolean) {
        val ctx = coroutineContext
        val s = store ?: return
        _state.value = State.Planning(0, 0)
        val tz = TimeZone.getDefault()
        val byId = runs.associateBy { it.id }
        val acts = DayPlanner.activities(runs.filter { includeOther || !it.sport.excludedByDefault })
        val fileKeys: (Int) -> List<String> = { reports.getOrNull(it)?.keys.orEmpty() }
        val importedKeys = app.tracks.importedKeys()
        // 比べる既存の記録: 取り込み済みでない活動と時間が重なるもの＋その日に前に取り込んだ記録（区間として足す先）。点は必要なものだけ読む
        val fresh = acts.filter { a -> DayPlanner.keysOf(a, fileKeys).none { it in importedKeys } }
        val now = System.currentTimeMillis()
        val all = app.tracks.allTracks()
        val freshDays = fresh.flatMapTo(HashSet()) { listOf(DayPlanner.dayOf(it.start, tz), DayPlanner.dayOf(it.end, tz)) }
        val spans = fresh.map { longArrayOf(it.start, it.end) }.sortedBy { it[0] }
        val needed = all.filter { t ->
            val e = t.endedAt ?: now
            (t.imported && t.status == TrackEntity.STATUS_FINISHED && DayPlanner.dayOf(t.startedAt, tz) in freshDays) ||
                spans.any { it[0] <= e && it[1] >= t.startedAt }
        }
        val existing = needed.map { t ->
            ctx.ensureActive()
            ExistingTrack(t.id, t.name, t.startedAt, t.endedAt ?: now, t.imported && t.status == TrackEntity.STATUS_FINISHED, app.tracks.pointBlock(t.id))
        }
        val result = DayPlanner.planAll(acts, s, tz, existing, importedKeys, fileKeys, fileLabel = ::fileLabel, check = { ctx.ensureActive() })
        val days = result.days
        val autoPause = app.settings.settings.value.autoPause
        val smooth = TrackRepository.smoothConfig()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = tz }
        val rows = ArrayList<DayRow>()
        val kept = HashMap<Long, DayPlan>()
        days.forEachIndexed { i, d ->
            ctx.ensureActive()
            if (i % 8 == 0) _state.value = State.Planning(i, days.size)
            val label = dayFmt.format(Date(d.start))
            // 確認画面の統計はこの取り込みで増える分（区間として足す日も新しい点だけ）
            val built = ImportBuilder.build(d, s, autoPause, smooth, label) ?: return@forEachIndexed
            kept[d.key] = d
            val titles = d.files.mapNotNull { reports.getOrNull(it)?.title }.distinct()
            rows.add(
                DayRow(
                    key = d.key, day = d.day, label = label, start = built.track.startedAt, end = built.track.endedAt ?: d.end,
                    distanceM = built.track.distanceM, movingMs = built.track.movingMs, points = built.points.size,
                    files = d.files.map { reports.getOrNull(it)?.path ?: "?" },
                    formats = d.formats.toList(),
                    overlaps = d.notes.filterIsInstance<DupNote.Elsewhere>().map { it.other }.distinct(),
                    hasOther = d.pieces.any { byId[it.run]?.sport?.excludedByDefault == true },
                    titles = titles,
                    recordName = recordName(label, titles, d.slot),
                    appendTo = d.appendToName,
                    notes = d.notes.filterIsInstance<DupNote.SameRide>().map { noteText(it) },
                ),
            )
        }
        plans = kept
        val otherFiles = runs.filter { it.sport.excludedByDefault }.map { it.file }.toSet().size
        val analysis = Analysis(
            sources, rows, reports, ignored, otherFiles, readPoints, catalogFiles,
            alreadyImported = result.alreadyImported,
            sameAsExisting = result.sameAsExisting.map { (f, name) -> app.getString(R.string.act_import_same_existing_line, fileLabel(f), name) },
            sameRideDropped = result.sameRideDropped,
        )
        // 既定: すべて選ぶ（重複は活動単位で外してある。別の場所で時間が重なる日は印だけ）
        _state.value = State.Review(analysis, rows.map { it.key }.toSet(), includeOther)
    }

    /** ファイルの表示名（目録の名前があれば「名前（FIT）」、無ければファイル名（FIT））。 */
    private fun fileLabel(file: Int): String {
        val r = reports.getOrNull(file) ?: return "?"
        val name = r.title ?: r.path.substringAfterLast('/')
        return name + (r.format?.let { "（${it.label}）" } ?: "")
    }

    private fun noteText(n: DupNote.SameRide): String {
        val reason = app.getString(
            when (n.reason) {
                KeepReason.LONGER -> R.string.act_import_reason_longer
                KeepReason.FORMAT -> R.string.act_import_reason_format
                KeepReason.MORE_POINTS -> R.string.act_import_reason_points
            },
        )
        return app.getString(R.string.act_import_same_ride, n.dropped.size + 1, fileLabel(n.kept), reason)
    }

    /**
     * 記録名: 目録の名前が無ければ「2026-05-03 取り込み」、1 つなら「2026-05-03 朝のライド」、2 つなら「… A / B」、3 つ以上は「… A / B ほか n 件」。
     */
    private fun recordName(label: String, titles: List<String>, slot: Int): String {
        val t = titles.map { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }
        val name = when (t.size) {
            0 -> app.getString(R.string.track_name_imported, label)
            1, 2 -> app.getString(R.string.track_name_imported_titled, label, t.joinToString(" / "))
            else -> app.getString(R.string.track_name_imported_more, label, t.take(2).joinToString(" / "), t.size - 2)
        }
        // 同じ日の別の記録（時間が重なる別の場所の走行）
        return if (slot > 0) "$name (${slot + 1})" else name
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** 読んだバイト数を数える（進み具合）。 */
    private class Counting(input: InputStream, private val onRead: (Int) -> Unit) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) onRead(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) onRead(n)
            return n
        }
    }

    companion object {
        private const val TAG = "CRL-Import"
        private const val TITLE_MAX = 40
    }
}
