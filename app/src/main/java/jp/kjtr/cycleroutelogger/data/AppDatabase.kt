package jp.kjtr.cycleroutelogger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query(
        """
        SELECT p.id, p.name, p.memo, p.createdAt, p.updatedAt,
          (SELECT COUNT(*) FROM route r WHERE r.planId = p.id) AS routeCount,
          (SELECT COUNT(*) FROM waypoint w WHERE w.planId = p.id) AS waypointCount,
          (SELECT COALESCE(SUM(r.distanceM), 0) FROM route r WHERE r.planId = p.id) AS distanceM,
          (SELECT COALESCE(SUM(r.ascentM), 0) FROM route r WHERE r.planId = p.id) AS ascentM
        FROM plan p ORDER BY p.updatedAt DESC, p.id DESC
        """
    )
    fun summaries(): Flow<List<PlanSummary>>

    @Query("SELECT * FROM plan WHERE id = :id")
    fun plan(id: Long): Flow<PlanEntity?>

    @Query("SELECT * FROM plan WHERE id = :id")
    suspend fun planNow(id: Long): PlanEntity?

    @Query("SELECT * FROM plan ORDER BY id")
    suspend fun allPlans(): List<PlanEntity>

    @Insert
    suspend fun insertPlan(p: PlanEntity): Long

    @Update
    suspend fun updatePlan(p: PlanEntity)

    @Query("UPDATE plan SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM plan WHERE id = :id")
    suspend fun deletePlan(id: Long)

    // ルート
    @Query("SELECT * FROM route WHERE planId = :planId ORDER BY sortOrder, id")
    fun routes(planId: Long): Flow<List<RouteEntity>>

    @Query("SELECT * FROM route WHERE planId = :planId ORDER BY sortOrder, id")
    suspend fun routesNow(planId: Long): List<RouteEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM route WHERE planId = :planId")
    suspend fun maxSortOrder(planId: Long): Int

    @Insert
    suspend fun insertRoute(r: RouteEntity): Long

    @Update
    suspend fun updateRoute(r: RouteEntity)

    @Update
    suspend fun updateRoutes(list: List<RouteEntity>)

    @Query("DELETE FROM route WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Insert
    suspend fun insertRoutePoints(points: List<RoutePointEntity>)

    @Query("SELECT * FROM route_point WHERE routeId = :routeId ORDER BY seq")
    suspend fun routePoints(routeId: Long): List<RoutePointEntity>

    // 経由地
    @Query("SELECT * FROM waypoint WHERE planId = :planId ORDER BY createdAt, id")
    fun waypoints(planId: Long): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoint WHERE planId = :planId ORDER BY createdAt, id")
    suspend fun waypointsNow(planId: Long): List<WaypointEntity>

    @Insert
    suspend fun insertWaypoint(w: WaypointEntity): Long

    @Insert
    suspend fun insertWaypoints(list: List<WaypointEntity>)

    @Update
    suspend fun updateWaypoint(w: WaypointEntity)

    @Query("DELETE FROM waypoint WHERE id = :id")
    suspend fun deleteWaypoint(id: Long)

    @Query("SELECT * FROM waypoint WHERE id = :id")
    suspend fun waypointNow(id: Long): WaypointEntity?

    // URL からの取り込み（版 2）
    @Query(
        """
        SELECT sourceUrl FROM route WHERE planId = :planId AND sourceUrl IS NOT NULL
        UNION SELECT sourceUrl FROM waypoint WHERE planId = :planId AND sourceUrl IS NOT NULL
        """
    )
    fun sourceUrls(planId: Long): Flow<List<String>>

    @Query("SELECT * FROM route WHERE planId = :planId AND sourceUrl = :url ORDER BY sortOrder, id")
    suspend fun routesFromSource(planId: Long, url: String): List<RouteEntity>

    @Query("SELECT id FROM waypoint WHERE planId = :planId AND sourceUrl = :url")
    suspend fun waypointIdsFromSource(planId: Long, url: String): List<Long>

    @Query("DELETE FROM waypoint WHERE planId = :planId AND sourceUrl = :url")
    suspend fun deleteWaypointsFromSource(planId: Long, url: String)

    // 描いたルートの保存し直し（版 3）
    @Query("DELETE FROM route_point WHERE routeId = :routeId")
    suspend fun deleteRoutePoints(routeId: Long)

    @Query("SELECT * FROM route WHERE id = :id")
    suspend fun routeNow(id: Long): RouteEntity?

    @Transaction
    suspend fun replaceRoutePoints(r: RouteEntity, segments: List<List<RoutePointEntity>>) {
        updateRoute(r)
        deleteRoutePoints(r.id)
        val all = ArrayList<RoutePointEntity>()
        var seq = 0
        segments.forEachIndexed { si, seg -> for (p in seg) all.add(p.copy(routeId = r.id, seq = seq++, segment = si)) }
        all.chunked(5_000).forEach { insertRoutePoints(it) }
    }

    /** ルート 1 本を点ごと追加（[RouteEntity.sortOrder] は末尾）。 */
    @Transaction
    suspend fun insertRouteWithPoints(r: RouteEntity, segments: List<List<RoutePointEntity>>): Long {
        val id = insertRoute(r.copy(sortOrder = maxSortOrder(r.planId) + 1))
        val all = ArrayList<RoutePointEntity>()
        var seq = 0
        segments.forEachIndexed { si, seg ->
            for (p in seg) all.add(p.copy(routeId = id, seq = seq++, segment = si))
        }
        all.chunked(5_000).forEach { insertRoutePoints(it) }
        return id
    }
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM track WHERE status = 2 ORDER BY startedAt DESC")
    fun finished(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE id = :id")
    fun track(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun trackNow(id: Long): TrackEntity?

    /** 記録の途中で終わったもの（アプリやサービスが落ちた）。 */
    @Query("SELECT * FROM track WHERE status != 2 ORDER BY startedAt DESC LIMIT 1")
    suspend fun unfinished(): TrackEntity?

    @Query("SELECT * FROM track WHERE status = 2 ORDER BY startedAt")
    suspend fun allFinished(): List<TrackEntity>

    /** すべての記録（取り込みの重複の判定。0.10.0）。 */
    @Query("SELECT * FROM track ORDER BY startedAt")
    suspend fun all(): List<TrackEntity>

    @Insert
    suspend fun insertTrack(t: TrackEntity): Long

    @Update
    suspend fun updateTrack(t: TrackEntity)

    @Query("UPDATE track SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM track WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    @Insert
    suspend fun insertPoint(p: TrackPointEntity): Long

    @Insert
    suspend fun insertPoints(list: List<TrackPointEntity>)

    @Query("SELECT * FROM track_point WHERE trackId = :trackId ORDER BY id")
    suspend fun points(trackId: Long): List<TrackPointEntity>

    @Query("SELECT COUNT(*) FROM track_point WHERE trackId = :trackId")
    suspend fun pointCount(trackId: Long): Int

    @Query("DELETE FROM track_point WHERE trackId = :trackId")
    suspend fun deletePoints(trackId: Long)

    // 取り込みの印（版 6、0.10.1）
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportSources(list: List<ImportSourceEntity>)

    @Query("SELECT `key` FROM import_source")
    suspend fun importSourceKeys(): List<String>

    // 場所の索引（版 7、0.11.0）
    @Query("SELECT segment, lat, lon FROM track_point WHERE trackId = :trackId ORDER BY id")
    suspend fun pointsLL(trackId: Long): List<PointLL>

    @Query("SELECT trackId, version, sourcePoints FROM track_geo")
    suspend fun geoMeta(): List<TrackGeoMeta>

    @Query("SELECT * FROM track_geo")
    suspend fun geoAll(): List<TrackGeoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGeo(g: TrackGeoEntity)

    // 記録中の出来事（版 2）
    @Insert
    suspend fun insertEvent(e: TrackEventEntity): Long

    @Query("UPDATE track_event SET endedAt = :at WHERE trackId = :trackId AND type = :type AND endedAt IS NULL")
    suspend fun endEvents(trackId: Long, type: String, at: Long)

    @Query("SELECT * FROM track_event WHERE trackId = :trackId ORDER BY startedAt, id")
    fun events(trackId: Long): Flow<List<TrackEventEntity>>
}

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(p: PhotoEntity): Long

    @Update
    suspend fun update(p: PhotoEntity)

    @Query("DELETE FROM photo WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM photo WHERE id = :id")
    suspend fun photoNow(id: Long): PhotoEntity?

    @Query("SELECT * FROM photo WHERE trackId = :trackId ORDER BY takenAt, id")
    fun forTrack(trackId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photo WHERE trackId = :trackId ORDER BY takenAt, id")
    suspend fun forTrackNow(trackId: Long): List<PhotoEntity>

    /** 記録に紐づいた写真すべて（まとめ。0.7.0）。 */
    @Query("SELECT * FROM photo WHERE trackId IS NOT NULL ORDER BY takenAt, id")
    fun linked(): Flow<List<PhotoEntity>>

    /** 記録ごとの写真の数（一覧の「写真あり」の絞り込み。0.11.0）。 */
    @Query("SELECT trackId, COUNT(*) AS n FROM photo WHERE trackId IS NOT NULL GROUP BY trackId")
    fun countsByTrack(): Flow<List<TrackPhotoCount>>

    @Query("SELECT COUNT(*) FROM photo WHERE uri = :uri AND trackId = :trackId")
    suspend fun countUri(uri: String, trackId: Long): Int

    /** 同じ記録で、撮影時刻が [from]〜[to] の写真の数（Photo Picker の URI は元の URI と違うので時刻でも重複を見る）。 */
    @Query("SELECT COUNT(*) FROM photo WHERE trackId = :trackId AND takenAt BETWEEN :from AND :to")
    suspend fun countNear(trackId: Long, from: Long, to: Long): Int
}

/*
 * 版の履歴（公開後は消さずに Migration を足す。fallbackToDestructiveMigration は使わない）
 *   1: 0.1.0（プラン・ルート・経由地・記録）
 *   2: 0.2.0（route / waypoint に sourceUrl、記録中の出来事 track_event）
 *   3: 0.3.0（route に editShape（描いたルートの制御点）、写真 photo）
 *   4: 0.9.0（track_point に高度の精度 altAccuracy・測位の出どころ provider）
 *   5: 0.10.0（track に取り込みの元の形式 importFormat）
 *   6: 0.10.1（取り込みの印 import_source）
 *   7: 0.11.0（場所の索引 track_geo。一覧の場所の絞り込み）
 */
@Database(
    entities = [
        PlanEntity::class, RouteEntity::class, RoutePointEntity::class, WaypointEntity::class,
        TrackEntity::class, TrackPointEntity::class, TrackEventEntity::class, PhotoEntity::class, ImportSourceEntity::class, TrackGeoEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun tracks(): TrackDao
    abstract fun photos(): PhotoDao

    companion object {
        /**
         * 1 → 2: 列と表を足すだけ（既存の行はそのまま。sourceUrl は NULL）。
         * SQL は単体テスト（MigrationTest）で schemas/2.json と突き合わせ、SQLite で実際に流して確かめる。
         */
        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE `route` ADD COLUMN `sourceUrl` TEXT",
            "ALTER TABLE `waypoint` ADD COLUMN `sourceUrl` TEXT",
            "CREATE TABLE IF NOT EXISTS `track_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                "FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_track_event_trackId` ON `track_event` (`trackId`)",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach { db.execSQL(it) }
            }
        }

        /** 2 → 3: route に editShape、写真の表 photo。 */
        val MIGRATION_2_3_SQL = listOf(
            "ALTER TABLE `route` ADD COLUMN `editShape` TEXT",
            "CREATE TABLE IF NOT EXISTS `photo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER, `uri` TEXT NOT NULL, " +
                "`takenAt` INTEGER NOT NULL, `lat` REAL, `lon` REAL, `locationSource` TEXT, `thumbPath` TEXT, `ownCapture` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_photo_trackId` ON `photo` (`trackId`)",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 3 → 4（0.9.0）: 点の高度の精度 track_point.altAccuracy（NULL）と測位の出どころ track_point.provider（0 = 不明＝既存の点）。
         * ALTER TABLE だけ（既存の点はそのまま。ドライブロガーの 5 → 6 と同じ列）。
         */
        val MIGRATION_3_4_SQL = listOf(
            "ALTER TABLE `track_point` ADD COLUMN `altAccuracy` REAL",
            "ALTER TABLE `track_point` ADD COLUMN `provider` INTEGER NOT NULL DEFAULT 0",
        )

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 4 → 5（0.10.0）: ファイルから取り込んだ記録の元の形式 track.importFormat（NULL = 自分で記録したもの＝既存の記録）。ALTER TABLE だけ。
         */
        val MIGRATION_4_5_SQL = listOf(
            "ALTER TABLE `track` ADD COLUMN `importFormat` TEXT",
        )

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 5 → 6（0.10.1）: 取り込みの印の表 import_source（記録を消すと一緒に消える）。表を足すだけ（既存の行はそのまま）。
         */
        val MIGRATION_5_6_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `import_source` (`key` TEXT NOT NULL, `trackId` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`), FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_import_source_trackId` ON `import_source` (`trackId`)",
        )

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6_SQL.forEach { db.execSQL(it) }
            }
        }

        /**
         * 6 → 7（0.11.0）: 記録の場所の索引の表 track_geo（記録を消すと一緒に消える）。表を足すだけ。既存の記録の索引は
         * 起動後にバックグラウンドで作る（[TrackGeoIndex]。Migration の中では点を読まない＝上書き後の最初の起動を待たせない）。
         */
        val MIGRATION_6_7_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `track_geo` (`trackId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `sourcePoints` INTEGER NOT NULL, " +
                "`south` REAL, `west` REAL, `north` REAL, `east` REAL, `startLat` REAL, `startLon` REAL, `endLat` REAL, `endLon` REAL, `line` BLOB NOT NULL, " +
                "PRIMARY KEY(`trackId`), FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_7_SQL.forEach { db.execSQL(it) }
            }
        }

        /** 版を上げたらここに Migration を足す。 */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cycle_route_logger.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
