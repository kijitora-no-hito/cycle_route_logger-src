package jp.kjtr.cycleroutelogger.photo

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import jp.kjtr.cycleroutelogger.data.AppDatabase
import jp.kjtr.cycleroutelogger.data.PhotoEntity
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 写真の登録・取り込み・縮小版・削除と、画像のフォトライブラリへの保存。 */
class PhotoRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.photos()
    private val resolver get() = context.contentResolver
    private val thumbDir: File get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun forTrack(trackId: Long): Flow<List<PhotoEntity>> = dao.forTrack(trackId)
    suspend fun forTrackNow(trackId: Long): List<PhotoEntity> = dao.forTrackNow(trackId)
    /** 記録に紐づいた写真すべて（まとめの写真の数・地図の写真の位置）。 */
    fun linked(): Flow<List<PhotoEntity>> = dao.linked()

    /**
     * アプリ内カメラで撮ってフォトライブラリに保存した写真を登録する。位置は撮ったときの測位 [fix]、無ければ記録の軌跡から時刻で補間。
     * EXIF に撮影日時（とオフセット）、[writeLocation] なら GPS を書き込む。
     */
    suspend fun addCaptured(uri: Uri, takenAt: Long, trackId: Long?, fix: LatLon?, writeLocation: Boolean): PhotoEntity = withContext(Dispatchers.IO) {
        var loc = fix
        var source: String? = if (fix != null) PhotoEntity.LOC_GPS else null
        if (loc == null && trackId != null) {
            loc = PhotoLocator.interpolate(db.tracks().points(trackId), takenAt)
            if (loc != null) source = PhotoEntity.LOC_TRACK
        }
        runCatching { writeExif(uri, takenAt, if (writeLocation) loc else null) }
        val thumb = makeThumb(uri)
        val p = PhotoEntity(
            trackId = trackId, uri = uri.toString(), takenAt = takenAt, lat = loc?.lat, lon = loc?.lon,
            locationSource = source, thumbPath = thumb, ownCapture = true, createdAt = System.currentTimeMillis(),
        )
        p.copy(id = dao.insert(p))
    }

    data class ImportResult(val added: Int, val outsideTime: Int, val noTime: Int, val duplicate: Int, val noLocation: Int)

    /**
     * Photo Picker で選んだ写真を記録 [track] に紐づける。EXIF の撮影日時で記録の時間帯（前後 10 分）に入るものだけ。
     * 位置は EXIF の GPS、無ければ軌跡から時刻で補間。
     */
    suspend fun importPicked(uris: List<Uri>, track: TrackEntity): ImportResult = withContext(Dispatchers.IO) {
        val points = db.tracks().points(track.id)
        var added = 0; var outside = 0; var noTime = 0; var dup = 0; var noLoc = 0
        for (uri in uris) {
            runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (dao.countUri(uri.toString(), track.id) > 0) {
                dup++; continue
            }
            val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
            val takenAt = exif?.let {
                ExifTime.parse(
                    it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_DATETIME),
                    it.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: it.getAttribute(ExifInterface.TAG_OFFSET_TIME),
                    it.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                )
            }
            if (takenAt == null) {
                noTime++; continue
            }
            if (!PhotoLocator.belongsTo(track, takenAt)) {
                outside++; continue
            }
            if (dao.countNear(track.id, takenAt - 1000, takenAt + 1000) > 0) {
                dup++; continue
            }
            var loc: LatLon? = exif.let {
                ExifGps.toLatLon(
                    it.getAttribute(ExifInterface.TAG_GPS_LATITUDE), it.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF),
                    it.getAttribute(ExifInterface.TAG_GPS_LONGITUDE), it.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF),
                )
            }?.let { LatLon(it.first, it.second) }
            var source: String? = if (loc != null) PhotoEntity.LOC_EXIF else null
            if (loc == null) {
                loc = PhotoLocator.interpolate(points, takenAt)
                if (loc != null) source = PhotoEntity.LOC_TRACK else noLoc++
            }
            dao.insert(
                PhotoEntity(
                    trackId = track.id, uri = uri.toString(), takenAt = takenAt, lat = loc?.lat, lon = loc?.lon,
                    locationSource = source, thumbPath = makeThumb(uri), ownCapture = false, createdAt = System.currentTimeMillis(),
                ),
            )
            added++
        }
        ImportResult(added, outside, noTime, dup, noLoc)
    }

    /** アプリ内の紐づけ（と縮小版）だけ外す。 */
    suspend fun unlink(p: PhotoEntity) = withContext(Dispatchers.IO) {
        dao.delete(p.id)
        p.thumbPath?.let { File(it).delete() }
        Unit
    }

    /**
     * フォトライブラリから消す。すぐ消せたら null、利用者の確認が要るならその IntentSender（Android 10+ で自分のでない写真など）。
     * 消せなければ例外。
     */
    fun deleteFromLibrary(uri: Uri): IntentSender? {
        return try {
            resolver.delete(uri, null, null)
            null
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender
                Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> throw e
            }
        }
    }

    /** EXIF に撮影日時・オフセットと（あれば）GPS を書く。 */
    private fun writeExif(uri: Uri, takenAt: Long, loc: LatLon?) {
        resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            val tz = TimeZone.getDefault()
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ExifTime.format(takenAt, tz))
            exif.setAttribute(ExifInterface.TAG_DATETIME, ExifTime.format(takenAt, tz))
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifTime.formatOffset(takenAt, tz))
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "%03d".format(Locale.ROOT, takenAt % 1000))
            if (loc != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, ExifGps.toRational(loc.lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, ExifGps.latRef(loc.lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, ExifGps.toRational(loc.lon))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, ExifGps.lonRef(loc.lon))
            } else {
                for (t in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF)) {
                    exif.setAttribute(t, null)
                }
            }
            exif.saveAttributes()
        }
    }

    /** 縮小版（長辺 [THUMB_PX]）を files/thumbs に作り、パスを返す。 */
    private fun makeThumb(uri: Uri): String? = runCatching {
        val bmp = loadBitmap(uri, THUMB_PX) ?: return null
        val f = File(thumbDir, "t_${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.absolutePath
    }.getOrNull()

    /** 画像を長辺 [maxPx] 程度に縮めて読む（EXIF の向きを反映）。 */
    fun loadBitmap(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
        val rot = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } }.getOrNull() ?: 0
        if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    }.getOrNull()

    /** 写真を読む（本体が読めなければ縮小版）。 */
    fun loadPhoto(p: PhotoEntity, maxPx: Int): Bitmap? =
        loadBitmap(Uri.parse(p.uri), maxPx) ?: p.thumbPath?.let { BitmapFactory.decodeFile(it) }

    /** 生成した画像をフォトライブラリ（Pictures/自転車ルートロガー）に JPEG で保存。 */
    suspend fun saveToLibrary(bmp: Bitmap, baseName: String): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val ok = runCatching { resolver.openOutputStream(uri)?.use { out: OutputStream -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) } }.getOrNull() == true
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }

    companion object {
        /** フォトライブラリのアルバム（Pictures/ の下のフォルダ）。 */
        const val ALBUM = "自転車ルートロガー"
        const val THUMB_PX = 320

        /** カメラで撮った写真のファイル名。 */
        fun captureName(timeMs: Long): String = "CRL_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date(timeMs))
    }
}
