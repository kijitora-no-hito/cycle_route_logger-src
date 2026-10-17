package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.data.Settings
import jp.kjtr.cycleroutelogger.update.AppVersion
import jp.kjtr.cycleroutelogger.update.Releases
import jp.kjtr.cycleroutelogger.update.UpdateChecker
import jp.kjtr.cycleroutelogger.update.UpdateLinks
import jp.kjtr.cycleroutelogger.update.UpdateMemo
import jp.kjtr.cycleroutelogger.update.UpdatePolicy
import jp.kjtr.cycleroutelogger.update.UpdateRelease
import jp.kjtr.cycleroutelogger.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 0.4.0: 更新の確認（GitHub の releases.json → ホームの帯。DESIGN.md §10。Phonetic Hound の V0250Test に相当）。 */
class UpdateCheckTest {

    private fun v(s: String) = AppVersion.parse(s)!!

    // ---------------------------------------------------------------- 1. 版の比較

    @Test
    fun versionsCompareAsNumberTuples() {
        assertTrue(v("0.3.0") < v("0.4.0"))
        assertTrue(v("0.9") < v("0.10"))
        assertTrue(v("0.9.9") < v("0.10.0"))
        assertTrue(v("0.3.0") < v("0.3.1"))
        assertEquals(0, v("0.4").compareTo(v("0.4.0")))
        // 正式版は同じ数の開発版より新しい。1.0.0 は 0.99.0 より新しい。
        assertTrue(v("0.4.0-dev") < v("0.4.0"))
        assertTrue(v("1.0.0") > v("0.99.0-dev"))
        assertTrue(v("1.0.0-dev") > v("0.99.0"))
        // 頭の v は外す。
        assertEquals(v("0.3.0"), v("v0.3.0"))
        assertEquals("0.3.0", v("v0.3.0").toString())
        assertEquals("0.4.0-dev", v("0.4.0-dev").toString())
        for (bad in listOf("", "abc", "1..2", "1.2.x", "v", "-dev", "1.2.3.4.5")) assertNull(bad, AppVersion.parse(bad))
        // いまの版の versionName は読める。
        assertNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
    }

    // ---------------------------------------------------------------- 2. releases.json

    private fun entry(folder: String, tag: String, notes: String = "") =
        """{"folder":"$folder","tag":"$tag","title":"x","apk":"d:/work/android/$folder/dist/x.apk","sha256":"AB","notes":${quote(notes)}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun json(vararg entries: String) = """{"repo":"kijitora-no-hito/android-apps","releases":[${entries.joinToString(",")}]}"""

    private val notes = "自転車ルートロガー v0.4.0（2026-10-12）\n\n" +
        "- **更新の確認**: 新しい版をホームで知らせます。\n- 設定で OFF にできます。\n\n" +
        "説明・インストール方法: https://kijitora-no-hito.github.io/android-apps/cycle_route_logger/\nSHA-256: 0F37"

    @Test
    fun parsesThisAppsEntry() {
        val text = json(
            entry("trip_planner", "tripplanner-v1.6"),
            entry("cycle_route_logger", "cycleroutelogger-v0.4.0", notes),
            entry("phonetic_hound", "phonetichound-v1.2.0"),
        )
        val r = Releases.parse(text)!!
        assertEquals("cycleroutelogger-v0.4.0", r.tag)
        assertEquals("0.4.0", r.version)
        assertEquals(notes, r.notes)
        // 更新内容 = 箇条書きの行だけ（強調は外す。見出し・URL・SHA-256 は入れない）。
        assertEquals(listOf("更新の確認: 新しい版をホームで知らせます。", "設定で OFF にできます。"), Releases.summary(r.notes))
        assertEquals(1, Releases.summary(r.notes, max = 1).size)
        assertEquals(6, Releases.summary((1..10).joinToString("\n") { "- $it" }).size)
        assertEquals(listOf("a"), Releases.summary("・a\n見出し\n-b"))
        // 同じフォルダが複数あれば一番新しい版（並び順によらない）。
        val two = json(entry("cycle_route_logger", "cycleroutelogger-v0.3.0"), entry("cycle_route_logger", "cycleroutelogger-v0.10.0"), entry("cycle_route_logger", "cycleroutelogger-v0.4.0"))
        assertEquals("0.10.0", Releases.parse(two)!!.version)
        // 知らないキー・数値の notes・入れ子があっても読める（notes が文字列でなければ空）。
        val odd = """{"releases":[{"folder":"cycle_route_logger","tag":"cycleroutelogger-v0.5.0","notes":42,"extra":{"a":[1,2]}}],"other":true}"""
        assertEquals(UpdateRelease("cycleroutelogger-v0.5.0", "0.5.0", ""), Releases.parse(odd))
    }

    @Test
    fun missingOrBrokenJsonIsNull() {
        assertNull(Releases.parse(json(entry("trip_planner", "tripplanner-v1.6"))))
        // 他のアプリのエントリに自分のタグの形があっても読まない（folder で選ぶ）。
        assertNull(Releases.parse(json(entry("phonetic_hound", "cycleroutelogger-v9.0.0"))))
        assertNull(Releases.parse(json()))
        assertNull(Releases.parse("""{"repo":"x"}"""))
        assertNull(Releases.parse("""{"releases":{"folder":"cycle_route_logger"}}"""))
        assertNull(Releases.parse("""[1,2,3]"""))
        assertNull(Releases.parse(""))
        assertNull(Releases.parse("{\"releases\":[{\"folder\":\"cycle_route_logger\",\"tag\":"))
        assertNull(Releases.parse("<html><body>404: Not Found</body></html>"))
        assertNull(Releases.parse("404: Not Found"))
        // タグの形が違う・URL に入れられない文字を含む・版として読めない・文字列でない → 読まない。
        assertNull(Releases.parse(json(entry("cycle_route_logger", "v0.4.0"))))
        assertNull(Releases.parse(json(entry("cycle_route_logger", "phonetichound-v0.4.0"))))
        assertNull(Releases.parse(json(entry("cycle_route_logger", "cycleroutelogger-v0.4.0/../evil"))))
        assertNull(Releases.parse(json(entry("cycle_route_logger", "cycleroutelogger-v0.4.0 x"))))
        assertNull(Releases.parse(json(entry("cycle_route_logger", "cycleroutelogger-vabc"))))
        assertNull(Releases.parse("""{"releases":[{"folder":"cycle_route_logger","tag":4}]}"""))
        // 壊れたエントリがあっても、正しいエントリは読める。
        val mixed = json(entry("cycle_route_logger", "cycleroutelogger-vabc"), "1", "null", entry("cycle_route_logger", "cycleroutelogger-v0.4.0"))
        assertEquals("0.4.0", Releases.parse(mixed)!!.version)
    }

    @Test
    fun publishedReleasesJsonHasTheEntry() {
        // 公開リポジトリの手元の写し（あれば）: 今の形のまま読める。
        val f = File("../../android-apps/releases.json")
        assumeTrue(f.isFile)
        val r = Releases.parse(f.readText(Charsets.UTF_8))
        assertNotNull(r)
        assertTrue(r!!.tag.startsWith(Releases.TAG_PREFIX))
        assertTrue(Releases.summary(r.notes).isNotEmpty())
    }

    // ---------------------------------------------------------------- 3. URL

    @Test
    fun urls() {
        val r = UpdateRelease("cycleroutelogger-v0.4.0", "0.4.0", "")
        assertEquals("https://github.com/kijitora-no-hito/android-apps/releases/download/cycleroutelogger-v0.4.0/cycleroutelogger-0.4.0-release.apk",
            UpdateLinks.apkUrl(r))
        assertEquals(UpdateLinks.apkUrl(r), checker("0.3.0").c.apkUrl(r))
        assertEquals("https://kijitora-no-hito.github.io/android-apps/cycle_route_logger/", UpdateLinks.PAGE_URL)
        assertEquals("https://raw.githubusercontent.com/kijitora-no-hito/android-apps/main/releases.json", UpdateLinks.RELEASES_URL)
    }

    // ---------------------------------------------------------------- 4. いつ取りに行くか・OFF・あとで

    /** 時計・保存・取得を差し替えた [UpdateChecker]。[reply] = 取得の結果（null = 失敗）。 */
    private class Rig(current: String, var memo: UpdateMemo = UpdateMemo(), available: Boolean = true) {
        var now = 1_000_000_000_000L
        var reply: String? = null
        val urls = ArrayList<String>()
        val c = UpdateChecker(current, available, { done -> urls.add(UpdateLinks.RELEASES_URL); done(reply) }, { memo }, { memo = it }, { now })
    }

    private fun checker(current: String, memo: UpdateMemo = UpdateMemo(), available: Boolean = true) = Rig(current, memo, available)

    private val newer get() = json(entry("cycle_route_logger", "cycleroutelogger-v0.4.0", notes))
    private val hour = 3_600_000L

    @Test
    fun launchCheckIsSkippedWithinSixHours() {
        val r = checker("0.3.0")
        r.reply = newer
        assertTrue(r.c.onLaunch(true))
        assertEquals(1, r.urls.size)
        assertEquals(1, r.c.fetches)
        assertEquals(UpdateStatus.AVAILABLE, r.c.status)
        assertEquals(r.now, r.memo.lastAttemptMs)
        assertEquals(r.now, r.memo.lastSuccessMs)
        // 次の起動（5 時間 59 分後）: 取りに行かないが、覚えている版で帯は出る。
        val r2 = Rig("0.3.0", r.memo).apply { now = r.now + 6 * hour - 60_000L; reply = newer }
        assertFalse(r2.c.onLaunch(true))
        assertEquals(0, r2.urls.size)
        assertEquals("0.4.0", r2.c.banner(true)?.version)
        // 6 時間後は取りに行く。
        r2.now = r.now + 6 * hour
        assertTrue(r2.c.onLaunch(true))
        assertEquals(1, r2.urls.size)
        assertEquals(6, UpdatePolicy.INTERVAL_HOURS)
        assertEquals(8_000, UpdatePolicy.TIMEOUT_MS)
        assertEquals(1024 * 1024, UpdatePolicy.MAX_BYTES)
        // 時計が戻っていたら取りに行く。初めては取りに行く。
        assertTrue(UpdatePolicy.isDue(r.now - hour, r.now))
        assertTrue(UpdatePolicy.isDue(r.now, 0L))
        assertFalse(UpdatePolicy.isDue(r.now + hour, r.now))
    }

    @Test
    fun offMeansNoNetworkAtAll() {
        val r = checker("0.3.0")
        r.reply = newer
        assertFalse(r.c.onLaunch(false))
        assertFalse(r.c.checkNow(false))
        assertFalse(r.c.pretend("0.1.0", false))
        assertEquals(0, r.urls.size)
        assertEquals(0L, r.memo.lastAttemptMs)
        // 取得できない環境も通信しない。
        val d = checker("0.3.0", available = false)
        assertFalse(d.c.onLaunch(true))
        assertFalse(d.c.checkNow(true))
        assertEquals(0, d.urls.size)
        // 覚えている版があっても、OFF なら帯を出さない。
        val cached = Rig("0.3.0", UpdateMemo(1L, 1L, "cycleroutelogger-v0.4.0", notes))
        assertNotNull(cached.c.banner(true))
        assertNull(cached.c.banner(false))
        // 設定の既定は ON。
        assertTrue(Settings().updateCheck)
    }

    @Test
    fun laterHidesThatVersionUntilNextLaunch() {
        val r = checker("0.3.0")
        r.reply = newer
        r.c.onLaunch(true)
        assertEquals("cycleroutelogger-v0.4.0", r.c.banner(true)?.tag)
        r.c.dismiss()
        assertNull(r.c.banner(true))
        assertEquals(UpdateStatus.AVAILABLE, r.c.status)
        // ［今すぐ確認］で取り消す。
        assertTrue(r.c.checkNow(true))
        assertNotNull(r.c.banner(true))
        r.c.dismiss()
        // 次の起動（メモリだけなので、同じ版でもまた出る）。
        val next = Rig("0.3.0", r.memo).apply { now = r.now + hour }
        assertNotNull(next.c.banner(true))
        // さらに新しい版が出たら（［あとで］を押した版と違う）、同じ起動の中でも出る。
        assertNull(r.c.banner(true))
        r.reply = json(entry("cycle_route_logger", "cycleroutelogger-v0.5.0"))
        r.now += 6 * hour
        assertTrue(r.c.onLaunch(true))
        assertEquals("0.5.0", r.c.banner(true)?.version)
    }

    @Test
    fun failuresAreSilentAndSameOrOlderIsNotShown() {
        val r = checker("0.3.0")
        r.reply = null
        assertTrue(r.c.onLaunch(true))
        assertEquals(UpdateStatus.FAILED, r.c.status)
        assertNull(r.c.banner(true))
        assertEquals(r.now, r.memo.lastAttemptMs) // 失敗しても試行の時刻は進める（毎起動では取りに行かない）
        assertEquals(0L, r.memo.lastSuccessMs)
        r.reply = "<html>"
        r.c.checkNow(true)
        assertEquals(UpdateStatus.FAILED, r.c.status)
        // 同じ版・古い版は出さない。
        for (cur in listOf("0.4.0", "0.4.1", "0.5.0", "1.0.0")) {
            val s = checker(cur)
            s.reply = newer
            s.c.onLaunch(true)
            assertEquals(cur, UpdateStatus.LATEST, s.c.status)
            assertNull(cur, s.c.banner(true))
        }
        // 失敗しても覚えている版の帯は残る。
        val cached = Rig("0.3.0", UpdateMemo(1L, 1L, "cycleroutelogger-v0.4.0", notes))
        cached.reply = null
        cached.c.checkNow(true)
        assertEquals(UpdateStatus.FAILED, cached.c.status)
        assertNotNull(cached.c.banner(true))
    }

    @Test
    fun cachedReleaseIsReEvaluatedAgainstTheCurrentVersion() {
        // 覚えている版で起動時に評価する（通信しない）: 古い版で入れていれば帯、更新した後なら出さない（古い「新しい版」が残らない）。
        val memo = UpdateMemo(1L, 1L, "cycleroutelogger-v0.4.0", notes)
        val old = Rig("0.3.0", memo)
        assertEquals(UpdateStatus.AVAILABLE, old.c.status)
        assertEquals("0.4.0", old.c.newer?.version)
        assertEquals(0, old.urls.size)
        val updated = Rig("0.4.0", memo)
        assertEquals(UpdateStatus.UNKNOWN, updated.c.status)
        assertNull(updated.c.newer)
        assertNull(updated.c.banner(true))
        // 何も覚えていない・他のアプリのタグ・壊れたタグ → 版なし。
        assertNull(UpdateMemo().release)
        assertNull(UpdateMemo(tag = "careerlog-v1.2.0").release)
        assertNull(UpdateMemo(tag = "cycleroutelogger-vxyz").release)
        assertEquals("0.4.0", memo.release?.version)
        assertEquals(UpdateStatus.UNKNOWN, Rig("0.3.0").c.status)
    }

    @Test
    fun pretendComparesAsAnOlderVersion() {
        val r = checker("0.4.0")
        r.reply = newer
        r.c.onLaunch(true)
        assertNull(r.c.banner(true))
        assertEquals(UpdateStatus.LATEST, r.c.status)
        assertTrue(r.c.pretend("0.2.0", true))
        assertEquals("0.2.0", r.c.compareAs.toString())
        assertEquals("0.4.0", r.c.banner(true)?.version)
        assertFalse(r.c.pretend("not a version", true))
    }

    // ---------------------------------------------------------------- 5. 権限・Android・文言

    @Test
    fun androidSideKeepsTheConnectionPlain() {
        val manager = File("src/main/java/jp/kjtr/cycleroutelogger/update/UpdateManager.kt").readText(Charsets.UTF_8)
        assertTrue(manager.contains("HttpURLConnection") && manager.contains("useCaches = false"))
        // ヘッダは既定のまま（User-Agent を変えない・何も足さない）。
        assertFalse(manager.contains("setRequestProperty"))
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        val ui = File("src/main/java/jp/kjtr/cycleroutelogger/ui/UpdateUi.kt").readText(Charsets.UTF_8)
        assertTrue(ui.contains("Intent.ACTION_VIEW"))
        // 通知は使わない。
        assertFalse(ui.contains("Notification") || manager.contains("Notification"))
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        assertTrue(strings.contains("raw.githubusercontent.com"))
    }
}
