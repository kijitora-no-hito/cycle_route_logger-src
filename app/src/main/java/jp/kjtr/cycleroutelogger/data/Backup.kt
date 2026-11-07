package jp.kjtr.cycleroutelogger.data

import android.util.Xml
import jp.kjtr.cycleroutelogger.gpx.GpxParser
import jp.kjtr.cycleroutelogger.gpx.GpxWriter
import jp.kjtr.cycleroutelogger.ranking.RankingBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val format: Int = 1,
    val app: String = "jp.kjtr.cycleroutelogger",
    val exportedAt: Long,
    val plans: List<PlanMeta>,
    val tracks: List<TrackMeta>,
    /** オンラインランキングの参加（playerId・secret・名前。0.12.0〜、参加していなければ無し）。読み込むと同じ参加者として続く。 */
    val ranking: RankingBackup? = null,
)

@Serializable
data class PlanMeta(
    val file: String,
    val name: String,
    val memo: String,
    val createdAt: Long,
    /** GPX の trk の順に対応する。 */
    val routes: List<RouteMeta>,
    /** GPX の wpt の順に対応する、URL から取り込んだ経由地の取り込み元（0.2.0 から。無ければ空）。 */
    val waypointSourceUrls: List<String?> = emptyList(),
)

@Serializable
data class RouteMeta(
    val name: String,
    val color: Int,
    val visible: Boolean,
    /** URL から取り込んだルートなら、その URL（0.2.0 から）。 */
    val sourceUrl: String? = null,
    /** 地図に描いたルートなら、編集できる形（制御点）の JSON（0.3.0 から）。 */
    val editShape: String? = null,
)

@Serializable
data class TrackMeta(
    val file: String,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val mode: String,
    val planName: String? = null,
    val autoPause: Boolean,
    val distanceM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double,
    val ascentM: Double,
    /** ファイルから取り込んだ記録なら元の形式（0.10.0 から。"fit,gpx" など）。 */
    val importFormat: String? = null,
)

/**
 * 全データの ZIP バックアップ。中身は manifest.json + プランごとの GPX（ルート = trk、経由地 = wpt）+ 記録ごとの GPX。
 * GPX はほかのアプリでも開ける。読み込みは**追加**（今あるデータは消さない）。
 */
class Backup(private val db: AppDatabase, private val plans: PlanRepository, private val tracks: TrackRepository) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    data class Counts(val plans: Int, val tracks: Int, val ranking: RankingBackup? = null)

    /** [ranking] = ランキングの参加の情報（参加していなければ null。ranking_server/docs/API.md §2）。 */
    suspend fun export(out: OutputStream, ranking: RankingBackup? = null): Counts = withContext(Dispatchers.IO) {
        val planList = plans.allPlans()
        val trackList = tracks.allFinished()
        val planMetas = ArrayList<PlanMeta>()
        val trackMetas = ArrayList<TrackMeta>()
        ZipOutputStream(out).use { zip ->
            val w = OutputStreamWriter(zip, Charsets.UTF_8)
            // 先に GPX を書き、最後に manifest（内容を集めてから）
            for (p in planList) {
                val file = "plans/plan_${p.id}.gpx"
                val data = plans.toGpx(p.id) ?: continue
                zip.putNextEntry(ZipEntry(file))
                GpxWriter.write(w, data)
                w.flush()
                zip.closeEntry()
                val routes = plans.routesNow(p.id).map { RouteMeta(it.name, it.color, it.visible, it.sourceUrl, it.editShape) }
                // toGpx の wpt と同じ順（waypointsNow）
                val wpSources = plans.waypointsNow(p.id).map { it.sourceUrl }.takeIf { l -> l.any { it != null } }.orEmpty()
                planMetas.add(PlanMeta(file, p.name, p.memo, p.createdAt, routes, wpSources))
            }
            for (t in trackList) {
                val file = "tracks/track_${t.id}.gpx"
                val data = tracks.toGpx(t.id) ?: continue
                zip.putNextEntry(ZipEntry(file))
                GpxWriter.write(w, data, t.startedAt)
                w.flush()
                zip.closeEntry()
                trackMetas.add(
                    TrackMeta(
                        file, t.name, t.startedAt, t.endedAt, t.mode, t.planName, t.autoPause,
                        t.distanceM, t.movingMs, t.maxSpeedMps, t.ascentM, t.importFormat,
                    ),
                )
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            w.write(json.encodeToString(BackupManifest.serializer(), BackupManifest(exportedAt = System.currentTimeMillis(), plans = planMetas, tracks = trackMetas, ranking = ranking)))
            w.flush()
            zip.closeEntry()
        }
        Counts(planMetas.size, trackMetas.size)
    }

    /** [zipFile]（一時ファイルにコピーしたもの）から追加で読み込む。 */
    suspend fun import(zipFile: File): Counts = withContext(Dispatchers.IO) {
        ZipFile(zipFile).use { zip ->
            val mEntry = zip.getEntry(MANIFEST) ?: throw IllegalArgumentException("no manifest")
            val manifest = zip.getInputStream(mEntry).use { json.decodeFromString(BackupManifest.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
            var planCount = 0
            var trackCount = 0
            for (pm in manifest.plans) {
                val e = zip.getEntry(pm.file) ?: continue
                val data = zip.getInputStream(e).use { GpxParser().parse(Xml.newPullParser(), it) }
                val now = System.currentTimeMillis()
                val planId = db.plans().insertPlan(PlanEntity(name = pm.name, memo = pm.memo, createdAt = pm.createdAt, updatedAt = now))
                data.tracks.forEachIndexed { i, t ->
                    val meta = pm.routes.getOrNull(i)
                    plans.insertRoute(
                        planId, meta?.name ?: t.name ?: pm.name,
                        meta?.color ?: PlanRepository.ROUTE_PALETTE[i % PlanRepository.ROUTE_PALETTE.size],
                        t.segments, now, null, meta?.visible ?: true, meta?.sourceUrl, meta?.editShape,
                    )
                }
                db.plans().insertWaypoints(
                    data.waypoints.mapIndexed { i, w -> PlanRepository.waypointFromGpx(planId, w, now + i, pm.waypointSourceUrls.getOrNull(i)) },
                )
                planCount++
            }
            for (tm in manifest.tracks) {
                val e = zip.getEntry(tm.file) ?: continue
                val data = zip.getInputStream(e).use { GpxParser().parse(Xml.newPullParser(), it) }
                val segs = data.tracks.flatMap { it.segments }
                val id = db.tracks().insertTrack(
                    TrackEntity(
                        name = tm.name, startedAt = tm.startedAt, endedAt = tm.endedAt, status = TrackEntity.STATUS_FINISHED,
                        mode = tm.mode, planName = tm.planName, autoPause = tm.autoPause, distanceM = tm.distanceM,
                        movingMs = tm.movingMs, maxSpeedMps = tm.maxSpeedMps, ascentM = tm.ascentM,
                        pointCount = segs.sumOf { it.size }, importFormat = tm.importFormat,
                    ),
                )
                // 取り込んだ記録の点は「取り込み」のまま戻す（0.10.0〜）
                val provider = if (tm.importFormat != null) TrackPointEntity.PROVIDER_IMPORT else TrackPointEntity.PROVIDER_UNKNOWN
                val pts = ArrayList<TrackPointEntity>()
                segs.forEachIndexed { si, s ->
                    for (p in s) pts.add(TrackPointEntity(trackId = id, segment = si, time = p.timeMs ?: tm.startedAt, lat = p.lat, lon = p.lon, ele = p.ele, accuracy = null, deviceSpeed = null, speed = null, provider = provider))
                }
                pts.chunked(5_000).forEach { db.tracks().insertPoints(it) }
                trackCount++
            }
            Counts(planCount, trackCount, manifest.ranking)
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
    }
}
