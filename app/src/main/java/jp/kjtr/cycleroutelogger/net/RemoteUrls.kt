package jp.kjtr.cycleroutelogger.net

import java.net.URI
import java.net.URLDecoder

/** URL の出どころ（取得の仕方とエラーの案内が変わる）。 */
enum class SourceKind { GOOGLE_DRIVE, DROPBOX, ONEDRIVE, GITHUB, OTHER }

/** 利用者が貼り付けた（QR から読んだ）URL を、GPX を直接ダウンロードできる URL に直したもの。 */
sealed interface ResolvedUrl {
    data class Ok(
        /** 利用者が渡した URL（前後の空白などを除いたもの）。プランに保存し、再読み込みに使う。 */
        val original: String,
        /** 実際に取りに行く URL（https）。 */
        val download: String,
        val kind: SourceKind,
    ) : ResolvedUrl

    data class Rejected(val reason: Reason) : ResolvedUrl

    enum class Reason {
        /** URL として読めない。 */
        NOT_URL,
        /** http など https 以外。 */
        NOT_HTTPS,
        /** Google ドライブのフォルダ。 */
        DRIVE_FOLDER,
        /** Google ドキュメント・スプレッドシートなど（GPX ではない）。 */
        GOOGLE_DOCS,
        /** Google ドライブだがファイルの ID が無い。 */
        DRIVE_NO_ID,
        /** Dropbox / GitHub などのフォルダ。 */
        FOLDER,
    }
}

/**
 * 共有リンクの書き換え（純ロジック。通信はしない）。
 *
 * - **https のみ**。スキームが無く「ホスト名/パス」の形ならば https を補う。文の中の URL は最初の https:// から取り出す。
 * - Google ドライブ: `/file/d/<ID>/…`・`/open?id=`・`/uc?id=`・`drive.usercontent.google.com/download?id=` →
 *   `https://drive.usercontent.google.com/download?id=<ID>&export=download`（resourcekey は引き継ぐ）。フォルダ・ドキュメントは非対応。
 * - Dropbox: `dl=0` → `dl=1`（無ければ足す）。フォルダ（`/scl/fo/`・`/sh/`）は非対応。
 * - OneDrive: `onedrive.live.com/redir|embed` → `/download`、SharePoint は `download=1`、
 *   それ以外（`1drv.ms` など）は共有 API（`api.onedrive.com/v1.0/shares/u!<base64url>/root/content`）。
 * - GitHub: `github.com/<o>/<r>/blob/<ref>/<path>` → `raw.githubusercontent.com/<o>/<r>/<ref>/<path>`、
 *   gist は `gist.githubusercontent.com/<user>/<id>/raw`。`/tree/` やリポジトリのトップは非対応。
 * - それ以外の https はそのまま。
 */
object RemoteUrls {
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"'「」]+""", RegexOption.IGNORE_CASE)
    private val HOST_LIKE = Regex("""^[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}(?::\d+)?(?:[/?#]\S*)?$""")
    private val DRIVE_FILE_ID = Regex("""/file/(?:u/\d+/)?d/([A-Za-z0-9_-]{10,})""")
    private val DRIVE_ID = Regex("""^[A-Za-z0-9_-]{10,}$""")

    fun resolve(input: String): ResolvedUrl {
        val text = cleanInput(input) ?: return ResolvedUrl.Rejected(ResolvedUrl.Reason.NOT_URL)
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme == "http") return ResolvedUrl.Rejected(ResolvedUrl.Reason.NOT_HTTPS)
        if (scheme != "https") return ResolvedUrl.Rejected(ResolvedUrl.Reason.NOT_URL)
        val uri = parse(text) ?: return ResolvedUrl.Rejected(ResolvedUrl.Reason.NOT_URL)
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return ResolvedUrl.Rejected(ResolvedUrl.Reason.NOT_URL)
        val path = uri.rawPath.orEmpty()
        val query = parseQuery(uri.rawQuery)

        fun ok(download: String, kind: SourceKind) = ResolvedUrl.Ok(text, download, kind)
        // そのまま取りに行くときは、日本語などを % で表した形（ASCII）にする
        val ascii = uri.toString()

        return when {
            host == "drive.google.com" || host == "drive.usercontent.google.com" ||
                (host == "docs.google.com" && (path.startsWith("/uc") || path.startsWith("/file/") || path.startsWith("/open"))) -> {
                if (path.contains("/folders/") || path.startsWith("/embeddedfolderview") || path.startsWith("/drive/")) {
                    return ResolvedUrl.Rejected(ResolvedUrl.Reason.DRIVE_FOLDER)
                }
                val id = DRIVE_FILE_ID.find(path)?.groupValues?.get(1)
                    ?: query.firstValue("id")?.takeIf { DRIVE_ID.matches(it) }
                    ?: return ResolvedUrl.Rejected(ResolvedUrl.Reason.DRIVE_NO_ID)
                ok(driveDownloadUrl(id, query.firstValue("resourcekey")), SourceKind.GOOGLE_DRIVE)
            }
            host == "docs.google.com" -> ResolvedUrl.Rejected(ResolvedUrl.Reason.GOOGLE_DOCS)

            host == "dropbox.com" || host == "www.dropbox.com" -> {
                if (path.startsWith("/scl/fo/") || path.startsWith("/sh/")) return ResolvedUrl.Rejected(ResolvedUrl.Reason.FOLDER)
                val q = query.filterNot { it.first == "dl" || it.first == "raw" } + ("dl" to "1")
                ok(build("https", "www.dropbox.com", path, q), SourceKind.DROPBOX)
            }
            host == "dl.dropboxusercontent.com" || host == "dl.dropbox.com" -> ok(ascii, SourceKind.DROPBOX)

            host == "1drv.ms" -> ok(oneDriveShareApi(ascii), SourceKind.ONEDRIVE)
            host == "onedrive.live.com" -> {
                val p = path.lowercase()
                if (p == "/redir" || p == "/embed" || p == "/redir.aspx" || p == "/embed.aspx") {
                    ok(build("https", host, "/download", query), SourceKind.ONEDRIVE)
                } else {
                    ok(oneDriveShareApi(ascii), SourceKind.ONEDRIVE)
                }
            }
            host.endsWith(".sharepoint.com") -> {
                val q = query.filterNot { it.first == "download" || it.first == "web" } + ("download" to "1")
                ok(build("https", host, path, q), SourceKind.ONEDRIVE)
            }

            host == "github.com" || host == "www.github.com" -> {
                val seg = path.trim('/').split('/').filter { it.isNotEmpty() }
                when {
                    seg.size >= 5 && seg[2] == "blob" ->
                        ok("https://raw.githubusercontent.com/${seg[0]}/${seg[1]}/" + seg.drop(3).joinToString("/"), SourceKind.GITHUB)
                    seg.size >= 5 && seg[2] == "raw" -> ok(ascii, SourceKind.GITHUB)
                    seg.size <= 2 || (seg.size >= 3 && seg[2] == "tree") -> ResolvedUrl.Rejected(ResolvedUrl.Reason.FOLDER)
                    else -> ok(ascii, SourceKind.GITHUB)
                }
            }
            host == "gist.github.com" -> {
                val seg = path.trim('/').split('/').filter { it.isNotEmpty() }
                if (seg.size >= 2) ok("https://gist.githubusercontent.com/${seg[0]}/${seg[1]}/raw", SourceKind.GITHUB) else ok(ascii, SourceKind.GITHUB)
            }
            host == "raw.githubusercontent.com" || host == "gist.githubusercontent.com" -> ok(ascii, SourceKind.GITHUB)

            else -> ok(ascii, SourceKind.OTHER)
        }
    }

    fun driveDownloadUrl(id: String, resourceKey: String? = null): String =
        "https://drive.usercontent.google.com/download?id=$id&export=download" + (resourceKey?.let { "&resourcekey=$it" } ?: "")

    /** OneDrive の共有 API（`u!` + 共有 URL の base64url、パディングなし）。 */
    fun oneDriveShareApi(shareUrl: String): String =
        "https://api.onedrive.com/v1.0/shares/u!" + base64Url(shareUrl.toByteArray(Charsets.UTF_8)) + "/root/content"

    /** 前後の空白・括弧・引用符を除き、文の中なら URL を取り出し、スキームが無ければ https を補う。 */
    private fun cleanInput(input: String): String? {
        var s = input.trim().trim('<', '>', '"', '\'', '「', '」').trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) {
            if (HOST_LIKE.matches(s)) s = "https://$s" else return null
        } else if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(s) || s.any { it.isWhitespace() }) {
            s = URL_IN_TEXT.find(s)?.value ?: return null
        }
        return s.trimEnd('.', ',', ')', '）', '。', '、')
    }

    /** 日本語や空白がそのまま入った URL も読めるように、ASCII 以外と空白を % で表してから解析する。 */
    private fun parse(s: String): URI? {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c <= 0x20 || c >= 0x7F || c == '"'.code || c == '<'.code || c == '>'.code || c == '\\'.code ||
                c == '^'.code || c == '`'.code || c == '{'.code || c == '|'.code || c == '}'.code
            ) {
                sb.append('%').append("%02X".format(c))
            } else {
                sb.append(c.toChar())
            }
        }
        return runCatching { URI(sb.toString()) }.getOrNull()
    }

    /** クエリを（名前, 値）の並びに。値は % のまま（組み立て直すときにそのまま使う）。名前は小文字にしない。 */
    private fun parseQuery(raw: String?): List<Pair<String, String>> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.map { kv ->
            val i = kv.indexOf('=')
            if (i < 0) kv to "" else kv.substring(0, i) to kv.substring(i + 1)
        }

    private fun List<Pair<String, String>>.firstValue(name: String): String? =
        firstOrNull { it.first == name }?.second?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    private fun build(scheme: String, host: String, rawPath: String, query: List<Pair<String, String>>): String {
        val q = query.joinToString("&") { if (it.second.isEmpty() && !it.first.contains('=')) it.first + "=" else it.first + "=" + it.second }
        return "$scheme://$host$rawPath" + if (q.isNotEmpty()) "?$q" else ""
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** base64url（パディングなし）。java.util.Base64 は API 26 から、android.util.Base64 は単体テストで使えないので自前。 */
    fun base64Url(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(B64[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(B64[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }
}
