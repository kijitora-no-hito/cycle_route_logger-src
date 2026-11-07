package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.gpx.GpxTime
import jp.kjtr.cycleroutelogger.importer.ActivityCatalog
import jp.kjtr.cycleroutelogger.importer.ActivityFormat
import jp.kjtr.cycleroutelogger.importer.ActivityReader
import jp.kjtr.cycleroutelogger.importer.DayPlanner
import jp.kjtr.cycleroutelogger.importer.DupNote
import jp.kjtr.cycleroutelogger.importer.ExistingTrack
import jp.kjtr.cycleroutelogger.importer.KeepReason
import jp.kjtr.cycleroutelogger.importer.PlanResult
import jp.kjtr.cycleroutelogger.importer.PointBlock
import jp.kjtr.cycleroutelogger.importer.FileSpool
import jp.kjtr.cycleroutelogger.importer.FileStatus
import jp.kjtr.cycleroutelogger.importer.FitParser
import jp.kjtr.cycleroutelogger.importer.ImportBuilder
import jp.kjtr.cycleroutelogger.importer.MemoryStore
import jp.kjtr.cycleroutelogger.importer.PointSink
import jp.kjtr.cycleroutelogger.importer.RunCollector
import jp.kjtr.cycleroutelogger.importer.Sport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/** ファイルからの記録の取り込み（0.10.0）: FIT / GPX / TCX / KML の解析、zip・.gz、日付での分割、重複の除去、既存の記録との重複。 */
class ActivityImportTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    // ---- 組み立ての道具 ----

    private class Pt(val t: Long, val lat: Double, val lon: Double, val ele: Double?, val speed: Float?)

    private class Recorder : PointSink {
        val runs = ArrayList<MutableList<Pt>>()
        var sport = Sport.UNKNOWN
        override fun beginRun() { runs.add(ArrayList()) }
        override fun point(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?) {
            if (runs.isEmpty()) runs.add(ArrayList())
            runs.last().add(Pt(timeMs, lat, lon, ele, speedMps))
        }
        override fun sport(s: Sport) { sport = s }
        val all get() = runs.flatten()
    }

    /** テスト用の小さな FIT（定義・データ・圧縮タイムスタンプ・開発者フィールド・CRC）。 */
    private class FitBuilder {
        private class D(val sizes: IntArray, val big: Boolean, val dev: Int)
        private val defs = HashMap<Int, D>()
        private val body = ByteArrayOutputStream()

        fun def(local: Int, global: Int, fields: List<Pair<Int, Int>>, big: Boolean = false, devSizes: List<Int> = emptyList()) {
            body.write(0x40 or (if (devSizes.isNotEmpty()) 0x20 else 0) or local)
            body.write(0)
            body.write(if (big) 1 else 0)
            write(global.toLong(), 2, big)
            body.write(fields.size)
            for ((num, size) in fields) {
                body.write(num); body.write(size); body.write(if (size == 1) 0x02 else if (size == 2) 0x84 else 0x86)
            }
            if (devSizes.isNotEmpty()) {
                body.write(devSizes.size)
                devSizes.forEachIndexed { i, s -> body.write(i); body.write(s); body.write(0) }
            }
            defs[local] = D(fields.map { it.second }.toIntArray(), big, devSizes.sum())
        }

        fun data(local: Int, vararg values: Long) = message(local, local, values)

        /** 圧縮タイムスタンプのヘッダ（ローカル 0〜3、時刻の下 5 ビット [offset]）。 */
        fun compressed(local: Int, offset: Int, vararg values: Long) = message(0x80 or (local shl 5) or (offset and 0x1F), local, values)

        private fun message(header: Int, local: Int, values: LongArray) {
            val d = defs[local]!!
            body.write(header)
            d.sizes.forEachIndexed { i, s -> write(values[i], s, d.big) }
            repeat(d.dev) { body.write(0xAB) }
        }

        private fun write(v: Long, size: Int, big: Boolean) {
            val bytes = ByteArray(size) { i -> (v shr (8 * i)).toByte() }
            if (big) bytes.reverse()
            body.write(bytes)
        }

        fun build(): ByteArray {
            val data = body.toByteArray()
            val out = ByteArrayOutputStream()
            val header = ByteArray(14)
            header[0] = 14; header[1] = 0x20; header[2] = 0x54; header[3] = 0x08
            for (i in 0 until 4) header[4 + i] = (data.size shr (8 * i)).toByte()
            ".FIT".toByteArray().copyInto(header, 8)
            val hcrc = FitParser.crc(header, 0, 12)
            header[12] = hcrc.toByte(); header[13] = (hcrc shr 8).toByte()
            out.write(header); out.write(data)
            val all = out.toByteArray()
            val crc = FitParser.crc(all, 0, all.size)
            out.write(crc and 0xFF); out.write(crc shr 8)
            return out.toByteArray()
        }
    }

    private fun semi(deg: Double): Long = (deg * 2147483648.0 / 180.0).roundToLong() and 0xFFFFFFFFL
    private fun fitTs(ms: Long): Long = ms / 1000 - FitParser.FIT_EPOCH_S
    private fun utc(s: String) = GpxTime.parse(s)!!

    /** 東へ [n] 秒、[mps] で進む線（緯度 35.68）。 */
    private fun east(t0: Long, n: Int, mps: Double = 5.0, lon0: Double = 139.70): List<Pt> =
        (0 until n).map { i -> Pt(t0 + i * 1000L, 35.68, lon0 + i * mps / 90_400.0, 10.0, null) }

    private fun gpx(pts: List<Pt>, type: String? = null): String = buildString {
        append("<?xml version=\"1.0\"?><gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>")
        if (type != null) append("<type>$type</type>")
        append("<trkseg>")
        for (p in pts) append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"><ele>${p.ele}</ele><time>${GpxTime.format(p.t)}</time></trkpt>")
        append("</trkseg></trk></gpx>")
    }

    private fun fitOf(pts: List<Pt>, sport: Int = 2): ByteArray = FitBuilder().apply {
        def(0, FitParser.MESG_RECORD, listOf(253 to 4, 0 to 4, 1 to 4, 78 to 4, 73 to 4))
        for (p in pts) data(0, fitTs(p.t), semi(p.lat), semi(p.lon), ((p.ele!! + 500) * 5).roundToLong(), 5000)
        def(1, FitParser.MESG_SESSION, listOf(253 to 4, 5 to 1))
        data(1, fitTs(pts.last().t), sport.toLong())
    }.build()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    private fun gz(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(bytes) } }.toByteArray()

    private class Read(val store: MemoryStore, val collector: RunCollector, val reader: ActivityReader)

    private fun read(name: String, bytes: ByteArray): Read {
        val store = MemoryStore()
        val c = RunCollector(store, nowMs = utc("2027-01-01T00:00:00Z"))
        val r = ActivityReader({ KXmlParser() }, c)
        r.read(name, bytes.inputStream())
        return Read(store, c, r)
    }

    // ---- FIT ----

    @Test
    fun fit_records_compressedTimestamps_developerFields_noPosition_sport() {
        val t0 = utc("2026-05-03T00:00:30Z") // FIT の時刻の下 5 ビットが 0 でない
        val ts0 = fitTs(t0)
        val b = FitBuilder().apply {
            // 開発者フィールド（2 バイト + 1 バイト）つきの record の定義
            def(0, FitParser.MESG_RECORD, listOf(253 to 4, 0 to 4, 1 to 4, 2 to 2, 6 to 2), devSizes = listOf(2, 1))
            data(0, ts0, semi(35.681236), semi(139.767125), ((40.0 + 500) * 5).toLong(), 5500)
            // 位置の無い record（屋内・センサーだけ）
            data(0, ts0 + 1, 0x7FFFFFFF, 0x7FFFFFFF, 0xFFFF, 4000)
            // 圧縮タイムスタンプ用の定義（timestamp なし、ビッグエンディアン）
            def(1, FitParser.MESG_RECORD, listOf(0 to 4, 1 to 4, 78 to 4, 73 to 4), big = true)
            compressed(1, ((ts0 + 2) and 0x1F).toInt(), semi(35.6813), semi(139.7672), ((41.6 + 500) * 5).roundToLong(), 6000)
            // 28 秒後（下 5 ビットが一周すれば前より小さい値 → +32 秒として戻す）
            compressed(1, ((ts0 + 30) and 0x1F).toInt(), semi(35.6814), semi(139.7673), ((42.0 + 500) * 5).toLong(), 6100)
            def(2, FitParser.MESG_SESSION, listOf(253 to 4, 5 to 1))
            data(2, ts0 + 30, 2)
        }.build()
        val rec = Recorder()
        val r = FitParser.parse(b, rec)
        assertTrue(r.crcOk)
        assertFalse(r.truncated)
        assertEquals(3, r.points)
        assertEquals(1, r.noPosition)
        assertEquals(Sport.CYCLING, r.sport)
        val pts = rec.all
        assertEquals(listOf(t0, t0 + 2000, t0 + 30_000), pts.map { it.t })
        assertEquals(35.681236, pts[0].lat, 1e-6)
        assertEquals(139.767125, pts[0].lon, 1e-6)
        assertEquals(40.0, pts[0].ele!!, 1e-9)
        assertEquals(5.5f, pts[0].speed!!, 1e-6f)
        assertEquals(41.6, pts[1].ele!!, 1e-9)
        assertEquals(6.0f, pts[1].speed!!, 1e-6f)
    }

    @Test
    fun fit_badCrc_and_truncated_stillUsePoints() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val good = fitOf(east(t0, 10))
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val r1 = FitParser.parse(bad, Recorder())
        assertFalse(r1.crcOk)
        assertEquals(10, r1.points)
        // 途中で切れたファイル（最後の数メッセージと CRC が無い）
        val cut = good.copyOf(good.size - 30)
        val rec = Recorder()
        val r2 = FitParser.parse(cut, rec)
        assertTrue(r2.truncated)
        assertTrue(r2.points in 7..9)
    }

    @Test(expected = FitParser.NotFitException::class)
    fun fit_rejectsNonFit() {
        FitParser.parse("<gpx></gpx>                 ".toByteArray(), Recorder())
    }

    // ---- GPX / TCX / KML / zip / .gz ----

    @Test
    fun zip_recursive_gz_mixedFormats_andReports() {
        val t0 = utc("2026-05-03T00:00:00Z")
        val tcx = "  \n  <?xml version=\"1.0\" encoding=\"UTF-8\"?><TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">" +
            "<Activities><Activity Sport=\"Biking\"><Id>x</Id><Lap StartTime=\"2026-05-04T00:00:00Z\"><Track>" +
            (0 until 5).joinToString("") { i ->
                "<Trackpoint><Time>${GpxTime.format(utc("2026-05-04T00:00:00Z") + i * 1000L)}</Time>" +
                    "<Position><LatitudeDegrees>35.0</LatitudeDegrees><LongitudeDegrees>${139.0 + i * 0.00005}</LongitudeDegrees></Position>" +
                    "<AltitudeMeters>12.5</AltitudeMeters><Extensions><TPX xmlns=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\"><Speed>4.5</Speed></TPX></Extensions></Trackpoint>"
            } +
            "<Trackpoint><Time>2026-05-04T00:00:09Z</Time><HeartRateBpm><Value>120</Value></HeartRateBpm></Trackpoint>" +
            "</Track></Lap></Activity></Activities></TrainingCenterDatabase>"
        val routeOnly = "<gpx version=\"1.1\"><rte><rtept lat=\"35\" lon=\"139\"/><rtept lat=\"35.1\" lon=\"139\"/></rte><wpt lat=\"35\" lon=\"139\"/></gpx>"
        val untimed = "<gpx version=\"1.0\"><trk><trkseg><trkpt lat=\"35\" lon=\"139\"/><trkpt lat=\"35.1\" lon=\"139\"/></trkseg></trk></gpx>"
        val inner = zip("deep/c.GPX" to gpx(east(utc("2026-05-05T00:00:00Z"), 6)).toByteArray())
        val bytes = zip(
            "activities/" to ByteArray(0),
            "activities/1.gpx.gz" to gz(gpx(east(t0, 8)).toByteArray()),
            "activities/2.FIT.GZ" to gz(fitOf(east(utc("2026-05-06T00:00:00Z"), 7))),
            "activities/3.tcx.gz" to gz(tcx.toByteArray()),
            "routes/route.gpx" to routeOnly.toByteArray(),
            "routes/untimed.gpx" to untimed.toByteArray(),
            "nested.zip" to inner,
            "broken.fit" to "not a fit file at all".toByteArray(),
            "activities.csv" to "a,b\n".toByteArray(),
            "photo.jpg" to ByteArray(10),
            "__MACOSX/activities/._1.gpx.gz" to ByteArray(4),
        )
        val r = read("export.zip", bytes)
        val reports = r.reader.reports.associateBy { it.path.substringAfterLast("export.zip/") }
        assertEquals(FileStatus.OK, reports["activities/1.gpx.gz"]!!.status)
        assertEquals(8, reports["activities/1.gpx.gz"]!!.points)
        assertEquals(ActivityFormat.FIT, reports["activities/2.FIT.GZ"]!!.format)
        assertEquals(7, reports["activities/2.FIT.GZ"]!!.points)
        assertEquals(5, reports["activities/3.tcx.gz"]!!.points)
        assertTrue(reports["activities/3.tcx.gz"]!!.detail!!.contains("1"))
        assertEquals(FileStatus.ROUTE_ONLY, reports["routes/route.gpx"]!!.status)
        assertEquals(FileStatus.NO_TIME, reports["routes/untimed.gpx"]!!.status)
        assertEquals(6, reports["nested.zip/deep/c.GPX"]!!.points)
        assertEquals(FileStatus.BROKEN, reports["broken.fit"]!!.status)
        assertEquals(2, r.reader.ignored) // csv と jpg（__MACOSX は数えない）
        assertEquals(7, r.reader.reports.size)
        // TCX の速度・高度・種目
        val tcxRun = r.collector.runs.first { it.format == ActivityFormat.TCX }
        assertEquals(Sport.CYCLING, tcxRun.sport)
        val blk = r.store.read(tcxRun.first, tcxRun.count)
        assertEquals(4.5f, blk.speedAt(0)!!, 1e-6f)
        assertEquals(12.5, blk.eleAt(0)!!, 1e-6)
        // 日付は 4 日（5/3・5/4・5/5・5/6）
        val days = DayPlanner.plan(r.collector.runs, r.store, tokyo)
        assertEquals(4, days.size)
    }

    @Test
    fun gpxSport_running_isOther_andExcludable() {
        val t0 = utc("2026-05-03T00:00:00Z")
        val r = read(
            "a.zip",
            zip("run.gpx" to gpx(east(t0, 5), type = "running").toByteArray(), "ride.gpx" to gpx(east(t0 + 3_600_000, 5), type = "cycling").toByteArray()),
        )
        assertEquals(listOf(Sport.OTHER, Sport.CYCLING), r.collector.runs.map { it.sport })
        val cyclingOnly = DayPlanner.plan(r.collector.runs, r.store, tokyo, include = { it.sport != Sport.OTHER })
        assertEquals(5, cyclingOnly.single().points)
        assertEquals(10, DayPlanner.plan(r.collector.runs, r.store, tokyo).single().points)
        assertEquals(Sport.CYCLING, Sport.of("Road Biking"))
        assertEquals(Sport.UNKNOWN, Sport.of(null))
        assertEquals(Sport.OTHER, Sport.ofFit(1))
    }

    @Test
    fun kml_gxTrack_and_timeSpanInterpolation_and_untimedLines() {
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>
              <Placemark><gx:Track>
                <when>2026-05-03T01:00:00Z</when><when>2026-05-03T01:00:01Z</when><when>2026-05-03T01:00:02Z</when>
                <gx:coord>139.70 35.68 10</gx:coord><gx:coord>139.7001 35.68 11</gx:coord><gx:coord>139.7002 35.68 12</gx:coord>
              </gx:Track></Placemark>
              <Folder><TimeSpan><begin>2026-05-03T03:00:00Z</begin><end>2026-05-03T03:00:30Z</end></TimeSpan>
                <Placemark><LineString><coordinates>139.80,35.68,0 139.801,35.68,0 139.803,35.68,0</coordinates></LineString></Placemark>
              </Folder>
              <Placemark><TimeStamp><when>2026-05-03T05:00:00Z</when></TimeStamp><LineString><coordinates>139.9,35.6 139.91,35.6</coordinates></LineString></Placemark>
              <Placemark><Point><coordinates>139.9,35.6</coordinates></Point></Placemark>
            </Document></kml>"""
        val rec = Recorder()
        val r = jp.kjtr.cycleroutelogger.importer.KmlParser.parse(KXmlParser(), kml.byteInputStream(), rec)
        assertEquals(1, r.tracks)
        assertEquals(1, r.interpolatedLines)
        assertEquals(1, r.untimedLines)
        val track = rec.runs.first { it.isNotEmpty() }
        assertEquals(listOf(utc("2026-05-03T01:00:00Z"), utc("2026-05-03T01:00:01Z"), utc("2026-05-03T01:00:02Z")), track.map { it.t })
        assertEquals(11.0, track[1].ele!!, 1e-9)
        // TimeSpan: 道のりに比例（0・1/3・1 の位置 → 0 秒・10 秒・30 秒）
        val line = rec.runs.last { it.isNotEmpty() }
        val begin = utc("2026-05-03T03:00:00Z")
        assertEquals(listOf(0L, 10_000L, 30_000L), line.map { ((it.t - begin) / 100.0).roundToLong() * 100 })
        // KMZ（zip の中の doc.kml）も読む
        val kmz = read("trip.KMZ", zip("doc.kml" to kml.toByteArray(), "files/icon.png" to ByteArray(3)))
        assertEquals(6, kmz.reader.reports.single().points)
    }

    // ---- 日付での分割・重複 ----

    @Test
    fun days_splitAtLocalMidnight_andMergeSameDayFiles() {
        // 東京の 23:58〜0:02 にまたがる 1 本（UTC 14:58〜15:02）と、同じ日の昼の別ファイル
        val store = MemoryStore()
        val c = RunCollector(store, nowMs = utc("2027-01-01T00:00:00Z"))
        c.startFile(0, ActivityFormat.GPX)
        east(utc("2026-05-03T14:58:00Z"), 240).forEach { c.point(it.t, it.lat, it.lon, it.ele, it.speed) }
        c.endFile()
        c.startFile(1, ActivityFormat.FIT)
        east(utc("2026-05-03T03:00:00Z"), 60, lon0 = 139.6).forEach { c.point(it.t, it.lat, it.lon, it.ele, it.speed) }
        c.endFile()
        val days = DayPlanner.plan(c.runs, store, tokyo)
        assertEquals(2, days.size)
        val d3 = days[0]
        // 5/3: 昼の 60 点 + 23:58〜23:59:59 の 120 点、2 片（ファイルの境目で線をつながない）
        assertEquals(DayPlanner.dayOf(utc("2026-05-03T03:00:00Z"), tokyo), d3.day)
        assertEquals(2, d3.pieces.size)
        assertEquals(180, d3.points)
        assertEquals(setOf(0, 1), d3.files)
        assertEquals(listOf(ActivityFormat.FIT, ActivityFormat.GPX), d3.formats.toList())
        assertEquals(utc("2026-05-03T14:59:59Z"), d3.end)
        // 5/4: 0:00〜0:02
        assertEquals(120, days[1].points)
        assertEquals(utc("2026-05-03T15:00:00Z"), days[1].start)
        // UTC なら 1 日にまとまる
        assertEquals(1, DayPlanner.plan(c.runs, store, TimeZone.getTimeZone("UTC")).size)
    }

    @Test
    fun gap_over10min_splitsRun() {
        val store = MemoryStore()
        val c = RunCollector(store, nowMs = utc("2027-01-01T00:00:00Z"))
        c.startFile(0, ActivityFormat.GPX)
        val a = east(utc("2026-05-03T01:00:00Z"), 30)
        val b = east(utc("2026-05-03T01:10:29Z"), 30, lon0 = 139.71)
        (a + b).forEach { c.point(it.t, it.lat, it.lon, it.ele, it.speed) }
        // 時刻が戻る点・同じ時刻・2000 年より前・(0, 0) は捨てる
        c.point(utc("2026-05-03T01:00:00Z"), 35.0, 139.0, null, null)
        c.point(utc("1999-12-31T00:00:00Z"), 35.0, 139.0, null, null)
        c.point(utc("2026-05-03T02:00:00Z"), 0.0, 0.0, null, null)
        assertEquals(60, c.endFile())
        assertEquals(2, c.runs.size) // 01:00:29 → 01:10:29 は 10 分ちょうど → 分ける
    }

    @Test
    fun duplicates_sameRideInGpxAndFit_countedOnce() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val ride = east(t0, 600)
        // GPX は FIT より 3 秒早く始まり 5 秒遅く終わる（機器ごとの差）
        val gpxPts = east(t0 - 3000, 608)
        val r = read("both.zip", zip("a.gpx" to gpx(gpxPts).toByteArray(), "a.fit" to fitOf(ride)))
        val days = DayPlanner.plan(r.collector.runs, r.store, tokyo)
        val d = days.single()
        // 同じ走行なので FIT を丸ごと 1 つだけ（時間の差は 10% 以内なので形式の優先）。GPX の端の数秒も残らない
        assertEquals(600, d.points)
        assertEquals(1, d.pieces.size)
        assertEquals(ActivityFormat.FIT, d.pieces[0].format)
        val note = d.notes.single() as DupNote.SameRide
        assertEquals(1, note.kept) // a.fit（ファイルの添字 1）
        assertEquals(listOf(0), note.dropped)
        assertEquals(KeepReason.FORMAT, note.reason)
        val built = ImportBuilder.build(d, r.store, autoPause = true, smooth = null, name = "x")!!
        assertEquals(3000.0, built.track.distanceM, 60.0)
        assertEquals("fit", built.track.importFormat)
        assertTrue(built.points.all { it.provider == TrackPointEntity.PROVIDER_IMPORT && it.accuracy == null })
        assertEquals(TrackEntity.MODE_FREE, built.track.mode)
    }

    @Test
    fun builder_stoppedGap_isNotMovingTime_butMovingGapCounts() {
        val store = MemoryStore()
        val c = RunCollector(store, nowMs = utc("2027-01-01T00:00:00Z"))
        c.startFile(0, ActivityFormat.FIT)
        val t0 = utc("2026-05-03T01:00:00Z")
        val a = east(t0, 120, mps = 6.0)
        // 信号で 5 分止まって機器が記録を止めた（同じ場所から再開、端末の速度は 6 m/s）
        val last = a.last()
        val b = (1..120).map { i -> Pt(last.t + 300_000 + i * 1000L, last.lat, last.lon + i * 6.0 / 90_400.0, 10.0, 6f) }
        // トンネルで 60 秒位置が無い（その間に 360 m 進んだ）
        val bl = b.last()
        val tunnel = (1..60).map { i -> Pt(bl.t + 60_000 + i * 1000L, bl.lat, bl.lon + (360 + i * 6.0) / 90_400.0, 10.0, 6f) }
        (a.map { Pt(it.t, it.lat, it.lon, it.ele, 6f) } + b + tunnel).forEach { c.point(it.t, it.lat, it.lon, it.ele, it.speed) }
        c.endFile()
        val day = DayPlanner.plan(c.runs, store, tokyo).single()
        assertEquals(1, day.pieces.size)
        val built = ImportBuilder.build(day, store, autoPause = true, smooth = null, name = "x")!!
        // 止まっていた 5 分で区間が分かれ、走行時間に入らない。トンネルの 60 秒は動いていたので入る
        assertEquals(2, built.points.map { it.segment }.toSet().size)
        val movingS = built.track.movingMs / 1000.0
        assertEquals((119 + 119 + 60 + 60).toDouble(), movingS, 3.0)
        assertEquals((119 + 119 + 60) * 6.0 + 360, built.track.distanceM, 30.0)
        // 色分けの速さが入る（区間の最初の点は 0）
        assertTrue(built.points.count { (it.speed ?: 0f) > 5f } >= built.points.size - 4)
    }

    @Test
    fun fileSpool_roundTrip() {
        val f = File.createTempFile("spool", ".bin")
        FileSpool(f).use { s ->
            for (i in 0 until 10_000) s.append(1_000L + i, 35.0 + i * 1e-6, 139.0, if (i % 2 == 0) i.toDouble() else null, if (i % 3 == 0) 1.5f else null)
            val b = s.read(4_095, 4_100)
            assertEquals(1_000L + 4_095, b.time[0])
            assertEquals(35.0 + 8_194 * 1e-6, b.lat[4_099], 1e-12)
            assertNull(b.eleAt(0)) // 4095 は奇数
            assertEquals(4_096.0, b.eleAt(1)!!, 1e-9)
            assertEquals(1.5f, b.speedAt(0)!!, 0f) // 4095 は 3 の倍数
            assertNull(b.speedAt(1))
            assertEquals(1.5f, b.speedAt(3)!!, 0f)
            s.append(99, 1.0, 2.0, null, null)
            assertEquals(99L, s.read(10_000, 1).time[0])
        }
        assertFalse(f.exists())
    }

    // ---- 重複（0.10.1: 活動単位・取り込み済み・既存の記録） ----

    /** 東へ進む線に、位置の雑音（[noiseM] m 程度の決まったゆらぎ）を足す。 */
    private fun wobble(pts: List<Pt>, noiseM: Double, seed: Int): List<Pt> =
        pts.mapIndexed { i, p -> Pt(p.t, p.lat + noiseM * Math.sin((i + seed) * 0.7) / 111_000.0, p.lon + noiseM * Math.cos((i + seed) * 1.3) / 90_400.0, p.ele, p.speed) }

    private class Planned(val r: Read, val result: PlanResult)

    private fun plan(
        name: String,
        bytes: ByteArray,
        existing: List<ExistingTrack> = emptyList(),
        imported: Set<String> = emptySet(),
    ): Planned {
        val r = read(name, bytes)
        val acts = DayPlanner.activities(r.collector.runs)
        val res = DayPlanner.planAll(acts, r.store, tokyo, existing, imported, fileKeys = { r.reader.reports[it].keys })
        return Planned(r, res)
    }

    private fun block(pts: List<Pt>) = PointBlock(
        LongArray(pts.size) { pts[it].t }, DoubleArray(pts.size) { pts[it].lat }, DoubleArray(pts.size) { pts[it].lon },
        FloatArray(pts.size) { Float.NaN }, FloatArray(pts.size) { Float.NaN },
    )

    @Test
    fun twoDevices_sameRide_minutesApart_keepsOneWhole() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val route = east(t0 - 240_000, 3600 + 240 + 180) // 走った道（4 分前から 3 分後まで）
        // 機器 A（FIT）: 4 分早く始めて 1 時間、機器 B（GPX・スマホ）: t0 から 1 時間 3 分。位置は数 m ずれる
        val a = wobble(route.filter { it.t < t0 + 3_360_000 }, 6.0, 0)
        val b = wobble(route.filter { it.t >= t0 }, 8.0, 5)
        val p = plan("two.zip", zip("garmin.fit" to fitOf(a), "phone.gpx" to gpx(b).toByteArray()))
        val d = p.result.days.single()
        // 丸ごと 1 つ（切れ端を残さない）。時間の長さは 10% 以内なので FIT
        assertEquals(1, d.pieces.size)
        assertEquals(ActivityFormat.FIT, d.pieces.single().format)
        assertEquals(a.size, d.points)
        assertEquals(1, p.result.sameRideDropped)
        assertEquals(DupNote.SameRide(0, listOf(1), KeepReason.FORMAT), d.notes.single())
    }

    @Test
    fun twoDevices_muchLonger_wins_evenIfGpx() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val route = east(t0, 7200)
        // FIT は電池切れで最初の 30 分だけ、GPX は 2 時間
        val p = plan("two.zip", zip("a.fit" to fitOf(route.take(1800)), "b.gpx" to gpx(wobble(route, 5.0, 3)).toByteArray()))
        val d = p.result.days.single()
        assertEquals(ActivityFormat.GPX, d.pieces.single().format)
        assertEquals(7200, d.points)
        assertEquals(KeepReason.LONGER, (d.notes.single() as DupNote.SameRide).reason)
    }

    @Test
    fun sameTime_differentPlace_keepsBoth_asSeparateRecords() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val here = east(t0, 1800)
        val there = east(t0 + 300_000, 1800, lon0 = 140.10) // 35 km 東・5 分あと
        val p = plan("x.zip", zip("a.fit" to fitOf(here), "b.fit" to fitOf(there)))
        val days = p.result.days
        assertEquals(2, days.size)
        assertEquals(listOf(0, 1), days.map { it.slot })
        assertEquals(days[0].day, days[1].day)
        assertTrue(days.all { it.pieces.size == 1 && it.points == 1800 })
        assertTrue(days[1].notes.single() is DupNote.Elsewhere)
        assertEquals(0, p.result.sameRideDropped)
    }

    @Test
    fun consecutiveRides_smallOverlap_trimsOnlyTheOverlap() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val route = east(t0, 3600)
        // A は 0〜40 分、B は 38〜60 分（同じ道。2 分だけ重なる）→ 同じ走行ではない。重なった点だけ捨てる
        val p = plan("x.zip", zip("a.fit" to fitOf(route.take(2400)), "b.fit" to fitOf(route.drop(2280))))
        val d = p.result.days.single()
        assertEquals(2, d.pieces.size)
        assertEquals(0, p.result.sameRideDropped)
        val pts = d.points
        assertTrue("points $pts", pts in 3500..3600)
    }

    @Test
    fun sameFileTwice_inOneImport_countedOnce_andReimportIsSilentlyDropped() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val fit = fitOf(east(t0, 1200))
        val p = plan("x.zip", zip("a.fit" to fit, "copy/a.fit" to fit))
        assertEquals(1200, p.result.days.single().points)
        assertEquals(1, p.result.sameRideDropped)
        // 取り込みの印（SHA-256 と始まりの時刻）を覚えておけば、2 回目は黙って外す
        val keys = p.result.days.single().keys.toSet()
        assertTrue(keys.any { it.startsWith("sha:") })
        val again = plan("again.zip", zip("other-name.fit" to fit), imported = keys)
        assertTrue(again.result.days.isEmpty())
        assertEquals(1, again.result.alreadyImported)
        // 中身が違えば（別の走行）取り込む
        val other = plan("other.zip", zip("b.fit" to fitOf(east(t0 + 7_200_000, 600))), imported = keys)
        assertEquals(1, other.result.days.size)
    }

    @Test
    fun fitFileId_isAKey() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val fit = FitBuilder().apply {
            def(2, FitParser.MESG_FILE_ID, listOf(0 to 1, 1 to 2, 3 to 4, 4 to 4))
            data(2, 4, 1, 3_456_789, fitTs(t0))
            def(0, FitParser.MESG_RECORD, listOf(253 to 4, 0 to 4, 1 to 4))
            for (pt in east(t0, 60)) data(0, fitTs(pt.t), semi(pt.lat), semi(pt.lon))
        }.build()
        val r = read("a.fit", fit)
        assertTrue(r.reader.reports.single().keys.contains("fit:1/3456789/${fitTs(t0)}"))
    }

    @Test
    fun existingSameRide_isDropped_butOtherRideSameDay_isImported() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val morning = east(t0, 1800)
        val evening = east(t0 + 8 * 3_600_000L, 1200, lon0 = 139.80)
        // アプリで朝の走行を記録してある（4 分遅く始めて早く止めた・位置は数 m ずれる）
        val mine = wobble(morning.drop(240).dropLast(120), 7.0, 2)
        val own = ExistingTrack(7, "朝の走行", mine.first().t, mine.last().t, imported = false, points = block(mine))
        val p = plan("x.zip", zip("am.fit" to fitOf(morning), "pm.fit" to fitOf(evening)), existing = listOf(own))
        assertEquals(listOf(0 to "朝の走行"), p.result.sameAsExisting)
        val d = p.result.days.single()
        assertEquals(1200, d.points)
        assertEquals(ActivityFormat.FIT, d.pieces.single().format)
        // 自分で記録したものしか無い日は、取り込んだ分だけの新しい記録（自分の記録には足さない）
        assertNull(d.appendTo)
    }

    @Test
    fun existingImportedDay_newRide_isAppended() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val morning = east(t0, 1800)
        val evening = east(t0 + 8 * 3_600_000L, 1200, lon0 = 139.80)
        val prev = ExistingTrack(9, "2026-05-03 取り込み", morning.first().t, morning.last().t, imported = true, points = block(morning))
        val p = plan("x.zip", zip("am.fit" to fitOf(morning), "pm.gpx" to gpx(evening).toByteArray()), existing = listOf(prev))
        val d = p.result.days.single()
        assertEquals(9L, d.appendTo)
        assertEquals(1200, d.points)
        // 足すと、点は時刻の順・区間は振り直し、統計は合わせた分
        val target = TrackEntity(id = 9, name = "2026-05-03 取り込み", startedAt = morning.first().t, endedAt = morning.last().t, status = 2, mode = "free", autoPause = true, importFormat = "fit")
        val old = morning.map { TrackPointEntity(trackId = 9, segment = 0, time = it.t, lat = it.lat, lon = it.lon, ele = null, accuracy = null, deviceSpeed = null, speed = null, provider = TrackPointEntity.PROVIDER_IMPORT) }
        val built = ImportBuilder.appendTo(target, old, d, p.r.store, autoPause = true, smooth = null)!!
        assertTrue(built.replacesPoints)
        assertEquals(3000, built.points.size)
        assertEquals(listOf(0, 1), built.points.map { it.segment }.distinct())
        assertTrue(built.points.zipWithNext().all { (x, y) -> x.time < y.time })
        assertEquals("fit,gpx", built.track.importFormat)
        assertEquals(1200 * 5.0, built.addedDistanceM, 60.0)
        assertEquals((1800 + 1200) * 5.0, built.track.distanceM, 100.0)
    }

    @Test
    fun existingDifferentPlace_overlappingImportedDay_goesToAnotherRecord() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val prevPts = east(t0, 1800)
        val prev = ExistingTrack(9, "2026-05-03 取り込み", prevPts.first().t, prevPts.last().t, imported = true, points = block(prevPts))
        val p = plan("x.zip", zip("far.fit" to fitOf(east(t0 + 60_000, 1800, lon0 = 140.2))), existing = listOf(prev))
        val d = p.result.days.single()
        assertEquals(1, d.slot)
        assertNull(d.appendTo)
        assertEquals("2026-05-03 取り込み", (d.notes.single() as DupNote.Elsewhere).other)
    }

    // ---- Strava の activities.csv（0.10.1） ----

    @Test
    fun stravaCatalog_typesNamesIds_overrideFileSport() {
        val t0 = utc("2026-05-03T01:00:00Z")
        val csv = "\uFEFFActivity ID,Activity Date,Activity Name,Activity Type,Activity Description,Elapsed Time,Distance,Filename\n" +
            "111,\"May 3, 2026, 1:00:00 AM\",Morning Ride,Ride,\"複数行の\n説明, カンマも\",600,3.0,activities/111.fit.gz\n" +
            "222,\"May 3, 2026, 5:00:00 AM\",夕方のラン,Run,,600,3.0,activities/222.fit.gz\n" +
            "333,\"May 3, 2026, 9:00:00 AM\",\"Zwift - Watopia\",Virtual Ride,,600,3.0,activities/333.fit.gz\n" +
            "444,\"May 4, 2026, 1:00:00 AM\",Manual,Ride,,600,3.0,\n"
        // FIT の sport は全部 generic（0）。目録の種目で判定する
        val p = read(
            "export_1.zip",
            zip(
                "activities/111.fit.gz" to gz(fitOf(east(t0, 600), sport = 0)),
                "activities/222.fit.gz" to gz(fitOf(east(t0 + 4 * 3_600_000L, 600), sport = 0)),
                "activities/333.fit.gz" to gz(fitOf(east(t0 + 8 * 3_600_000L, 600), sport = 0)),
                "activities.csv" to csv.toByteArray(),
            ),
        )
        val cat = p.reader.catalog!!
        assertEquals(3, cat.size) // ファイルの無い行（手入力の活動）は数えない
        assertEquals(0, p.reader.ignored) // activities.csv は対象外に数えない
        val (runs, reports) = cat.applyTo(p.collector.runs, p.reader.reports)
        assertEquals(listOf(Sport.CYCLING, Sport.OTHER, Sport.VIRTUAL), runs.map { it.sport })
        assertEquals(listOf("Morning Ride", "夕方のラン", "Zwift - Watopia"), reports.map { it.title })
        assertTrue(reports[0].keys.contains("strava:111"))
        // 既定では自転車だけ（ラン・バーチャルは外す）
        val days = DayPlanner.plan(runs, p.store, tokyo, include = { !it.sport.excludedByDefault })
        assertEquals(600, days.single().points)
    }

    @Test
    fun stravaCatalog_japaneseHeaders_and_unknownHeaders_and_sjis() {
        val ja = "アクティビティID,アクティビティ日,アクティビティ名,アクティビティタイプ,ファイル名\r\n1,x,朝のライド,ライド,activities/1.fit.gz\r\n2,x,散歩,ウォーク,activities/2.gpx.gz\r\n"
        val c1 = ActivityCatalog.parse(ja.toByteArray())!!
        assertEquals(Sport.CYCLING, c1.find("export.zip/activities/1.fit.gz")!!.sport)
        assertEquals("朝のライド", c1.find("export.zip/activities/1.fit.gz")!!.name)
        assertEquals(Sport.OTHER, c1.find("2.gpx.gz")!!.sport) // 展開して選んだとき（ファイル名だけで対応）
        // Shift_JIS で保存し直したもの
        val c2 = ActivityCatalog.parse(ja.toByteArray(charset("MS932")))!!
        assertEquals("朝のライド", c2.find("activities/1.fit.gz")!!.name)
        // 列名が分からない（ほかの言語）でも、Strava の並び（13 列目がファイル）なら読む
        val cols = (0 until 13).joinToString(",") { "c$it" }
        val row = (0 until 13).joinToString(",") { when (it) { 0 -> "9"; 2 -> "Gravel"; 3 -> "Gravel Ride"; 12 -> "activities/9.fit.gz"; else -> "" } }
        val c3 = ActivityCatalog.parse("$cols\n$row\n".toByteArray())!!
        assertEquals(Sport.CYCLING, c3.find("activities/9.fit.gz")!!.sport)
        assertEquals("9", c3.find("activities/9.fit.gz")!!.id)
        assertNull(ActivityCatalog.parse("a,b\n1,2\n".toByteArray()))
    }

    @Test
    fun stravaTypes() {
        for (t in listOf("Ride", "E-Bike Ride", "Gravel Ride", "Mountain Bike Ride", "E-Mountain Bike Ride", "Handcycle", "Velomobile", "ライド", "E バイクライド", "マウンテンバイクライド")) {
            assertEquals(t, Sport.CYCLING, Sport.ofStrava(t))
        }
        for (t in listOf("Virtual Ride", "バーチャルライド")) assertEquals(t, Sport.VIRTUAL, Sport.ofStrava(t))
        for (t in listOf("Run", "Walk", "Canoe", "Alpine Ski", "Hike", "Swim", "ラン", "ウォーク")) assertEquals(t, Sport.OTHER, Sport.ofStrava(t))
        assertEquals(Sport.UNKNOWN, Sport.ofStrava(""))
        assertEquals(Sport.VIRTUAL, Sport.ofFit(2, 58))
        assertEquals(Sport.CYCLING, Sport.ofFit(2, 7))
    }
}
