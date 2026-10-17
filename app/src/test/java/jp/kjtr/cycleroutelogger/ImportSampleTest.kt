package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.importer.ActivityReader
import jp.kjtr.cycleroutelogger.importer.DayPlanner
import jp.kjtr.cycleroutelogger.importer.FileSpool
import jp.kjtr.cycleroutelogger.importer.FileStatus
import jp.kjtr.cycleroutelogger.importer.ImportBuilder
import jp.kjtr.cycleroutelogger.importer.RunCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.TimeZone

/**
 * 実際の一括書き出し（Strava など）を通して、読めた件数・失敗の内訳・日数・距離・時間を見る（手元の確認用。0.10.1）。
 * **書き出しは利用者の実際の走行なので、リポジトリにもテストのリソースにも入れない**。環境変数 `CRL_IMPORT_SAMPLE`（zip のパス）が
 * あるときだけ走り、無ければスキップする。結果は件数と合計だけを出す（場所・座標・名前は出さない）。
 */
class ImportSampleTest {
    private val sample: File? = System.getenv("CRL_IMPORT_SAMPLE")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun sampleExport_readsAndPlans_andReimportIsAllAlreadyImported() {
        val zip = sample
        assumeTrue("sample export not found", zip != null)
        zip!!
        val tz = TimeZone.getTimeZone("Asia/Tokyo")
        val rt = Runtime.getRuntime()
        System.gc()
        val heap0 = rt.totalMemory() - rt.freeMemory()
        var peak = heap0
        val t0 = System.nanoTime()
        val spoolFile = File.createTempFile("crl-sample", ".bin")
        FileSpool(spoolFile).use { spool ->
            val collector = RunCollector(spool, check = { peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) })
            val reader = ActivityReader({ KXmlParser() }, collector)
            zip.inputStream().buffered(1 shl 16).use { reader.read(zip.name, it) }
            val cat = reader.catalog
            val (runs, reports) = cat?.applyTo(collector.runs, reader.reports) ?: (collector.runs to reader.reports)
            val t1 = System.nanoTime()
            val acts = DayPlanner.activities(runs.filter { !it.sport.excludedByDefault })
            val fileKeys: (Int) -> List<String> = { reports[it].keys }
            val result = DayPlanner.planAll(acts, spool, tz, fileKeys = fileKeys)
            val days = result.days
            val t2 = System.nanoTime()
            var dist = 0.0
            var moving = 0L
            var built = 0
            for (d in days) {
                val b = ImportBuilder.build(d, spool, true, TrackRepository.smoothConfig(force = true), "x") ?: continue
                built++
                dist += b.track.distanceM
                moving += b.track.movingMs
                peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
            }
            val t3 = System.nanoTime()
            val byStatus = reader.reports.groupingBy { it.status }.eachCount()
            val byFormat = reader.reports.groupingBy { it.format }.eachCount()
            val details = reader.reports.mapNotNull { it.detail }.groupingBy { it.replace(Regex("\\d+"), "n") }.eachCount()
            val fileSports = runs.groupBy { it.file }.values.groupingBy { it.first().sport }.eachCount()
            val types = reports.mapNotNull { it.activityType }.groupingBy { it }.eachCount()
            println("[sample] files=${reader.reports.size} ignored=${reader.ignored} catalog=${cat?.size} status=$byStatus format=$byFormat details=$details")
            println("[sample] runs=${runs.size} activities=${acts.size} points=${collector.totalPoints} fileSports=$fileSports csvTypes=$types")
            println("[sample] days=${days.size} slots>0=${days.count { it.slot > 0 }} built=$built sameRide=${result.sameRideDropped} dist=%.1f km moving=%.1f h".format(dist / 1000, moving / 3_600_000.0))
            println("[sample] read=%.1fs plan=%.1fs build=%.1fs heap0=%d MB peak=%d MB spool=%d MB".format(
                (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9, heap0 shr 20, peak shr 20, spoolFile.length() shr 20,
            ))
            reader.reports.filter { it.status != FileStatus.OK }.forEach { println("[sample] problem ${it.status} ${it.format} ${it.detail}") }
            assertTrue(reader.reports.count { it.status == FileStatus.OK } * 100 >= reader.reports.size * 95)
            // 2 回目: 保存した日の印を覚えていれば、全部「取り込み済み」
            val keys = days.flatMap { it.keys }.toSet()
            val again = DayPlanner.planAll(acts, spool, tz, importedKeys = keys, fileKeys = fileKeys)
            println("[sample] reimport days=${again.days.size} already=${again.alreadyImported}")
            assertEquals(0, again.days.size)
        }
    }
}
