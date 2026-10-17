package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.net.RemoteUrls
import jp.kjtr.cycleroutelogger.net.ResolvedUrl
import jp.kjtr.cycleroutelogger.net.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlsTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123"
    private val driveDl = "https://drive.usercontent.google.com/download?id=$id&export=download"

    private fun ok(input: String): ResolvedUrl.Ok {
        val r = RemoteUrls.resolve(input)
        assertTrue("expected Ok for $input but $r", r is ResolvedUrl.Ok)
        return r as ResolvedUrl.Ok
    }

    private fun rejected(input: String): ResolvedUrl.Reason {
        val r = RemoteUrls.resolve(input)
        assertTrue("expected Rejected for $input but $r", r is ResolvedUrl.Rejected)
        return (r as ResolvedUrl.Rejected).reason
    }

    // ---- Google ドライブ ----

    @Test
    fun drive_fileView() {
        val r = ok("https://drive.google.com/file/d/$id/view?usp=sharing")
        assertEquals(SourceKind.GOOGLE_DRIVE, r.kind)
        assertEquals(driveDl, r.download)
        assertEquals("https://drive.google.com/file/d/$id/view?usp=sharing", r.original)
    }

    @Test
    fun drive_fileViewWithAccountIndex() {
        assertEquals(driveDl, ok("https://drive.google.com/file/u/1/d/$id/view").download)
    }

    @Test
    fun drive_open() {
        assertEquals(driveDl, ok("https://drive.google.com/open?id=$id").download)
    }

    @Test
    fun drive_uc() {
        assertEquals(driveDl, ok("https://drive.google.com/uc?id=$id&export=download").download)
        assertEquals(driveDl, ok("https://docs.google.com/uc?export=download&id=$id").download)
    }

    @Test
    fun drive_usercontent() {
        assertEquals(driveDl, ok("https://drive.usercontent.google.com/download?id=$id&export=download&authuser=0").download)
    }

    @Test
    fun drive_resourceKeyKept() {
        assertEquals("$driveDl&resourcekey=0-abc", ok("https://drive.google.com/file/d/$id/view?usp=sharing&resourcekey=0-abc").download)
    }

    @Test
    fun drive_folderRejected() {
        assertEquals(ResolvedUrl.Reason.DRIVE_FOLDER, rejected("https://drive.google.com/drive/folders/$id?usp=sharing"))
        assertEquals(ResolvedUrl.Reason.DRIVE_FOLDER, rejected("https://drive.google.com/drive/u/0/folders/$id"))
    }

    @Test
    fun drive_docsRejected() {
        assertEquals(ResolvedUrl.Reason.GOOGLE_DOCS, rejected("https://docs.google.com/document/d/$id/edit"))
        assertEquals(ResolvedUrl.Reason.GOOGLE_DOCS, rejected("https://docs.google.com/spreadsheets/d/$id/edit#gid=0"))
    }

    @Test
    fun drive_noId() {
        assertEquals(ResolvedUrl.Reason.DRIVE_NO_ID, rejected("https://drive.google.com/open"))
    }

    // ---- Dropbox ----

    @Test
    fun dropbox_dl0to1() {
        val r = ok("https://www.dropbox.com/scl/fi/abc123/route.gpx?rlkey=xyz&dl=0")
        assertEquals(SourceKind.DROPBOX, r.kind)
        assertEquals("https://www.dropbox.com/scl/fi/abc123/route.gpx?rlkey=xyz&dl=1", r.download)
    }

    @Test
    fun dropbox_addsDl() {
        assertEquals("https://www.dropbox.com/s/abc/route.gpx?dl=1", ok("https://dropbox.com/s/abc/route.gpx").download)
    }

    @Test
    fun dropbox_folderRejected() {
        assertEquals(ResolvedUrl.Reason.FOLDER, rejected("https://www.dropbox.com/scl/fo/abc/def?rlkey=x&dl=0"))
    }

    // ---- OneDrive ----

    @Test
    fun oneDrive_shortLinkToShareApi() {
        val r = ok("https://1drv.ms/u/s!AbCdEf")
        assertEquals(SourceKind.ONEDRIVE, r.kind)
        // u! + base64url("https://1drv.ms/u/s!AbCdEf")（パディングなし）
        assertEquals("https://api.onedrive.com/v1.0/shares/u!aHR0cHM6Ly8xZHJ2Lm1zL3UvcyFBYkNkRWY/root/content", r.download)
    }

    @Test
    fun oneDrive_redirToDownload() {
        assertEquals(
            "https://onedrive.live.com/download?cid=ABC&resid=ABC%21123&authkey=xyz",
            ok("https://onedrive.live.com/redir?cid=ABC&resid=ABC%21123&authkey=xyz").download,
        )
    }

    @Test
    fun sharePoint_download1() {
        assertEquals(
            "https://contoso-my.sharepoint.com/:u:/g/personal/a/EAbc?e=x&download=1",
            ok("https://contoso-my.sharepoint.com/:u:/g/personal/a/EAbc?e=x").download,
        )
    }

    @Test
    fun base64Url_matchesStandard() {
        // "ab?" → YWI_（+/ を -_ に、= なし）
        assertEquals("YWI_", RemoteUrls.base64Url("ab?".toByteArray()))
        assertEquals("YQ", RemoteUrls.base64Url("a".toByteArray()))
        assertEquals("YWI", RemoteUrls.base64Url("ab".toByteArray()))
    }

    // ---- GitHub ----

    @Test
    fun github_blobToRaw() {
        val r = ok("https://github.com/owner/repo/blob/main/data/tracks/route%201.gpx")
        assertEquals(SourceKind.GITHUB, r.kind)
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/data/tracks/route%201.gpx", r.download)
    }

    @Test
    fun github_blobQueryDropped() {
        assertEquals("https://raw.githubusercontent.com/o/r/v1.0/a.gpx", ok("https://github.com/o/r/blob/v1.0/a.gpx?plain=1").download)
    }

    @Test
    fun github_rawAndRawHostAsIs() {
        assertEquals("https://github.com/o/r/raw/main/a.gpx", ok("https://github.com/o/r/raw/main/a.gpx").download)
        assertEquals("https://raw.githubusercontent.com/o/r/main/a.gpx", ok("https://raw.githubusercontent.com/o/r/main/a.gpx").download)
    }

    @Test
    fun github_gist() {
        assertEquals("https://gist.githubusercontent.com/user/abc123/raw", ok("https://gist.github.com/user/abc123").download)
    }

    @Test
    fun github_treeAndRepoRejected() {
        assertEquals(ResolvedUrl.Reason.FOLDER, rejected("https://github.com/o/r/tree/main/data"))
        assertEquals(ResolvedUrl.Reason.FOLDER, rejected("https://github.com/o/r"))
    }

    // ---- その他・入力のゆれ ----

    @Test
    fun other_asIs() {
        val r = ok("https://example.com/tracks/route.gpx?x=1")
        assertEquals(SourceKind.OTHER, r.kind)
        assertEquals("https://example.com/tracks/route.gpx?x=1", r.download)
    }

    @Test
    fun httpRejected() {
        assertEquals(ResolvedUrl.Reason.NOT_HTTPS, rejected("http://example.com/route.gpx"))
        assertEquals(ResolvedUrl.Reason.NOT_HTTPS, rejected("HTTP://drive.google.com/file/d/$id/view"))
    }

    @Test
    fun notUrlRejected() {
        assertEquals(ResolvedUrl.Reason.NOT_URL, rejected(""))
        assertEquals(ResolvedUrl.Reason.NOT_URL, rejected("ただの文字"))
        assertEquals(ResolvedUrl.Reason.NOT_URL, rejected("ftp://example.com/a.gpx"))
        assertEquals(ResolvedUrl.Reason.NOT_URL, rejected("<gpx version=\"1.1\"></gpx>"))
    }

    @Test
    fun schemeAddedAndTextTrimmed() {
        assertEquals(driveDl, ok("  drive.google.com/file/d/$id/view  ").download)
        assertEquals("https://example.com/a.gpx", ok("ルートはこちら https://example.com/a.gpx です").download)
        assertEquals("https://example.com/a.gpx", ok("<https://example.com/a.gpx>").download)
    }

    @Test
    fun nonAsciiPathAccepted() {
        val r = ok("https://example.com/ルート/走る.gpx")
        // 保存するのは渡された形、取りに行くのは % で表した形
        assertEquals("https://example.com/ルート/走る.gpx", r.original)
        assertEquals("https://example.com/%E3%83%AB%E3%83%BC%E3%83%88/%E8%B5%B0%E3%82%8B.gpx", r.download)
    }
}
