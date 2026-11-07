package jp.kjtr.cycleroutelogger.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 書き出しの目録（Strava の一括書き出しのルートにある `activities.csv`。0.10.1、DESIGN.md §4.10）。
 * Filename（`activities/123.fit.gz`）→ Activity Type・Activity Name・Activity ID を対応づけ、種目の判定（[Sport.ofStrava]。ファイルの種目より優先）と
 * 記録名・確認画面の表示・取り込みの印（"strava:<ID>"）に使う。Activity Date は使わない（点の時刻のほうが正確で、日付の書式が書き出しの言語で変わるため）。
 *
 * - CSV は RFC 4180（引用符の中のカンマ・改行・""）。UTF-8（BOM の有無を問わない）、UTF-8 として読めなければ MS932。
 * - 列は名前で探す（英語・日本語の候補。大文字小文字・空白を無視、同じ名前の列は最初のもの）。Filename の列が名前で見つからなければ
 *   中身（`activities/….fit.gz` などの値）で探し、それが Strava の並び（13 列目）なら種目・名前は 4・3 列目とみなす。
 * - ファイルとの対応: zip の中のパスの末尾が Filename と一致するもの。無ければファイル名（最後の / の後）が一致するものが 1 つだけならそれ
 *   （zip を展開して .fit.gz と activities.csv を一緒に選んだとき）。
 */
class ActivityCatalog private constructor(private val byBase: Map<String, List<Entry>>) {

    data class Entry(val filename: String, val type: String?, val name: String?, val id: String? = null) {
        val sport: Sport get() = Sport.ofStrava(type)
    }

    val size: Int get() = byBase.values.sumOf { it.size }

    fun find(path: String): Entry? {
        val norm = path.replace('\\', '/').lowercase()
        val list = byBase[norm.substringAfterLast('/')] ?: return null
        return list.firstOrNull { val k = it.filename.lowercase(); norm == k || norm.endsWith("/$k") } ?: list.singleOrNull()
    }

    operator fun plus(other: ActivityCatalog): ActivityCatalog {
        val m = HashMap<String, MutableList<Entry>>()
        for (c in listOf(this, other)) for ((k, v) in c.byBase) m.getOrPut(k) { ArrayList() }.addAll(v)
        return ActivityCatalog(m)
    }

    /**
     * 読んだ結果に目録を当てる: ファイルの報告に名前・種目を足し、線の種目を目録のもの（分かったとき）に置き換える。
     * [RunInfo.file] は [reports] の添字。
     */
    fun applyTo(runs: List<RunInfo>, reports: List<FileReport>): Pair<List<RunInfo>, List<FileReport>> {
        val entries = reports.map { find(it.path) }
        val newReports = reports.mapIndexed { i, r ->
            val e = entries[i] ?: return@mapIndexed r
            r.copy(
                title = e.name?.takeIf { it.isNotBlank() },
                activityType = e.type?.takeIf { it.isNotBlank() },
                keys = r.keys + listOfNotNull(e.id?.takeIf { it.isNotBlank() }?.let { "strava:$it" }),
            )
        }
        val newRuns = runs.map { run ->
            val s = entries.getOrNull(run.file)?.sport ?: Sport.UNKNOWN
            if (s != Sport.UNKNOWN) run.copy(sport = s) else run
        }
        return newRuns to newReports
    }

    companion object {
        /** これだけ読んで目録とみなすファイル名（zip の中のどこにあっても）。 */
        fun isCatalogName(name: String): Boolean = name.replace('\\', '/').substringAfterLast('/').equals("activities.csv", ignoreCase = true)

        private val FILENAME_KEYS = setOf("filename", "ファイル名", "ファイル")
        private val TYPE_KEYS = setOf("activitytype", "type", "sporttype", "アクティビティタイプ", "アクティビティの種類", "アクティビティの種目", "種類", "種目", "タイプ", "スポーツ")
        private val ID_KEYS = setOf("activityid", "アクティビティid", "id")
        private val NAME_KEYS = setOf("activityname", "name", "アクティビティ名", "アクティビティの名前", "名前", "タイトル")
        private val FILE_VALUE = Regex("(?i)^(.*/)?[^/]+\\.(fit|gpx|tcx)(\\.gz)?$")

        /** バイト列（UTF-8 / BOM / MS932）から。Filename の列が無ければ null。 */
        fun parse(bytes: ByteArray): ActivityCatalog? = parse(decode(bytes))

        fun parse(text: String): ActivityCatalog? {
            val rows = csvRows(text).filter { r -> r.any { it.isNotBlank() } }.toList()
            if (rows.size < 2) return null
            val header = rows[0].map { normalize(it) }
            val body = rows.subList(1, rows.size)
            var fileCol = header.indexOfFirst { it in FILENAME_KEYS }
            var typeCol = header.indexOfFirst { it in TYPE_KEYS }
            var nameCol = header.indexOfFirst { it in NAME_KEYS }
            var idCol = header.indexOfFirst { it in ID_KEYS }
            if (fileCol < 0) {
                // 列名で分からない（ほかの言語の書き出しなど）: 値がファイルのパスらしい列
                val cols = header.size
                fileCol = (0 until cols).firstOrNull { c ->
                    val vals = body.asSequence().mapNotNull { it.getOrNull(c)?.trim()?.takeIf(String::isNotEmpty) }.take(50).toList()
                    vals.isNotEmpty() && vals.count { FILE_VALUE.matches(it) } * 2 >= vals.size
                } ?: return null
                if (fileCol == STRAVA_FILENAME_COL) {
                    if (typeCol < 0) typeCol = STRAVA_TYPE_COL
                    if (nameCol < 0) nameCol = STRAVA_NAME_COL
                    if (idCol < 0) idCol = 0
                }
            }
            val m = HashMap<String, MutableList<Entry>>()
            for (r in body) {
                val f = r.getOrNull(fileCol)?.trim()?.replace('\\', '/')?.takeIf { it.isNotEmpty() } ?: continue
                val name = r.getOrNull(nameCol)?.replace(SPACES, " ")?.trim()
                val e = Entry(f, r.getOrNull(typeCol)?.trim(), name, r.getOrNull(idCol)?.trim())
                m.getOrPut(f.substringAfterLast('/').lowercase()) { ArrayList() }.add(e)
            }
            return if (m.isEmpty()) null else ActivityCatalog(m)
        }

        // Strava の activities.csv の並び（0 から）: Activity ID, Activity Date, Activity Name, Activity Type, …, Filename（12）
        private const val STRAVA_FILENAME_COL = 12
        private const val STRAVA_TYPE_COL = 3
        private const val STRAVA_NAME_COL = 2
        private const val BOM = "\uFEFF"
        private val SPACES = Regex("\\s+")

        private fun normalize(s: String): String =
            s.trim().removePrefix(BOM).lowercase().filterNot { it.isWhitespace() || it == '_' || it == '-' || it == '・' }

        fun decode(bytes: ByteArray): String {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            val buf = ByteBuffer.wrap(bytes, start, bytes.size - start)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString()
            } catch (e: CharacterCodingException) {
                val cs = runCatching { Charset.forName("MS932") }.getOrDefault(Charsets.ISO_8859_1)
                String(bytes, start, bytes.size - start, cs)
            }
        }

        /** CSV の行（RFC 4180: 引用符の中のカンマ・改行、"" は " 1 つ）。 */
        fun csvRows(text: String): Sequence<List<String>> = sequence {
            var row = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < n && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                    } else {
                        cell.append(c)
                    }
                } else {
                    when (c) {
                        '"' -> quoted = true
                        ',' -> { row.add(cell.toString()); cell.setLength(0) }
                        '\r' -> {}
                        '\n' -> { row.add(cell.toString()); cell.setLength(0); yield(row); row = ArrayList() }
                        else -> cell.append(c)
                    }
                }
                i++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); yield(row) }
        }
    }
}
