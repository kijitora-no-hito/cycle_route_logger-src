package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.HttpText
import jp.kjtr.cycleroutelogger.geo.NominatimSearch
import jp.kjtr.cycleroutelogger.geo.PlaceResult
import jp.kjtr.cycleroutelogger.geo.PlaceSearchException
import jp.kjtr.cycleroutelogger.geo.RateGate
import jp.kjtr.cycleroutelogger.plan.WaypointType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.URLDecoder

/** 0.6.0: 場所名の検索（Nominatim）。応答の解析・URL・1 秒の間隔・キャッシュ。通信はしない（http を差し替える）。 */
class PlaceSearchTest {

    private val tokyoStation = """
        [
          {"place_id":1,"lat":"35.6812996","lon":"139.7670658","category":"railway","type":"station",
           "name":"東京駅","display_name":"東京駅, 1, 丸の内一丁目, 丸の内, 千代田区, 東京都, 100-0005, 日本",
           "boundingbox":["35.6762","35.6862","139.7620","139.7720"]},
          {"place_id":2,"lat":"35.68","lon":"139.76","category":"amenity","type":"toilets","name":"",
           "display_name":"公衆トイレ, 丸の内, 千代田区, 東京都, 日本"},
          {"place_id":3,"lat":"x","lon":"139.0","name":"壊れた行","display_name":"壊れた行"},
          {"place_id":4,"lon":"139.0","name":"緯度なし"}
        ]
    """.trimIndent()

    // ---------------------------------------------------------------- 応答の解析

    @Test
    fun parsesNominatimJson() {
        val r = NominatimSearch.parse(tokyoStation)!!
        assertEquals(2, r.size)
        val st = r[0]
        assertEquals("東京駅", st.name)
        assertEquals(35.6812996, st.lat, 1e-9)
        assertEquals(139.7670658, st.lon, 1e-9)
        assertEquals("railway", st.category)
        assertEquals("station", st.type)
        // boundingbox は [南, 北, 西, 東]
        assertEquals(GeoBounds(35.6762, 139.7620, 35.6862, 139.7720), st.bounds)
        // name が空なら display_name の先頭
        assertEquals("公衆トイレ", r[1].name)
        assertNull(r[1].bounds)
    }

    @Test
    fun parseRejectsNonArray() {
        assertNull(NominatimSearch.parse("""{"error":"x"}"""))
        assertNull(NominatimSearch.parse("<html>"))
        assertEquals(0, NominatimSearch.parse("[]")!!.size)
    }

    @Test
    fun addressSummaryDropsNamePostcodeAndCountry() {
        val st = NominatimSearch.parse(tokyoStation)!![0]
        assertEquals("東京都 千代田区 丸の内 丸の内一丁目", NominatimSearch.addressSummary(st))
        val paris = PlaceResult("Tour Eiffel", "Tour Eiffel, 5, Avenue Anatole France, Paris, Île-de-France, 75007, France", 48.85, 2.29)
        assertEquals("Avenue Anatole France, Paris, Île-de-France, France", NominatimSearch.addressSummary(paris))
    }

    @Test
    fun guessesWaypointType() {
        val r = NominatimSearch.parse(tokyoStation)!!
        assertEquals(WaypointType.STATION, NominatimSearch.guessType(r[0]))
        assertEquals(WaypointType.TOILET, NominatimSearch.guessType(r[1]))
        // 湖（natural/water）は「給水」にしない。名前の「公園」は休憩
        assertEquals(WaypointType.OTHER, NominatimSearch.guessType(PlaceResult("不忍池", "", 35.7, 139.7, category = "natural", type = "water")))
        assertEquals(WaypointType.REST, NominatimSearch.guessType(PlaceResult("葛西臨海公園", "", 35.64, 139.86, category = "leisure", type = "park")))
        assertEquals(WaypointType.CONVENIENCE, NominatimSearch.guessType(PlaceResult("ローソン", "", 35.6, 139.7, category = "shop", type = "convenience")))
    }

    // ---------------------------------------------------------------- URL

    @Test
    fun buildsUrlWithEncodingAndParams() {
        val url = NominatimSearch.buildUrl("  葛西臨海公園　 駐輪場 ", null)
        assertTrue(url, url.startsWith("https://nominatim.openstreetmap.org/search?q="))
        val q = url.substringAfter("?q=").substringBefore("&")
        assertFalse("空白は + にしない", q.contains("+"))
        assertEquals("葛西臨海公園 駐輪場", URLDecoder.decode(q, "UTF-8"))
        assertTrue(url.contains("&format=jsonv2"))
        assertTrue(url.contains("&limit=10"))
        assertTrue(url.contains("&accept-language=ja"))
        assertFalse(url.contains("viewbox"))
        // & や = は値の中でエスケープ
        val amp = NominatimSearch.buildUrl("A&B=C", null)
        assertTrue(amp, amp.contains("q=A%26B%3DC&"))
    }

    @Test
    fun viewboxIsRoundedOutwardAndNotBounded() {
        val url = NominatimSearch.buildUrl("東京駅", GeoBounds(south = 35.6234, west = 139.6812, north = 35.7311, east = 139.8105))
        // 西,北,東,南（0.1 度で外側に丸める）
        assertTrue(url, url.endsWith("&viewbox=139.6,35.8,139.9,35.6&bounded=0"))
        // 西経・南緯
        assertEquals("-0.2,51.6,0.0,51.4", NominatimSearch.viewbox(GeoBounds(51.45, -0.15, 51.55, -0.01)))
        // 日付変更線をまたぐ・逆さま・NaN は付けない
        assertNull(NominatimSearch.viewbox(GeoBounds(10.0, 170.0, 20.0, -170.0)))
        assertNull(NominatimSearch.viewbox(GeoBounds(20.0, 0.0, 10.0, 1.0)))
        assertNull(NominatimSearch.viewbox(GeoBounds(Double.NaN, 0.0, 10.0, 1.0)))
        assertNull(NominatimSearch.viewbox(null))
    }

    @Test
    fun userAgentIdentifiesTheApp() {
        assertEquals("CycleRouteLogger/0.6.0 (jp.kjtr.cycleroutelogger)", NominatimSearch.userAgent("0.6.0"))
    }

    // ---------------------------------------------------------------- 1 秒の間隔

    private class FakeClock {
        var now = 10_000L
        val slept = mutableListOf<Long>()
        fun gate(interval: Long = 1000L) = RateGate(interval, { now }, { ms -> slept += ms; now += ms })
    }

    @Test
    fun rateGateWaitsOneSecondAfterThePreviousRequestEnded() = runBlocking {
        val c = FakeClock()
        val g = c.gate()
        g.run { c.now += 300 } // 1 回目は待たない（300 ms かかった）
        assertEquals(emptyList<Long>(), c.slept)
        c.now += 200 // 終わってから 200 ms 後に次
        g.run { }
        assertEquals(listOf(800L), c.slept)
        c.now += 5000 // 十分あいていれば待たない
        g.run { }
        assertEquals(listOf(800L), c.slept)
    }

    @Test
    fun rateGateCountsFailedRequestsToo() = runBlocking {
        val c = FakeClock()
        val g = c.gate()
        runCatching { g.run { error("boom") } }
        g.run { }
        assertEquals(listOf(1000L), c.slept)
    }

    // ---------------------------------------------------------------- キャッシュ・失敗

    private class Fixture(var response: () -> HttpText) {
        val clock = FakeClock()
        val urls = mutableListOf<String>()
        val agents = mutableListOf<String>()
        var online = true
        val search = NominatimSearch(
            userAgent = NominatimSearch.userAgent("0.6.0"),
            online = { online },
            http = { url, ua -> urls += url; agents += ua; response() },
            gate = clock.gate(),
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun sameQueryIsCachedAndNotSentAgain() = runBlocking {
        val f = Fixture { HttpText(200, tokyoStation) }
        val a = f.search.search("東京駅", null)
        val b = f.search.search(" 東京駅 ", null) // 前後の空白は同じ語
        assertEquals(2, a.size)
        assertEquals(a, b)
        assertEquals(1, f.urls.size)
        assertEquals(1, f.search.requestCount)
        assertEquals("CycleRouteLogger/0.6.0 (jp.kjtr.cycleroutelogger)", f.agents.single())
        // 違う語は送る（1 秒あけて）
        f.search.search("葛西臨海公園", null)
        assertEquals(2, f.urls.size)
        assertEquals(listOf(1000L), f.clock.slept)
        // 0 件もキャッシュする
        f.response = { HttpText(200, "[]") }
        assertTrue(f.search.search("ありえない地名", null).isEmpty())
        assertTrue(f.search.search("ありえない地名", null).isEmpty())
        assertEquals(3, f.urls.size)
        // 空の語は送らない
        assertTrue(f.search.search("   ", null).isEmpty())
        assertEquals(3, f.urls.size)
    }

    @Test
    fun cacheKeyIncludesRoundedViewbox() = runBlocking {
        val f = Fixture { HttpText(200, tokyoStation) }
        f.search.search("駅", GeoBounds(35.62, 139.68, 35.73, 139.81))
        // 少し動かしただけ（同じ 0.1 度の枠）なら同じ検索
        f.search.search("駅", GeoBounds(35.63, 139.69, 35.72, 139.85))
        assertEquals(1, f.urls.size)
        // 別の場所なら送り直す
        f.search.search("駅", GeoBounds(34.6, 135.4, 34.8, 135.6))
        assertEquals(2, f.urls.size)
    }

    @Test
    fun errorsAreReportedAndNotCached() = runBlocking {
        val f = Fixture { HttpText(429, "") }
        assertEquals(PlaceSearchException.Reason.RATE_LIMITED, reasonOf { f.search.search("東京駅", null) })
        f.response = { HttpText(500, "") }
        assertEquals(PlaceSearchException.Reason.HTTP_ERROR, reasonOf { f.search.search("東京駅", null) })
        f.response = { HttpText(200, "<html>") }
        assertEquals(PlaceSearchException.Reason.BAD_RESPONSE, reasonOf { f.search.search("東京駅", null) })
        f.response = { throw SocketTimeoutException() }
        assertEquals(PlaceSearchException.Reason.TIMEOUT, reasonOf { f.search.search("東京駅", null) })
        f.response = { throw java.net.UnknownHostException("nominatim.openstreetmap.org") }
        assertEquals(PlaceSearchException.Reason.OFFLINE, reasonOf { f.search.search("東京駅", null) })
        assertEquals(5, f.urls.size) // 失敗はキャッシュしない
        // オフラインなら送らない
        f.online = false
        f.response = { HttpText(200, tokyoStation) }
        assertEquals(PlaceSearchException.Reason.OFFLINE, reasonOf { f.search.search("東京駅", null) })
        assertEquals(5, f.urls.size)
        // 戻れば送って成功し、以後はオフラインでもキャッシュから
        f.online = true
        assertEquals(2, f.search.search("東京駅", null).size)
        f.online = false
        assertEquals(2, f.search.search("東京駅", null).size)
        assertEquals(6, f.urls.size)
    }

    private suspend fun reasonOf(block: suspend () -> Unit): PlaceSearchException.Reason {
        try {
            block()
        } catch (e: PlaceSearchException) {
            assertNotNull(e.reason)
            return e.reason
        }
        fail("例外が出なかった")
        throw IllegalStateException()
    }
}
