package jp.kjtr.cycleroutelogger.importer

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * 選ばれたファイルが「このアプリのバックアップ」か「記録の取り込みで読めるファイル」かを、先頭から軽く覗いて判定する（0.10.2。DESIGN.md §6.1）。
 * 設定の［バックアップを読み込む］で Strava の書き出しを選んだとき・取り込みでバックアップを選んだときに、正しい入口へ案内するため。
 *
 * - zip はエントリを頭から順に見るだけで、決まったところでやめる（全部は読まない）。
 *   - 最上位の `manifest.json` → [Kind.BACKUP]（このアプリのバックアップは plans/・tracks/ の GPX と manifest.json。manifest は最後に入っている）。
 *   - バックアップの GPX の名前（`plans/plan_N.gpx`・`tracks/track_N.gpx`）は決め手にしないで先を見る。
 *   - それ以外の GPX / FIT / TCX / KML / KMZ / `.gpx.gz` など・`activities.csv`（Strava の目録）→ [Kind.ACTIVITY]。入れ子の zip の中も見る。
 *   - 最後まで見て manifest が無く、バックアップの名前の GPX だけがあった → [Kind.ACTIVITY]（GPX として読める）。何も無ければ [Kind.UNKNOWN]。
 *   - [maxEntries] / [maxBytes] まで見ても決まらなければ [Kind.UNDECIDED]。
 * - 単体のファイルは拡張子（.gpx / .fit / .tcx / .kml / .kmz / .gz）、無ければ先頭のバイト（gzip・FIT・`<gpx`・TrainingCenterDatabase・`<kml`）。
 */
object FileKind {
    enum class Kind { BACKUP, ACTIVITY, UNKNOWN, UNDECIDED }

    private val BACKUP_GPX = Regex("""^(plans/plan_\d+|tracks/track_\d+)\.gpx$""")
    private const val MANIFEST = "manifest.json"

    const val MAX_ENTRIES = 3000
    const val MAX_BYTES = 48L * 1024 * 1024

    /**
     * [name] は表示名（拡張子の判定に使う。分からなければ null）。[input] は呼び出し側で閉じる。
     * エントリ名が UTF-8 でない zip は MS932 で読み直せないので（ストリームは 1 回きり）、名前の読めないエントリで止まり、そこまでで判定する。
     */
    fun classify(name: String?, input: InputStream, maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_BYTES): Kind {
        val lower = name?.substringAfterLast('/')?.lowercase().orEmpty()
        if (isActivityName(lower)) return Kind.ACTIVITY
        val b = BufferedInputStream(input, 8192)
        b.mark(2048)
        val head = ByteArray(1024)
        var n = 0
        while (n < head.size) {
            val r = try {
                b.read(head, n, head.size - n)
            } catch (e: IOException) {
                -1
            }
            if (r < 0) break
            n += r
        }
        val bytes = head.copyOf(n)
        if (isZip(bytes)) {
            b.reset()
            val budget = Budget(maxEntries, maxBytes)
            return zip(Counting(b, budget), budget, depth = 0, Charsets.UTF_8).let { r ->
                when (r) {
                    ZipResult.BACKUP -> Kind.BACKUP
                    ZipResult.ACTIVITY -> Kind.ACTIVITY
                    ZipResult.LIMIT -> Kind.UNDECIDED
                    ZipResult.END_BACKUP_GPX -> Kind.ACTIVITY
                    ZipResult.END_NOTHING -> Kind.UNKNOWN
                }
            }
        }
        return if (looksLikeActivity(bytes)) Kind.ACTIVITY else Kind.UNKNOWN
    }

    /** 取り込みで読める拡張子（単体のファイル・zip の中のエントリ）。 */
    fun isActivityName(lowerBase: String): Boolean {
        if (lowerBase.endsWith(".kmz") || lowerBase.endsWith(".gz")) return true
        return ActivityReader.formatOf(lowerBase) != null
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    private fun looksLikeActivity(b: ByteArray): Boolean {
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0x1F && (b[1].toInt() and 0xFF) == 0x8B) return true
        if (FitParser.looksLikeFit(b)) return true
        val text = String(b, Charsets.UTF_8)
        return text.contains("<gpx") || text.contains(":gpx") || text.contains("TrainingCenterDatabase") || text.contains("<kml") || text.contains(":kml")
    }

    private enum class ZipResult { BACKUP, ACTIVITY, LIMIT, END_BACKUP_GPX, END_NOTHING }

    private class Budget(var entries: Int, val maxBytes: Long) {
        var bytes = 0L
        val over get() = entries < 0 || bytes > maxBytes
    }

    private fun zip(input: InputStream, budget: Budget, depth: Int, charset: Charset): ZipResult {
        val z = ZipInputStream(NonClosing(input), charset)
        var backupGpx = false
        while (true) {
            if (budget.over) return ZipResult.LIMIT
            val e = try {
                z.nextEntry
            } catch (ex: IllegalArgumentException) {
                null // 名前が UTF-8 でない。ここまでで判定
            } catch (ex: IOException) {
                null // 壊れている・読めない形の zip。ここまでで判定
            } ?: break
            budget.entries--
            val path = e.name.replace('\\', '/')
            val base = path.substringAfterLast('/').lowercase()
            if (e.isDirectory || base.isEmpty() || path.startsWith("__MACOSX/") || path.contains("/__MACOSX/") || base.startsWith("._")) continue
            if (depth == 0 && path == MANIFEST) return ZipResult.BACKUP
            if (depth == 0 && BACKUP_GPX.matches(path)) {
                backupGpx = true
                continue
            }
            if (ActivityCatalog.isCatalogName(base) || isActivityName(base)) return ZipResult.ACTIVITY
            if (base.endsWith(".zip") && depth < 2) {
                val r = try {
                    zip(z, budget, depth + 1, charset)
                } catch (ex: IOException) {
                    ZipResult.END_NOTHING
                }
                if (r == ZipResult.ACTIVITY || r == ZipResult.LIMIT) return r
            }
        }
        return if (backupGpx) ZipResult.END_BACKUP_GPX else ZipResult.END_NOTHING
    }

    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    /** 読んだバイトを数える（[Budget.maxBytes] を超えたら次のエントリで止める）。 */
    private class Counting(input: InputStream, private val budget: Budget) : FilterInputStream(input) {
        override fun read(): Int {
            val r = super.read()
            if (r >= 0) budget.bytes++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) budget.bytes += r
            return r
        }

        override fun skip(n: Long): Long {
            val r = super.skip(n)
            if (r > 0) budget.bytes += r
            return r
        }

        override fun close() {}
    }
}
