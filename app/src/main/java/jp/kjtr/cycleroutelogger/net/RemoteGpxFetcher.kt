package jp.kjtr.cycleroutelogger.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CancellationException

/** URL から GPX を取れなかった理由。メッセージは呼ぶ側（画面）で決める。 */
class RemoteGpxException(val reason: Reason, val httpCode: Int = 0, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        NOT_URL, NOT_HTTPS, DRIVE_FOLDER, GOOGLE_DOCS, DRIVE_NO_ID, FOLDER,
        /** リダイレクト先が https でない。 */
        REDIRECT_NOT_HTTPS,
        TOO_MANY_REDIRECTS,
        /** Google ドライブ: ログイン画面・アクセス権が必要（共有設定が「リンクを知っている全員」でない）。 */
        NEED_SHARING,
        /** Google ドライブ: ダウンロードの上限（しばらく待つ）。 */
        RATE_LIMITED,
        NOT_FOUND,
        ACCESS_DENIED,
        HTTP_ERROR,
        /** HTML が返った（ダウンロードできるリンクではない）。 */
        NOT_DOWNLOADABLE,
        /** OneDrive の共有リンクから取れなかった。 */
        ONEDRIVE_UNSUPPORTED,
        NOT_GPX,
        EMPTY,
        TOO_LARGE,
        TIMEOUT,
        NETWORK,
    }
}

/** 取得した GPX（まだ解析していない）。 */
class FetchedGpx(
    val bytes: ByteArray,
    val kind: ContentKind,
    /** Content-Disposition か URL から。無ければ null（GPX 内の name を使う）。 */
    val fileName: String?,
    val finalUrl: String,
)

/**
 * 利用者が指定した URL から GPX を取る（HttpURLConnection。呼ぶ側が IO スレッドで呼ぶ）。
 *
 * - https のみ。リダイレクトは自分でたどり、https 同士で最大 [MAX_REDIRECTS] 回。
 * - 大きさの上限 [maxBytes]（Content-Length で先に断り、読みながらも数える）。gzip の中身は GPX の解析側の上限（64 MB）で止まる。
 * - Google ドライブの確認ページ（大きいファイルの「ウイルス スキャンを実行できません」）は解析して本体を取り直す（2 回まで）。
 * - [abort] で接続を切って止める（読み込み中のブロックも解ける）。
 */
class RemoteGpxFetcher(
    private val userAgent: String,
    private val maxBytes: Long = MAX_DOWNLOAD_BYTES,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var aborted = false

    fun abort() {
        aborted = true
        runCatching { current?.disconnect() }
    }

    private fun checkAborted() {
        if (aborted) throw CancellationException("aborted")
    }

    /** [onProgress] は（読んだバイト数, 全体の大きさ（分かれば））。 */
    fun fetch(input: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }): FetchedGpx {
        val resolved = when (val r = RemoteUrls.resolve(input)) {
            is ResolvedUrl.Ok -> r
            is ResolvedUrl.Rejected -> throw RemoteGpxException(
                when (r.reason) {
                    ResolvedUrl.Reason.NOT_URL -> RemoteGpxException.Reason.NOT_URL
                    ResolvedUrl.Reason.NOT_HTTPS -> RemoteGpxException.Reason.NOT_HTTPS
                    ResolvedUrl.Reason.DRIVE_FOLDER -> RemoteGpxException.Reason.DRIVE_FOLDER
                    ResolvedUrl.Reason.GOOGLE_DOCS -> RemoteGpxException.Reason.GOOGLE_DOCS
                    ResolvedUrl.Reason.DRIVE_NO_ID -> RemoteGpxException.Reason.DRIVE_NO_ID
                    ResolvedUrl.Reason.FOLDER -> RemoteGpxException.Reason.FOLDER
                },
            )
        }
        val drive = resolved.kind == SourceKind.GOOGLE_DRIVE
        val oneDrive = resolved.kind == SourceKind.ONEDRIVE
        var url = resolved.download
        var redirects = 0
        var confirmTries = 0
        try {
            while (true) {
                checkAborted()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/gpx+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8")
                }
                current = conn
                try {
                    val code = conn.responseCode
                    if (code in 300..399 && code != 304) {
                        val loc = conn.getHeaderField("Location") ?: throw RemoteGpxException(RemoteGpxException.Reason.HTTP_ERROR, code)
                        val next = URL(URL(url), loc)
                        if (!next.protocol.equals("https", ignoreCase = true)) throw RemoteGpxException(RemoteGpxException.Reason.REDIRECT_NOT_HTTPS)
                        if (++redirects > MAX_REDIRECTS) throw RemoteGpxException(RemoteGpxException.Reason.TOO_MANY_REDIRECTS)
                        if (next.host.equals("accounts.google.com", ignoreCase = true)) {
                            throw RemoteGpxException(if (drive) RemoteGpxException.Reason.NEED_SHARING else RemoteGpxException.Reason.ACCESS_DENIED)
                        }
                        url = next.toString()
                        continue
                    }
                    when {
                        code == 401 || code == 403 -> throw RemoteGpxException(
                            when {
                                drive -> RemoteGpxException.Reason.NEED_SHARING
                                oneDrive -> RemoteGpxException.Reason.ONEDRIVE_UNSUPPORTED
                                else -> RemoteGpxException.Reason.ACCESS_DENIED
                            },
                            code,
                        )
                        code == 404 || code == 410 -> throw RemoteGpxException(
                            if (oneDrive) RemoteGpxException.Reason.ONEDRIVE_UNSUPPORTED else RemoteGpxException.Reason.NOT_FOUND, code,
                        )
                        code == 429 -> throw RemoteGpxException(RemoteGpxException.Reason.RATE_LIMITED, code)
                        code !in 200..299 -> throw RemoteGpxException(RemoteGpxException.Reason.HTTP_ERROR, code)
                    }
                    val length = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                    if (length != null && length > maxBytes) throw RemoteGpxException(RemoteGpxException.Reason.TOO_LARGE)
                    val bytes = conn.inputStream.use { RemoteContent.readCapped(it, maxBytes, { n -> onProgress(n, length) }, ::checkAborted) }
                    val fileName = RemoteContent.fileNameFromContentDisposition(conn.getHeaderField("Content-Disposition"))
                        ?: RemoteContent.fileNameFromUrl(url)
                        ?: RemoteContent.fileNameFromUrl(resolved.original)
                    when (val kind = RemoteContent.classify(bytes)) {
                        ContentKind.GPX, ContentKind.GPX_GZIP -> return FetchedGpx(bytes, kind, fileName, url)
                        ContentKind.EMPTY -> throw RemoteGpxException(RemoteGpxException.Reason.EMPTY)
                        ContentKind.XML_NOT_GPX, ContentKind.OTHER -> throw RemoteGpxException(RemoteGpxException.Reason.NOT_GPX)
                        ContentKind.HTML -> {
                            val html = String(bytes, Charsets.UTF_8)
                            if (drive) {
                                val confirm = if (confirmTries < 2) RemoteContent.driveConfirmUrl(html, url) else null
                                when {
                                    confirm != null -> {
                                        confirmTries++
                                        url = confirm
                                        continue
                                    }
                                    RemoteContent.looksLikeDriveQuota(html) -> throw RemoteGpxException(RemoteGpxException.Reason.RATE_LIMITED)
                                    else -> throw RemoteGpxException(RemoteGpxException.Reason.NEED_SHARING)
                                }
                            }
                            throw RemoteGpxException(if (oneDrive) RemoteGpxException.Reason.ONEDRIVE_UNSUPPORTED else RemoteGpxException.Reason.NOT_DOWNLOADABLE)
                        }
                    }
                } finally {
                    current = null
                    runCatching { conn.disconnect() }
                }
            }
        } catch (e: RemoteGpxException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadTooLargeException) {
            throw RemoteGpxException(RemoteGpxException.Reason.TOO_LARGE, cause = e)
        } catch (e: SocketTimeoutException) {
            if (aborted) throw CancellationException("aborted")
            throw RemoteGpxException(RemoteGpxException.Reason.TIMEOUT, cause = e)
        } catch (e: InterruptedIOException) {
            if (aborted) throw CancellationException("aborted")
            throw RemoteGpxException(RemoteGpxException.Reason.TIMEOUT, cause = e)
        } catch (e: IOException) {
            if (aborted) throw CancellationException("aborted")
            throw RemoteGpxException(RemoteGpxException.Reason.NETWORK, cause = e)
        }
    }

    companion object {
        /**
         * ダウンロードの上限（20 MB）。ファイルから読むときの上限 64 MB より小さくしてある（モバイル回線で待たせない・
         * メモリに持つため）。gzip で圧縮されていれば、展開後は GPX の解析の上限（64 MB）まで読める。
         */
        const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024
        const val MAX_REDIRECTS = 5
    }
}
