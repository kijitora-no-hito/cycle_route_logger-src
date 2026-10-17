package jp.kjtr.cycleroutelogger.importer

import jp.kjtr.cycleroutelogger.gpx.GpxException
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.Charset
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * zip（中のフォルダ・入れ子の zip / kmz も）・.gz・単体のファイルを順に読み、GPX / FIT / TCX / KML を [collector] に流す。
 * zip は [ZipInputStream] で頭から順に読むだけ（丸ごとメモリに載せない）。1 ファイルは展開後 [MAX_ENTRY_BYTES] まで。
 *
 * - 形式は拡張子で決める（大文字小文字を問わない。`.gpx.gz` などは .gz を外して）。拡張子の無い・知らない単体のファイル（SAF で選んだもの）は中身の先頭で判定する。
 * - `activities.csv`（Strava の目録）は [catalog] に読む（0.10.1）。
 * - zip の中の知らない拡張子（画像・ほかの CSV など）は [ignored] に数えるだけ。`__MACOSX/` と `._` で始まるもの（macOS の付属ファイル）は数えない。
 * - エントリ名の文字コードは [charset]（既定 UTF-8。名前が UTF-8 でない zip は [ZipNameException] を投げるので、呼び出し側が MS932 で読み直す）。
 */
class ActivityReader(
    private val newParser: () -> XmlPullParser,
    private val collector: RunCollector,
    private val charset: Charset = Charsets.UTF_8,
    /** エントリごとに呼ぶ（キャンセルの確認）。 */
    private val check: () -> Unit = {},
    /** 読み始めるファイルのパス（進み具合の表示）。 */
    private val onFile: (String) -> Unit = {},
) {
    /** 読もうとしたファイル（[RunInfo.file] の番号の順）。 */
    val reports = ArrayList<FileReport>()
    /** 対象外として飛ばしたファイルの数。 */
    var ignored = 0
        private set
    /** 書き出しの目録（Strava の activities.csv。0.10.1）。いくつかあればまとめる。読み終えてから [ActivityCatalog.applyTo] で当てる。 */
    var catalog: ActivityCatalog? = null
        private set

    class ZipNameException(cause: Throwable) : IOException("zip entry name is not $cause", cause)
    private class TooLarge : IOException("too large")

    /** 選ばれた 1 ファイル（zip でも単体でも）を読む。[name] は表示名（拡張子の判定に使う）。 */
    fun read(name: String, input: InputStream) = entry(name, name, input, depth = 0, sniff = true)

    private fun entry(path: String, name: String, input: InputStream, depth: Int, sniff: Boolean) {
        check()
        val lower = name.substringAfterLast('/').lowercase()
        when {
            lower.endsWith(".gz") -> {
                val inner = name.dropLast(3)
                val gz = try {
                    GZIPInputStream(NonClosing(input), 64 * 1024)
                } catch (e: IOException) {
                    report(path, formatOf(inner), FileStatus.BROKEN, detail = "gzip")
                    return
                }
                try {
                    entry(path, inner, gz, depth, sniff = sniff)
                } catch (e: IOException) {
                    if (e is ZipNameException) throw e
                    report(path, formatOf(inner), FileStatus.BROKEN, detail = "gzip")
                }
            }
            lower.endsWith(".zip") || lower.endsWith(".kmz") -> zip(path, input, depth + 1, kmz = lower.endsWith(".kmz"))
            ActivityCatalog.isCatalogName(lower) -> readCatalog(input)
            else -> {
                val f = formatOf(name)
                if (f != null) {
                    parseFile(path, f, input)
                } else if (sniff) {
                    sniffAndRead(path, input, depth)
                } else {
                    ignored++
                }
            }
        }
    }

    /** activities.csv（目録）。読めなければ対象外と数える。 */
    private fun readCatalog(input: InputStream) {
        val c = try {
            ActivityCatalog.parse(readAll(Limited(input, MAX_ENTRY_BYTES)))
        } catch (e: IOException) {
            null
        }
        if (c == null) {
            ignored++
            return
        }
        catalog = catalog?.plus(c) ?: c
    }

    private fun sniffAndRead(path: String, input: InputStream, depth: Int) {
        val b = BufferedInputStream(input, 8192)
        b.mark(4096)
        val head = ByteArray(1024)
        var n = 0
        while (n < head.size) {
            val r = b.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        b.reset()
        val bytes = head.copyOf(n)
        when {
            n >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2].toInt() == 3 && bytes[3].toInt() == 4 -> zip(path, b, depth + 1, kmz = false)
            n >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B -> entry(path, "$path.unknown.gz", b, depth, sniff = true)
            FitParser.looksLikeFit(bytes) -> parseFile(path, ActivityFormat.FIT, b)
            else -> {
                val text = String(bytes, Charsets.UTF_8)
                when {
                    text.contains("<gpx") || text.contains(":gpx") -> parseFile(path, ActivityFormat.GPX, b)
                    text.contains("TrainingCenterDatabase") -> parseFile(path, ActivityFormat.TCX, b)
                    text.contains("<kml") || text.contains(":kml") -> parseFile(path, ActivityFormat.KML, b)
                    else -> report(path, null, FileStatus.BROKEN, detail = UNSUPPORTED)
                }
            }
        }
    }

    private fun zip(path: String, input: InputStream, depth: Int, kmz: Boolean) {
        if (depth > MAX_DEPTH) {
            ignored++
            return
        }
        val z = ZipInputStream(NonClosing(input), charset)
        while (true) {
            check()
            val e = try {
                z.nextEntry
            } catch (ex: IllegalArgumentException) {
                // 名前が UTF-8 でない（Windows で作った zip など）
                throw ZipNameException(ex)
            } catch (ex: IOException) {
                report(path, null, FileStatus.BROKEN, detail = "zip")
                return
            } ?: break
            val name = e.name.replace('\\', '/')
            val base = name.substringAfterLast('/')
            if (e.isDirectory || base.isEmpty() || name.startsWith("__MACOSX/") || name.contains("/__MACOSX/") || base.startsWith("._")) continue
            val sub = if (path.isEmpty()) name else "$path/$name"
            if (kmz && !base.lowercase().endsWith(".kml")) continue
            try {
                entry(sub, name, z, depth, sniff = false)
            } catch (ex: ZipNameException) {
                throw ex
            } catch (ex: IOException) {
                // 壊れた zip（途中で切れている）: このエントリを壊れたとして、その先は読めない
                report(sub, formatOf(name), FileStatus.BROKEN, detail = "zip")
                return
            }
        }
    }

    private fun parseFile(path: String, format: ActivityFormat, raw: InputStream) {
        onFile(path)
        val index = reports.size
        collector.startFile(index, format)
        // 取り込みの印（0.10.1）: 読んだ中身（展開後）の SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val input = DigestInputStream(Limited(raw, MAX_ENTRY_BYTES), digest)
        var status = FileStatus.OK
        var detail: String? = null
        var fitId: String? = null
        try {
            when (format) {
                ActivityFormat.FIT -> {
                    val bytes = readAll(input)
                    val r = FitParser.parse(bytes, collector, check)
                    fitId = r.fileId
                    val notes = ArrayList<String>()
                    if (r.noPosition > 0) notes.add("$NO_POSITION_RECORDS ${r.noPosition}")
                    if (!r.crcOk) notes.add(CRC_MISMATCH)
                    if (r.truncated) notes.add(TRUNCATED)
                    detail = notes.joinToString("・").ifEmpty { null }
                    status = when {
                        r.points == 0 && r.noPosition > 0 -> FileStatus.NO_POSITION
                        r.points == 0 -> FileStatus.NO_TIME
                        r.truncated -> FileStatus.PARTIAL
                        else -> FileStatus.OK
                    }
                    collector.sport(r.sport)
                }
                ActivityFormat.GPX -> {
                    val data = GpxParser(maxBytes = MAX_ENTRY_BYTES - 1).parse(newParser(), skipLeadingSpace(input))
                    var timed = 0
                    var lines = 0
                    var sport = Sport.UNKNOWN
                    for (t in data.tracks) {
                        if (t.isRoute) continue
                        lines++
                        if (sport == Sport.UNKNOWN) sport = Sport.of(t.type)
                        for (seg in t.segments) {
                            collector.beginRun()
                            for (p in seg) {
                                val tm = p.timeMs ?: continue
                                collector.point(tm, p.lat, p.lon, p.ele, null)
                                timed++
                            }
                        }
                    }
                    collector.sport(sport)
                    status = when {
                        timed > 0 && data.partial -> FileStatus.PARTIAL
                        timed > 0 -> FileStatus.OK
                        lines == 0 -> FileStatus.ROUTE_ONLY
                        else -> FileStatus.NO_TIME
                    }
                    if (data.decimated) detail = DECIMATED
                }
                ActivityFormat.TCX -> {
                    val r = TcxParser.parse(newParser(), skipLeadingSpace(input), collector, check)
                    status = when {
                        r.points == 0 && r.noPosition > 0 -> FileStatus.NO_POSITION
                        r.points == 0 -> FileStatus.NO_TIME
                        r.partial -> FileStatus.PARTIAL
                        else -> FileStatus.OK
                    }
                    if (r.noPosition > 0) detail = "$NO_POSITION_RECORDS ${r.noPosition}"
                }
                ActivityFormat.KML -> {
                    val r = KmlParser.parse(newParser(), skipLeadingSpace(input), collector, check)
                    status = when {
                        r.points == 0 -> FileStatus.NO_TIME
                        r.partial -> FileStatus.PARTIAL
                        else -> FileStatus.OK
                    }
                    val notes = ArrayList<String>()
                    if (r.interpolatedLines > 0) notes.add("$INTERPOLATED ${r.interpolatedLines}")
                    if (r.untimedLines > 0) notes.add("$UNTIMED_LINES ${r.untimedLines}")
                    detail = notes.joinToString("・").ifEmpty { null }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TooLarge) {
            status = FileStatus.TOO_LARGE
        } catch (e: GpxException) {
            status = when (e.reason) {
                GpxException.Reason.TOO_LARGE -> FileStatus.TOO_LARGE
                GpxException.Reason.EMPTY -> FileStatus.NO_TIME
                else -> FileStatus.BROKEN
            }
        } catch (e: ZipNameException) {
            throw e
        } catch (e: Exception) {
            // 形式が違う・壊れている・想定外の値（NotFit / NotTcx / NotKml / IOException など）
            status = FileStatus.BROKEN
        }
        // 読めたところまでの点は使う（壊れたファイルでも、途中までの点があれば一部とする）
        val pts = collector.endFile()
        if (status == FileStatus.BROKEN && pts > 0) status = FileStatus.PARTIAL
        if (status == FileStatus.TOO_LARGE && pts > 0) status = FileStatus.PARTIAL
        val keys = listOfNotNull("sha:" + hex(digest.digest(), 16), fitId?.let { "fit:$it" })
        reports.add(FileReport(path, format, status, pts, detail, keys = keys))
        // ZipInputStream は次のエントリへ進むときに残りを読み飛ばす
    }

    private fun report(path: String, format: ActivityFormat?, status: FileStatus, detail: String? = null) {
        val index = reports.size
        if (format != null) {
            collector.startFile(index, format)
            collector.endFile()
        }
        reports.add(FileReport(path, format, status, 0, detail))
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** zip / gzip の中のストリームを、パーサが閉じても外側を閉じないように包む。 */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    private class Limited(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw TooLarge()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw TooLarge()
            }
            return n
        }

        override fun close() {}
    }

    companion object {
        /** 1 ファイルの展開後の上限（GPX の解析の上限と同じ 64 MB）。 */
        const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        /** zip の入れ子の深さの上限。 */
        const val MAX_DEPTH = 4

        // 報告の補足（画面にそのまま出す）
        const val UNSUPPORTED = "対応していない形式"
        const val NO_POSITION_RECORDS = "位置の無い点"
        const val CRC_MISMATCH = "CRC 不一致（読めた点は使います）"
        const val TRUNCATED = "途中で切れています"
        const val DECIMATED = "点が多いので間引きました"
        const val INTERPOLATED = "TimeSpan から時刻を割り振った線"
        const val UNTIMED_LINES = "時刻の無い線"

        fun hex(b: ByteArray, n: Int): String = buildString { for (i in 0 until minOf(n, b.size)) append("%02x".format(b[i].toInt() and 0xFF)) }

        fun formatOf(name: String): ActivityFormat? {
            val l = name.substringAfterLast('/').lowercase()
            return when {
                l.endsWith(".gpx") -> ActivityFormat.GPX
                l.endsWith(".fit") -> ActivityFormat.FIT
                l.endsWith(".tcx") -> ActivityFormat.TCX
                l.endsWith(".kml") -> ActivityFormat.KML
                else -> null
            }
        }

        /**
         * XML の前の空白・BOM を飛ばす（Strava の TCX は `<?xml` の前に空白があり、そのままだと XML の解析が失敗する）。
         */
        fun skipLeadingSpace(input: InputStream): InputStream {
            val p = PushbackInputStream(input, 4)
            while (true) {
                val b = p.read()
                if (b < 0) return p
                if (b == ' '.code || b == '\t'.code || b == '\r'.code || b == '\n'.code) continue
                if (b == 0xEF) {
                    val b2 = p.read()
                    val b3 = p.read()
                    if (b2 == 0xBB && b3 == 0xBF) continue
                    if (b3 >= 0) p.unread(b3)
                    if (b2 >= 0) p.unread(b2)
                }
                p.unread(b)
                return p
            }
        }
    }
}
