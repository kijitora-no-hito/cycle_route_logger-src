package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Room の Migration を、エクスポートしたスキーマ（app/schemas/…/N.json）と突き合わせる。
 * 版 1 のスキーマで作った SQLite に実データを入れ、Migration の SQL を流し、版 2 のスキーマと表・列・索引・外部キーが
 * 一致すること、データが残ることを確かめる（Room が開くときの検証と同じ観点）。
 */
class MigrationTest {
    private val schemaDir = listOf("schemas", "app/schemas").map { File(it, "jp.kjtr.cycleroutelogger.data.AppDatabase") }.first { it.isDirectory }

    private fun schema(v: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private data class Col(val name: String, val type: String, val notNull: Boolean, val pk: Boolean)

    private fun entities(s: JsonObject) = s["entities"]!!.jsonArray.map { it.jsonObject }
    private fun tableName(e: JsonObject) = e["tableName"]!!.jsonPrimitive.content

    private fun expectedColumns(e: JsonObject): Set<Col> {
        val pkCols = e["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        return e["fields"]!!.jsonArray.map { it.jsonObject }.map { f ->
            Col(
                f["columnName"]!!.jsonPrimitive.content,
                f["affinity"]!!.jsonPrimitive.content,
                f["notNull"]!!.jsonPrimitive.boolean,
                f["columnName"]!!.jsonPrimitive.content in pkCols,
            )
        }.toSet()
    }

    private fun actualColumns(c: Connection, table: String): Set<Col> =
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                val out = HashSet<Col>()
                while (rs.next()) out.add(Col(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk") > 0))
                out
            }
        }

    private fun createV1(c: Connection) = create(c, 1)

    private fun create(c: Connection, v: Int) {
        c.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (e in entities(schema(v))) {
                st.execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName(e)))
                e["indices"]?.jsonArray?.forEach { i ->
                    st.execute(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName(e)))
                }
            }
        }
    }

    private fun scalar(c: Connection, sql: String): Any? = c.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getObject(1) else null } }

    @Test
    fun schema1and2_differOnlyByMigration() {
        val v1 = entities(schema(1)).associateBy { tableName(it) }
        val v2 = entities(schema(2)).associateBy { tableName(it) }
        assertEquals(2, schema(2)["version"]!!.jsonPrimitive.int)
        // 増えた表は track_event だけ、消えた表は無い
        assertEquals(setOf("track_event"), v2.keys - v1.keys)
        assertTrue((v1.keys - v2.keys).isEmpty())
        // 既存の表で増えた列は route.sourceUrl と waypoint.sourceUrl だけ（どちらも NULL 可）
        val added = v1.keys.flatMap { t -> (expectedColumns(v2[t]!!) - expectedColumns(v1[t]!!)).map { t to it } }
        assertEquals(
            setOf("route" to Col("sourceUrl", "TEXT", false, false), "waypoint" to Col("sourceUrl", "TEXT", false, false)),
            added.toSet(),
        )
        val removed = v1.keys.flatMap { t -> (expectedColumns(v1[t]!!) - expectedColumns(v2[t]!!)).map { t to it } }
        assertTrue(removed.isEmpty())
    }

    @Test
    fun migrate1to2_onRealSqlite_keepsDataAndMatchesSchema2() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute("INSERT INTO plan (id, name, memo, createdAt, updatedAt) VALUES (1, '旅', 'メモ', 1, 2)")
                st.execute(
                    "INSERT INTO route (id, planId, sortOrder, name, color, visible, createdAt, distanceM, ascentM, pointCount, hasElevation, sourceTrackId) " +
                        "VALUES (10, 1, 0, '1日目', -1, 1, 3, 1234.5, 12.0, 2, 1, NULL)",
                )
                st.execute("INSERT INTO route_point (routeId, seq, segment, lat, lon, ele) VALUES (10, 0, 0, 35.0, 139.0, 5.0)")
                st.execute("INSERT INTO waypoint (id, planId, name, type, memo, plannedAt, lat, lon, ele, createdAt) VALUES (20, 1, 'カフェ', 'food', '', NULL, 35.1, 139.1, NULL, 4)")
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, '走行', 5, 6, 2, 'free', NULL, NULL, 1, 100.0, 1000, 5.0, 1.0, 1)",
                )
                st.execute("INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed) VALUES (40, 30, 0, 5, 35.0, 139.0, NULL, 5.0, 1.0, 1.0)")
                st.execute("PRAGMA user_version = 1")
            }

            // Migration(1, 2) と同じ SQL
            c.createStatement().use { st -> AppDatabase.MIGRATION_1_2_SQL.forEach { st.execute(it) } }

            // 版 2 のスキーマと、全部の表の列が一致する
            for (e in entities(schema(2))) {
                assertEquals("columns of ${tableName(e)}", expectedColumns(e), actualColumns(c, tableName(e)))
            }
            // track_event の索引と外部キー（記録を消したら一緒に消える）
            assertEquals("index_track_event_trackId", scalar(c, "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='track_event'"))
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA foreign_key_list(`track_event`)").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("track", rs.getString("table"))
                    assertEquals("CASCADE", rs.getString("on_delete"))
                }
            }
            // track_event の作り方は版 2 のスキーマの createSql と同じ
            val createSql = entities(schema(2)).first { tableName(it) == "track_event" }["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "track_event")
            assertEquals(createSql.replace("IF NOT EXISTS ", ""), scalar(c, "SELECT sql FROM sqlite_master WHERE name='track_event'"))

            // データは残り、新しい列は NULL
            assertEquals("1日目", scalar(c, "SELECT name FROM route WHERE id = 10"))
            assertNull(scalar(c, "SELECT sourceUrl FROM route WHERE id = 10"))
            assertNull(scalar(c, "SELECT sourceUrl FROM waypoint WHERE id = 20"))
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_point") as Number).toInt())

            // 新しい列・表が使える
            c.createStatement().use { st ->
                st.execute("UPDATE route SET sourceUrl = 'https://example.com/a.gpx' WHERE id = 10")
                st.execute("INSERT INTO track_event (trackId, type, startedAt, endedAt) VALUES (30, 'location_off', 7, NULL)")
                st.execute("DELETE FROM track WHERE id = 30")
            }
            assertEquals("https://example.com/a.gpx", scalar(c, "SELECT sourceUrl FROM route WHERE id = 10"))
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM track_event") as Number).toInt())
        }
    }

    private fun assertMatchesSchema(c: Connection, v: Int) {
        for (e in entities(schema(v))) {
            assertEquals("v$v columns of ${tableName(e)}", expectedColumns(e), actualColumns(c, tableName(e)))
            e["indices"]?.jsonArray?.forEach { i ->
                val name = i.jsonObject["name"]!!.jsonPrimitive.content
                assertEquals(name, scalar(c, "SELECT name FROM sqlite_master WHERE type='index' AND name='$name'"))
            }
        }
    }

    @Test
    fun schema2and3_differOnlyByMigration() {
        val v2 = entities(schema(2)).associateBy { tableName(it) }
        val v3 = entities(schema(3)).associateBy { tableName(it) }
        assertEquals(3, schema(3)["version"]!!.jsonPrimitive.int)
        assertEquals(setOf("photo"), v3.keys - v2.keys)
        assertTrue((v2.keys - v3.keys).isEmpty())
        val added = v2.keys.flatMap { t -> (expectedColumns(v3[t]!!) - expectedColumns(v2[t]!!)).map { t to it } }
        assertEquals(setOf("route" to Col("editShape", "TEXT", false, false)), added.toSet())
        assertTrue(v2.keys.all { t -> (expectedColumns(v2[t]!!) - expectedColumns(v3[t]!!)).isEmpty() })
    }

    @Test
    fun migrate2to3_onRealSqlite() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, 2)
            c.createStatement().use { st ->
                st.execute("INSERT INTO plan (id, name, memo, createdAt, updatedAt) VALUES (1, 'p', '', 1, 2)")
                st.execute(
                    "INSERT INTO route (id, planId, sortOrder, name, color, visible, createdAt, distanceM, ascentM, pointCount, hasElevation, sourceTrackId, sourceUrl) " +
                        "VALUES (10, 1, 0, 'r', -1, 1, 3, 1.0, 0.0, 2, 0, NULL, 'https://example.com/a.gpx')",
                )
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, 't', 5, 6, 2, 'free', NULL, NULL, 1, 1.0, 1, 1.0, 0.0, 0)",
                )
                st.execute("INSERT INTO track_event (trackId, type, startedAt, endedAt) VALUES (30, 'no_fix', 5, 6)")
            }
            c.createStatement().use { st -> AppDatabase.MIGRATION_2_3_SQL.forEach { st.execute(it) } }
            assertMatchesSchema(c, 3)
            assertEquals("https://example.com/a.gpx", scalar(c, "SELECT sourceUrl FROM route WHERE id = 10"))
            assertNull(scalar(c, "SELECT editShape FROM route WHERE id = 10"))
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_event") as Number).toInt())
            c.createStatement().use { st ->
                st.execute("INSERT INTO photo (trackId, uri, takenAt, lat, lon, locationSource, thumbPath, ownCapture, createdAt) VALUES (30, 'content://x/1', 5, 35.0, 139.0, 'gps', NULL, 1, 7)")
                st.execute("DELETE FROM track WHERE id = 30")
            }
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM photo") as Number).toInt())
        }
    }

    /** 0.1.0 の端末から 0.3.0 へ一気に上げる（1 → 2 → 3）。 */
    @Test
    fun migrate1to3_chain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute("INSERT INTO plan (id, name, memo, createdAt, updatedAt) VALUES (1, 'p', '', 1, 2)")
                st.execute("INSERT INTO waypoint (id, planId, name, type, memo, plannedAt, lat, lon, ele, createdAt) VALUES (20, 1, 'w', 'food', '', NULL, 35.1, 139.1, NULL, 4)")
            }
            c.createStatement().use { st -> (AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL).forEach { st.execute(it) } }
            assertMatchesSchema(c, 3)
            assertEquals("w", scalar(c, "SELECT name FROM waypoint WHERE id = 20"))
        }
    }

    @Test
    fun schema3and4_differOnlyByMigration() {
        val v3 = entities(schema(3)).associateBy { tableName(it) }
        val v4 = entities(schema(4)).associateBy { tableName(it) }
        assertEquals(4, schema(4)["version"]!!.jsonPrimitive.int)
        assertEquals(v3.keys, v4.keys)
        // 増えた列は track_point.altAccuracy（NULL 可）と track_point.provider（NOT NULL・既定 0）だけ
        val added = v3.keys.flatMap { t -> (expectedColumns(v4[t]!!) - expectedColumns(v3[t]!!)).map { t to it } }
        assertEquals(
            setOf("track_point" to Col("altAccuracy", "REAL", false, false), "track_point" to Col("provider", "INTEGER", true, false)),
            added.toSet(),
        )
        assertTrue(v3.keys.all { t -> (expectedColumns(v3[t]!!) - expectedColumns(v4[t]!!)).isEmpty() })
        val provider = v4["track_point"]!!["fields"]!!.jsonArray.map { it.jsonObject }.first { it["columnName"]!!.jsonPrimitive.content == "provider" }
        assertEquals("0", provider["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun migrate3to4_onRealSqlite_keepsPoints() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, 3)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, 't', 5, 6, 2, 'free', NULL, NULL, 1, 1.0, 1, 1.0, 0.0, 2)",
                )
                st.execute("INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed) VALUES (40, 30, 0, 5, 35.0, 139.0, 12.5, 5.0, 1.0, 1.0)")
                st.execute("INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed) VALUES (41, 30, 0, 6, 35.0001, 139.0, NULL, NULL, NULL, NULL)")
            }
            c.createStatement().use { st -> AppDatabase.MIGRATION_3_4_SQL.forEach { st.execute(it) } }
            assertMatchesSchema(c, 4)
            // 既存の点は残り、高度の精度は NULL・出どころは 0（不明）
            assertEquals(2, (scalar(c, "SELECT COUNT(*) FROM track_point") as Number).toInt())
            assertEquals(12.5, (scalar(c, "SELECT ele FROM track_point WHERE id = 40") as Number).toDouble(), 1e-9)
            assertNull(scalar(c, "SELECT altAccuracy FROM track_point WHERE id = 40"))
            assertEquals(0, (scalar(c, "SELECT provider FROM track_point WHERE id = 41") as Number).toInt())
            // 新しい列が使え、記録を消すと点も消える
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track_point (trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed, altAccuracy, provider) " +
                        "VALUES (30, 0, 7, 35.0002, 139.0, 13.0, 4.0, 1.0, 1.0, 6.0, 3)",
                )
            }
            assertEquals(3, (scalar(c, "SELECT provider FROM track_point WHERE time = 7") as Number).toInt())
            c.createStatement().use { st -> st.execute("DELETE FROM track WHERE id = 30") }
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM track_point") as Number).toInt())
        }
    }

    /** 0.1.0 の端末から 0.9.0 へ一気に上げる（1 → 2 → 3 → 4）。 */
    @Test
    fun migrate1to4_chain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, '走行', 5, 6, 2, 'free', NULL, NULL, 1, 100.0, 1000, 5.0, 1.0, 1)",
                )
                st.execute("INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed) VALUES (40, 30, 0, 5, 35.0, 139.0, NULL, 5.0, 1.0, 1.0)")
            }
            c.createStatement().use { st ->
                (AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL + AppDatabase.MIGRATION_3_4_SQL).forEach { st.execute(it) }
            }
            assertMatchesSchema(c, 4)
            assertEquals(0, (scalar(c, "SELECT provider FROM track_point WHERE id = 40") as Number).toInt())
        }
    }

    @Test
    fun schema4and5_differOnlyByMigration() {
        val v4 = entities(schema(4)).associateBy { tableName(it) }
        val v5 = entities(schema(5)).associateBy { tableName(it) }
        assertEquals(5, schema(5)["version"]!!.jsonPrimitive.int)
        assertEquals(v4.keys, v5.keys)
        // 増えた列は track.importFormat（NULL 可）だけ
        val added = v4.keys.flatMap { t -> (expectedColumns(v5[t]!!) - expectedColumns(v4[t]!!)).map { t to it } }
        assertEquals(setOf("track" to Col("importFormat", "TEXT", false, false)), added.toSet())
        assertTrue(v4.keys.all { t -> (expectedColumns(v4[t]!!) - expectedColumns(v5[t]!!)).isEmpty() })
    }

    @Test
    fun migrate4to5_onRealSqlite_keepsTracks() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, 4)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, 't', 5, 6, 2, 'free', NULL, NULL, 1, 1.0, 1, 1.0, 0.0, 1)",
                )
                st.execute(
                    "INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed, altAccuracy, provider) " +
                        "VALUES (40, 30, 0, 5, 35.0, 139.0, NULL, 5.0, 1.0, 1.0, 3.0, 1)",
                )
            }
            c.createStatement().use { st -> AppDatabase.MIGRATION_4_5_SQL.forEach { st.execute(it) } }
            assertMatchesSchema(c, 5)
            // 既存の記録は「自分で記録したもの」（importFormat = NULL）のまま、点も残る
            assertNull(scalar(c, "SELECT importFormat FROM track WHERE id = 30"))
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_point") as Number).toInt())
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount, importFormat) " +
                        "VALUES ('2026-05-03 取り込み', 7, 8, 2, 'free', NULL, NULL, 1, 1.0, 1, 1.0, 0.0, 0, 'fit,gpx')",
                )
            }
            assertEquals("fit,gpx", scalar(c, "SELECT importFormat FROM track WHERE startedAt = 7"))
        }
    }

    /** 0.1.0 の端末から 0.10.0 へ一気に上げる（1 → 2 → 3 → 4 → 5）。 */
    @Test
    fun migrate1to5_chain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, '走行', 5, 6, 2, 'free', NULL, NULL, 1, 100.0, 1000, 5.0, 1.0, 1)",
                )
            }
            c.createStatement().use { st ->
                (AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL + AppDatabase.MIGRATION_3_4_SQL + AppDatabase.MIGRATION_4_5_SQL).forEach { st.execute(it) }
            }
            assertMatchesSchema(c, 5)
            assertEquals("走行", scalar(c, "SELECT name FROM track WHERE id = 30"))
        }
    }

    @Test
    fun schema5and6_differOnlyByImportSource() {
        val v5 = entities(schema(5)).associateBy { tableName(it) }
        val v6 = entities(schema(6)).associateBy { tableName(it) }
        assertEquals(6, schema(6)["version"]!!.jsonPrimitive.int)
        // 増えたのは import_source 表だけ。既存の表の列は同じ
        assertEquals(v5.keys + "import_source", v6.keys)
        assertTrue(v5.keys.all { t -> expectedColumns(v5[t]!!) == expectedColumns(v6[t]!!) })
    }

    @Test
    fun migrate5to6_onRealSqlite_keepsTracks_andCascadesSources() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, 5)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount, importFormat) " +
                        "VALUES (30, '2026-05-03 取り込み', 5, 6, 2, 'free', NULL, NULL, 1, 1.0, 1, 1.0, 0.0, 1, 'fit')",
                )
            }
            c.createStatement().use { st -> AppDatabase.MIGRATION_5_6_SQL.forEach { st.execute(it) } }
            assertMatchesSchema(c, 6)
            assertEquals("fit", scalar(c, "SELECT importFormat FROM track WHERE id = 30"))
            c.createStatement().use { st ->
                st.execute("INSERT INTO import_source (`key`, trackId, importedAt) VALUES ('sha:00@1', 30, 9)")
                st.execute("INSERT INTO import_source (`key`, trackId, importedAt) VALUES ('strava:1@1', 30, 9)")
            }
            assertEquals(2, (scalar(c, "SELECT COUNT(*) FROM import_source") as Number).toInt())
            // 記録を消すと印も消える（その走行はまた取り込める）
            c.createStatement().use { st -> st.execute("DELETE FROM track WHERE id = 30") }
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM import_source") as Number).toInt())
        }
    }

    /** 0.1.0 の端末から 0.10.1 へ一気に上げる（1 → … → 6）。 */
    @Test
    fun migrate1to6_chain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, '走行', 5, 6, 2, 'free', NULL, NULL, 1, 100.0, 1000, 5.0, 1.0, 1)",
                )
            }
            c.createStatement().use { st ->
                (AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL + AppDatabase.MIGRATION_3_4_SQL + AppDatabase.MIGRATION_4_5_SQL + AppDatabase.MIGRATION_5_6_SQL)
                    .forEach { st.execute(it) }
            }
            assertMatchesSchema(c, 6)
            assertEquals("走行", scalar(c, "SELECT name FROM track WHERE id = 30"))
        }
    }

    @Test
    fun schema6and7_differOnlyByTrackGeo() {
        val v6 = entities(schema(6)).associateBy { tableName(it) }
        val v7 = entities(schema(7)).associateBy { tableName(it) }
        assertEquals(7, schema(7)["version"]!!.jsonPrimitive.int)
        // 増えたのは track_geo 表だけ。既存の表の列は同じ
        assertEquals(v6.keys + "track_geo", v7.keys)
        assertTrue(v6.keys.all { t -> expectedColumns(v6[t]!!) == expectedColumns(v7[t]!!) })
        // Room が作る表と Migration の SQL が同じ
        val create = v7["track_geo"]!!["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "track_geo")
        assertEquals(create, AppDatabase.MIGRATION_6_7_SQL.single())
    }

    /** 0.10.2 → 0.11.0: 記録は残り、索引の表が使える。記録を消すと索引も消える。既存の記録の索引は Migration では作らない（起動後に作る）。 */
    @Test
    fun migrate6to7_onRealSqlite_keepsTracks_andCascadesGeo() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, 6)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount, importFormat) " +
                        "VALUES (40, '2013-05-02 Morning Ride', 5, 6, 2, 'free', NULL, NULL, 1, 21000.0, 3600000, 9.0, 120.0, 2, 'fit')",
                )
                st.execute("INSERT INTO track_point (id, trackId, segment, time, lat, lon, ele, accuracy, deviceSpeed, speed, altAccuracy, provider) VALUES (1, 40, 0, 5, 35.6, 139.7, NULL, NULL, NULL, NULL, NULL, 4)")
                st.execute("INSERT INTO import_source (`key`, trackId, importedAt) VALUES ('strava:1@1', 40, 9)")
            }
            c.createStatement().use { st -> AppDatabase.MIGRATION_6_7_SQL.forEach { st.execute(it) } }
            assertMatchesSchema(c, 7)
            assertEquals("2013-05-02 Morning Ride", scalar(c, "SELECT name FROM track WHERE id = 40"))
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_point") as Number).toInt())
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM track_geo") as Number).toInt())
            c.prepareStatement(
                "INSERT INTO track_geo (trackId, version, sourcePoints, south, west, north, east, startLat, startLon, endLat, endLon, line) " +
                    "VALUES (40, 1, 2, 35.6, 139.7, 35.61, 139.71, 35.6, 139.7, 35.61, 139.71, ?)",
            ).use { ps ->
                ps.setBytes(1, jp.kjtr.cycleroutelogger.stats.TrackGeoCodec.encode(listOf(intArrayOf(35_600_000, 139_700_000, 35_610_000, 139_710_000))))
                ps.executeUpdate()
            }
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_geo") as Number).toInt())
            // 点の無い・範囲の無い記録（NULL 可）
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount, importFormat) " +
                        "VALUES (41, 'x', 5, 6, 2, 'free', NULL, NULL, 1, 0.0, 0, 0.0, 0.0, 0, NULL)",
                )
                st.execute("INSERT INTO track_geo (trackId, version, sourcePoints, line) VALUES (41, 1, 0, x'00000000')")
            }
            // 記録の無い索引は入らない（外部キー）
            val rejected = runCatching { c.createStatement().use { st -> st.execute("INSERT INTO track_geo (trackId, version, sourcePoints, line) VALUES (99, 1, 0, x'00')") } }.isFailure
            assertTrue(rejected)
            // 記録を消すと索引・点・印も消える
            c.createStatement().use { st -> st.execute("DELETE FROM track WHERE id = 40") }
            assertEquals(1, (scalar(c, "SELECT COUNT(*) FROM track_geo") as Number).toInt())
            assertEquals(0, (scalar(c, "SELECT COUNT(*) FROM import_source") as Number).toInt())
        }
    }

    /** 0.1.0 の端末から 0.11.0 へ一気に上げる（1 → … → 7）。 */
    @Test
    fun migrate1to7_chain() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createV1(c)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO track (id, name, startedAt, endedAt, status, mode, planId, planName, autoPause, distanceM, movingMs, maxSpeedMps, ascentM, pointCount) " +
                        "VALUES (30, '走行', 5, 6, 2, 'free', NULL, NULL, 1, 100.0, 1000, 5.0, 1.0, 1)",
                )
            }
            c.createStatement().use { st ->
                assertEquals(6, AppDatabase.MIGRATIONS.size)
                (AppDatabase.MIGRATION_1_2_SQL + AppDatabase.MIGRATION_2_3_SQL + AppDatabase.MIGRATION_3_4_SQL + AppDatabase.MIGRATION_4_5_SQL +
                    AppDatabase.MIGRATION_5_6_SQL + AppDatabase.MIGRATION_6_7_SQL).forEach { st.execute(it) }
            }
            assertMatchesSchema(c, 7)
            assertEquals("走行", scalar(c, "SELECT name FROM track WHERE id = 30"))
            assertEquals(7, AppDatabase.MIGRATIONS.last().endVersion)
        }
    }
}
