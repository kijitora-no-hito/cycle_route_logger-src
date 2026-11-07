package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.net.ContentKind
import jp.kjtr.cycleroutelogger.net.DownloadTooLargeException
import jp.kjtr.cycleroutelogger.net.RemoteContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RemoteContentTest {
    private val gpx = """<?xml version="1.0" encoding="UTF-8"?>
<!-- created by test -->
<gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>川沿いルート</name></metadata>
  <wpt lat="35.1" lon="139.1"><name>カフェ</name></wpt>
  <trk><name>Day 1</name><trkseg>
    <trkpt lat="35.0" lon="139.0"/><trkpt lat="35.001" lon="139.001"/>
  </trkseg></trk>
</gpx>"""

    private fun html(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("drive/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun gzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    // ---- 中身の判定 ----

    @Test
    fun classify_gpx() {
        assertEquals(ContentKind.GPX, RemoteContent.classify(gpx.toByteArray()))
        // BOM・先頭の空白・名前空間の接頭辞
        assertEquals(ContentKind.GPX, RemoteContent.classify(("﻿ \n" + gpx).toByteArray()))
        assertEquals(ContentKind.GPX, RemoteContent.classify("<g:gpx xmlns:g=\"x\"></g:gpx>".toByteArray()))
        // UTF-16（BOM 付き）
        assertEquals(ContentKind.GPX, RemoteContent.classify(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + gpx.toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun classify_html() {
        assertEquals(ContentKind.HTML, RemoteContent.classify(html("virus_scan_2024.html").toByteArray()))
        assertEquals(ContentKind.HTML, RemoteContent.classify(html("need_access.html").toByteArray()))
        assertEquals(ContentKind.HTML, RemoteContent.classify("<html><body>x</body></html>".toByteArray()))
    }

    @Test
    fun classify_otherXmlAndText() {
        assertEquals(ContentKind.XML_NOT_GPX, RemoteContent.classify("<?xml version=\"1.0\"?><kml><Document/></kml>".toByteArray()))
        assertEquals(ContentKind.OTHER, RemoteContent.classify("{\"type\":\"FeatureCollection\"}".toByteArray()))
        assertEquals(ContentKind.EMPTY, RemoteContent.classify(ByteArray(0)))
        assertEquals(ContentKind.EMPTY, RemoteContent.classify("  \n ".toByteArray()))
    }

    @Test
    fun classify_gzipAndParse() {
        val gz = gzip(gpx.toByteArray())
        assertTrue(RemoteContent.isGzip(gz))
        assertEquals(ContentKind.GPX_GZIP, RemoteContent.classify(gz))
        val data = RemoteContent.gpxStream(gz).use { GpxParser().parse(KXmlParser(), it) }
        assertEquals("川沿いルート", data.name)
        assertEquals(1, data.tracks.size)
        assertEquals(1, data.waypoints.size)
        // gzip の中身が HTML なら HTML
        assertEquals(ContentKind.HTML, RemoteContent.classify(gzip("<!DOCTYPE html><html></html>".toByteArray())))
        // 壊れた gzip
        assertEquals(ContentKind.OTHER, RemoteContent.classify(byteArrayOf(0x1F, 0x8B.toByte(), 1, 2, 3)))
    }

    @Test
    fun plainGpxStreamParses() {
        val data = RemoteContent.gpxStream(gpx.toByteArray()).use { GpxParser().parse(KXmlParser(), it) }
        assertEquals(2, data.pointCount)
    }

    // ---- 大きさの上限 ----

    @Test
    fun readCapped_underLimit() {
        val b = ByteArray(100_000) { (it % 251).toByte() }
        var last = 0L
        val r = RemoteContent.readCapped(ByteArrayInputStream(b), 100_000, onProgress = { last = it })
        assertTrue(r.contentEquals(b))
        assertEquals(100_000L, last)
    }

    @Test
    fun readCapped_tooLarge() {
        try {
            RemoteContent.readCapped(ByteArrayInputStream(ByteArray(300_000)), 200_000)
            fail("should throw")
        } catch (e: DownloadTooLargeException) {
            assertEquals(200_000L, e.limit)
        }
    }

    @Test
    fun readCapped_cancel() {
        try {
            RemoteContent.readCapped(ByteArrayInputStream(ByteArray(1000)), 2000, checkCancel = { throw IllegalStateException("cancel") })
            fail("should throw")
        } catch (e: IllegalStateException) {
            assertEquals("cancel", e.message)
        }
    }

    // ---- ファイル名 ----

    @Test
    fun contentDisposition() {
        assertEquals("route.gpx", RemoteContent.fileNameFromContentDisposition("attachment; filename=\"route.gpx\""))
        assertEquals("ルート 1.gpx", RemoteContent.fileNameFromContentDisposition("attachment; filename=\"x.gpx\"; filename*=UTF-8''%E3%83%AB%E3%83%BC%E3%83%88%201.gpx"))
        assertEquals("a b.gpx", RemoteContent.fileNameFromContentDisposition("inline; filename=a b.gpx"))
        assertEquals("a.gpx", RemoteContent.fileNameFromContentDisposition("attachment; filename=\"../../a.gpx\""))
        assertNull(RemoteContent.fileNameFromContentDisposition("attachment"))
        assertNull(RemoteContent.fileNameFromContentDisposition(null))
    }

    @Test
    fun fileNameFromUrl() {
        assertEquals("route 1.gpx", RemoteContent.fileNameFromUrl("https://raw.githubusercontent.com/o/r/main/route%201.gpx"))
        assertEquals("ルート.gpx", RemoteContent.fileNameFromUrl("https://example.com/%E3%83%AB%E3%83%BC%E3%83%88.gpx?x=1"))
        assertNull(RemoteContent.fileNameFromUrl("https://drive.usercontent.google.com/download?id=abc"))
        assertNull(RemoteContent.fileNameFromUrl("https://example.com/"))
    }

    // ---- Google ドライブの確認ページ ----

    @Test
    fun driveConfirm_form2024() {
        val url = RemoteContent.driveConfirmUrl(html("virus_scan_2024.html"), "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123&export=download")
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123&export=download&confirm=t&uuid=0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b",
            url,
        )
    }

    @Test
    fun driveConfirm_form2023WithQueryInAction() {
        val url = RemoteContent.driveConfirmUrl(html("virus_scan_2023.html"), "https://drive.google.com/uc?id=x&export=download")
        assertEquals(
            "https://drive.google.com/uc?id=1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123&export=download&confirm=t&uuid=11112222-3333-4444-5555-666677778888",
            url,
        )
    }

    @Test
    fun driveConfirm_oldLink() {
        val url = RemoteContent.driveConfirmUrl(html("virus_scan_link.html"), "https://drive.google.com/uc?id=x&export=download")
        assertEquals("https://drive.google.com/uc?export=download&confirm=Xy7z&id=1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123", url)
    }

    @Test
    fun driveConfirm_notFoundInOtherPages() {
        assertNull(RemoteContent.driveConfirmUrl(html("need_access.html"), "https://drive.usercontent.google.com/download?id=x"))
        assertNull(RemoteContent.driveConfirmUrl(html("quota.html"), "https://drive.usercontent.google.com/download?id=x"))
        // http の action は使わない
        assertNull(RemoteContent.driveConfirmUrl("<form id=\"download-form\" action=\"http://evil.example/d\"><input type=\"hidden\" name=\"confirm\" value=\"t\"></form>", "https://drive.usercontent.google.com/download"))
    }

    @Test
    fun driveNeedAccessAndQuota() {
        assertTrue(RemoteContent.looksLikeDriveNeedAccess(html("need_access.html")))
        assertFalse(RemoteContent.looksLikeDriveNeedAccess(html("virus_scan_2024.html")))
        assertTrue(RemoteContent.looksLikeDriveQuota(html("quota.html")))
        assertFalse(RemoteContent.looksLikeDriveQuota(html("need_access.html")))
    }
}
