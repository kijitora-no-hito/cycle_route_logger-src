package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.importer.FileKind
import jp.kjtr.cycleroutelogger.importer.FileKind.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** 「バックアップを読み込む」と「ファイルから記録を取り込む」の取り違えの判定（0.10.2。FileKind）。 */
class FileKindTest {
    private val gpx = """<?xml version="1.0"?><gpx version="1.1" creator="t"><trk><trkseg>
        <trkpt lat="35.0" lon="139.0"><time>2026-09-01T00:00:00Z</time></trkpt></trkseg></trk></gpx>""".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** 読んだバイト数を数える（全部読まずに決めていることを確かめる）。 */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun kind(name: String?, data: ByteArray) = FileKind.classify(name, ByteArrayInputStream(data))

    @Test
    fun backupZipIsBackup() {
        // このアプリのバックアップ: plans/・tracks/ の GPX の後に manifest.json
        val b = zip("plans/plan_1.gpx" to gpx, "tracks/track_3.gpx" to gpx, "tracks/track_12.gpx" to gpx, "manifest.json" to """{"version":1}""".toByteArray())
        assertEquals(Kind.BACKUP, kind("cycleroutelogger-backup-20261017.zip", b))
        assertEquals(Kind.BACKUP, kind(null, b))
        // 記録が無いバックアップ（manifest だけ）
        assertEquals(Kind.BACKUP, kind("backup.zip", zip("manifest.json" to "{}".toByteArray())))
    }

    @Test
    fun stravaLikeZipIsActivity() {
        val big = Random(1).nextBytes(400_000)
        val fit = ByteArray(32).also { it[0] = 14; it[8] = '.'.code.toByte(); it[9] = 'F'.code.toByte(); it[10] = 'I'.code.toByte(); it[11] = 'T'.code.toByte() }
        val data = zip(
            "activities/111.fit.gz" to gz(fit),
            "activities/112.gpx.gz" to gz(gpx),
            "media/a.jpg" to big,
            "activities.csv" to "Activity ID,Activity Date,Activity Name,Activity Type\n".toByteArray(),
            "profile.csv" to "x".toByteArray(),
        )
        val c = Counting(ByteArrayInputStream(data))
        assertEquals(Kind.ACTIVITY, FileKind.classify("export_12345.zip", c))
        // 最初のエントリで決まるので、後ろの画像までは読まない
        assertTrue("read ${c.count} of ${data.size}", c.count < 100_000)
        // CSV だけ先にあっても activities.csv で決まる
        assertEquals(Kind.ACTIVITY, kind("export.zip", zip("profile.csv" to "x".toByteArray(), "activities.csv" to "a,b".toByteArray())))
        // 中のフォルダ・入れ子の zip（Garmin の書き出しの UploadedFiles_*.zip 風）
        assertEquals(Kind.ACTIVITY, kind("garmin.zip", zip("DI_CONNECT/a.json" to "{}".toByteArray(), "DI_CONNECT/UploadedFiles_0.zip" to zip("x/1.FIT" to fit))))
        // バックアップの名前の GPX だけで manifest が無い → GPX として取り込める
        assertEquals(Kind.ACTIVITY, kind("x.zip", zip("tracks/track_1.gpx" to gpx)))
        // manifest.json があっても最上位でなければバックアップではない
        assertEquals(Kind.ACTIVITY, kind("x.zip", zip("sub/manifest.json" to "{}".toByteArray(), "sub/ride.tcx" to "<x/>".toByteArray())))
    }

    @Test
    fun singleActivityFiles() {
        assertEquals(Kind.ACTIVITY, kind("ride.gpx", gpx))
        assertEquals(Kind.ACTIVITY, kind("RIDE.FIT", byteArrayOf(1, 2, 3)))
        assertEquals(Kind.ACTIVITY, kind("a.tcx", ByteArray(0)))
        assertEquals(Kind.ACTIVITY, kind("a.kml", ByteArray(0)))
        assertEquals(Kind.ACTIVITY, kind("a.kmz", ByteArray(0)))
        assertEquals(Kind.ACTIVITY, kind("a.fit.gz", gz(byteArrayOf(1))))
        // 名前が分からなくても中身の先頭で
        assertEquals(Kind.ACTIVITY, kind(null, gpx))
        assertEquals(Kind.ACTIVITY, kind("download", gz(gpx)))
        assertEquals(Kind.ACTIVITY, kind("document", "<?xml version=\"1.0\"?><TrainingCenterDatabase>".toByteArray()))
    }

    @Test
    fun unrelatedFilesAreUnknown() {
        assertEquals(Kind.UNKNOWN, kind("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0)))
        assertEquals(Kind.UNKNOWN, kind("notes.txt", "hello".toByteArray()))
        assertEquals(Kind.UNKNOWN, kind("empty", ByteArray(0)))
        assertEquals(Kind.UNKNOWN, kind("docs.zip", zip("a.txt" to "x".toByteArray(), "b.png" to byteArrayOf(1, 2), "__MACOSX/._a.gpx" to gpx)))
        // 壊れた zip（先頭だけ PK）
        assertEquals(Kind.UNKNOWN, kind("broken.zip", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0)))
    }

    @Test
    fun stopsAtLimit() {
        // 対象外のエントリばかりが続く大きな zip は、上限で「決まらない」
        val entries = (1..50).map { "img/$it.jpg" to ByteArray(10) }.toTypedArray()
        assertEquals(Kind.UNDECIDED, FileKind.classify("big.zip", ByteArrayInputStream(zip(*entries)), maxEntries = 10))
        // 上限を外せば最後まで見る
        assertEquals(Kind.UNKNOWN, FileKind.classify("big.zip", ByteArrayInputStream(zip(*entries)), Int.MAX_VALUE, Long.MAX_VALUE))
    }
}
