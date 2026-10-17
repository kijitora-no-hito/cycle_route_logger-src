package jp.kjtr.cycleroutelogger.net

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** 取得した中身の種類。 */
enum class ContentKind { GPX, GPX_GZIP, HTML, XML_NOT_GPX, EMPTY, OTHER }

/** 取得した中身が上限を超えた。 */
class DownloadTooLargeException(val limit: Long) : IOException("too large (> $limit)")

/** 取得した中身の判定・ファイル名・Google ドライブの確認ページなど（純ロジック。通信はしない）。 */
object RemoteContent {
    /** 先頭何バイトを見て判定するか。 */
    private const val SNIFF_BYTES = 8 * 1024

    /** 中身の判定。gzip なら展開した先頭を見る。 */
    fun classify(bytes: ByteArray): ContentKind {
        if (bytes.isEmpty()) return ContentKind.EMPTY
        if (isGzip(bytes)) {
            val head = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { readUpTo(it, SNIFF_BYTES) }
            }.getOrNull() ?: return ContentKind.OTHER
            return when (val k = classifyText(head)) {
                ContentKind.GPX -> ContentKind.GPX_GZIP
                ContentKind.GPX_GZIP -> ContentKind.OTHER // 二重の gzip は扱わない
                else -> k
            }
        }
        return classifyText(bytes.copyOf(minOf(bytes.size, SNIFF_BYTES)))
    }

    /** [classify] が GPX / GPX_GZIP と判定した中身を、GPX の XML として読むストリームに。 */
    fun gpxStream(bytes: ByteArray): InputStream {
        val raw = ByteArrayInputStream(bytes)
        return if (isGzip(bytes)) BufferedInputStream(GZIPInputStream(raw)) else raw
    }

    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B

    private fun classifyText(head: ByteArray): ContentKind {
        val text = decodeHead(head).trimStart('﻿', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) return ContentKind.EMPTY
        val lower = text.lowercase()
        if (!lower.startsWith("<")) {
            return if (lower.contains("<html") || lower.contains("<!doctype html")) ContentKind.HTML else ContentKind.OTHER
        }
        // 先頭の宣言・コメント・DOCTYPE を飛ばして、最初の要素の名前を見る
        var i = 0
        var doctypeHtml = false
        while (i < lower.length) {
            val lt = lower.indexOf('<', i)
            if (lt < 0) break
            when {
                lower.startsWith("<?", lt) -> i = lower.indexOf("?>", lt).let { if (it < 0) lower.length else it + 2 }
                lower.startsWith("<!--", lt) -> i = lower.indexOf("-->", lt).let { if (it < 0) lower.length else it + 3 }
                lower.startsWith("<!", lt) -> {
                    if (lower.startsWith("<!doctype html", lt)) doctypeHtml = true
                    i = lower.indexOf('>', lt).let { if (it < 0) lower.length else it + 1 }
                }
                else -> {
                    val name = Regex("""^<([a-z0-9_.:-]+)""").find(lower.substring(lt))?.groupValues?.get(1) ?: return ContentKind.OTHER
                    val local = name.substringAfter(':')
                    return when {
                        local == "gpx" -> ContentKind.GPX
                        local == "html" || doctypeHtml -> ContentKind.HTML
                        else -> ContentKind.XML_NOT_GPX
                    }
                }
            }
        }
        return if (doctypeHtml) ContentKind.HTML else ContentKind.OTHER
    }

    private fun decodeHead(b: ByteArray): String = when {
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xFF) == 0xFF -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFE -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        else -> String(b, Charsets.UTF_8)
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * [input] を [max] バイトまで読む。超えたら [DownloadTooLargeException]。
     * [onProgress] は読んだバイト数（64 KB ごとと最後）。[checkCancel] は読むたびに呼ぶ（取り消しなら例外を投げる）。
     */
    fun readCapped(input: InputStream, max: Long, onProgress: (Long) -> Unit = {}, checkCancel: () -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        var lastReport = 0L
        while (true) {
            checkCancel()
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw DownloadTooLargeException(max)
            out.write(buf, 0, n)
            if (total - lastReport >= 64 * 1024) {
                lastReport = total
                onProgress(total)
            }
        }
        onProgress(total)
        return out.toByteArray()
    }

    /** Content-Disposition からファイル名（`filename*=UTF-8''…` を優先）。 */
    fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()?.let { v ->
            val s = v.trim('"')
            val parts = s.split('\'', limit = 3)
            val (charset, encoded) = if (parts.size == 3) parts[0].ifBlank { "UTF-8" } to parts[2] else "UTF-8" to s
            runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()?.let { safeName(it) }?.let { return it }
        }
        Regex("""filename\s*=\s*("((?:[^"\\]|\\.)*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val v = m.groupValues[2].ifEmpty { m.groupValues[1] }.trim().trim('"').replace("\\\"", "\"")
            return safeName(v)
        }
        return null
    }

    /** URL の最後のパスからファイル名（拡張子があるものだけ）。 */
    fun fileNameFromUrl(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        val last = path.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        val name = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        if (!name.contains('.') || name.startsWith('.')) return null
        return safeName(name)
    }

    private fun safeName(s: String): String? =
        s.substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotEmpty() && it.length <= 200 }

    // ---- Google ドライブ ----

    /**
     * Google ドライブの「ウイルス スキャンを実行できません」の確認ページから、本体をダウンロードする URL を取り出す。
     * - 2024 年以降: `<form id="download-form" action="https://drive.usercontent.google.com/download">` の hidden（id, export, confirm, uuid …）。
     * - それ以前: `<form id="downloadForm" action="…/uc?id=…&confirm=…">`、または `<a id="uc-download-link" href="/uc?export=download&confirm=…&id=…">`。
     * 見つからなければ null。返す URL は https のみ。
     */
    fun driveConfirmUrl(html: String, baseUrl: String): String? {
        val forms = Regex("""<form\b([^>]*)>(.*?)</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)
        for (f in forms) {
            val attrs = f.groupValues[1]
            val action = attr(attrs, "action")?.let { unescapeHtml(it) } ?: continue
            val id = attr(attrs, "id").orEmpty()
            if (!(id.contains("download", true) || action.contains("/download") || action.contains("/uc"))) continue
            val params = ArrayList<Pair<String, String>>()
            for (inp in Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(f.groupValues[2])) {
                val a = inp.groupValues[1]
                if (!attr(a, "type").equals("hidden", ignoreCase = true)) continue
                val name = attr(a, "name")?.let { unescapeHtml(it) } ?: continue
                params.add(name to unescapeHtml(attr(a, "value").orEmpty()))
            }
            val hasConfirm = params.any { it.first == "confirm" } || action.contains("confirm=")
            if (!hasConfirm) continue
            val base = absolute(baseUrl, action) ?: continue
            val q = params.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
            val url = if (q.isEmpty()) base else base + (if (base.contains('?')) "&" else "?") + q
            if (url.startsWith("https://", ignoreCase = true)) return url
        }
        // 古い形: ダウンロードのリンク
        Regex("""href\s*=\s*"([^"]*confirm=[^"]*)"""", RegexOption.IGNORE_CASE).find(html)?.let { m ->
            val url = absolute(baseUrl, unescapeHtml(m.groupValues[1])) ?: return null
            if (url.startsWith("https://", ignoreCase = true)) return url
        }
        return null
    }

    /** Google ドライブがログイン画面・「アクセス権が必要」を返したらしいか。 */
    fun looksLikeDriveNeedAccess(html: String): Boolean {
        val s = html.lowercase()
        return s.contains("accounts.google.com/servicelogin") || s.contains("accounts.google.com/v3/signin") ||
            s.contains("you need access") || s.contains("request access") || s.contains("アクセス権が必要") ||
            s.contains("アクセス権をリクエスト") || s.contains("sign in - google accounts") || s.contains("ログイン - google アカウント")
    }

    /** Google ドライブの「ダウンロードの上限を超えました」（短時間に多くの人がダウンロードした）らしいか。 */
    fun looksLikeDriveQuota(html: String): Boolean {
        val s = html.lowercase()
        return s.contains("too many users have viewed or downloaded") || s.contains("download quota") ||
            s.contains("ダウンロードの上限") || s.contains("表示またはダウンロードしたユーザーの数が多すぎます")
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(attrs)?.let { m ->
            m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
        }

    private fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#x3d;", "=", ignoreCase = true).replace("&#61;", "=")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun absolute(base: String, ref: String): String? = runCatching { URI(base).resolve(URI(ref.replace(" ", "%20"))).toString() }.getOrNull()
}
