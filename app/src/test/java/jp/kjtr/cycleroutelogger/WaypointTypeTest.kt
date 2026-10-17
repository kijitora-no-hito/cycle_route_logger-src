package jp.kjtr.cycleroutelogger

import jp.kjtr.cycleroutelogger.plan.WaypointType
import jp.kjtr.cycleroutelogger.plan.WaypointType.CONVENIENCE
import jp.kjtr.cycleroutelogger.plan.WaypointType.FOOD
import jp.kjtr.cycleroutelogger.plan.WaypointType.LODGING
import jp.kjtr.cycleroutelogger.plan.WaypointType.OTHER
import jp.kjtr.cycleroutelogger.plan.WaypointType.PARKING
import jp.kjtr.cycleroutelogger.plan.WaypointType.REST
import jp.kjtr.cycleroutelogger.plan.WaypointType.SIGHT
import jp.kjtr.cycleroutelogger.plan.WaypointType.STATION
import jp.kjtr.cycleroutelogger.plan.WaypointType.TOILET
import jp.kjtr.cycleroutelogger.plan.WaypointType.WATER
import org.junit.Assert.assertEquals
import org.junit.Test

class WaypointTypeTest {
    @Test
    fun ownCodesRoundTrip() {
        for (t in WaypointType.entries) {
            assertEquals(t, WaypointType.infer(t.code, null))
            assertEquals(t, WaypointType.fromCode(t.code))
            // このアプリが書く sym だけでも同じ種類に戻る
            assertEquals(t, WaypointType.infer(null, t.gpxSym))
        }
        assertEquals(OTHER, WaypointType.fromCode("unknown"))
        assertEquals(OTHER, WaypointType.fromCode(null))
    }

    @Test
    fun garminSymbols() {
        assertEquals(FOOD, WaypointType.infer(null, "Restaurant"))
        assertEquals(FOOD, WaypointType.infer(null, "Fast Food"))
        assertEquals(TOILET, WaypointType.infer(null, "Restroom"))
        assertEquals(WATER, WaypointType.infer(null, "Drinking Water"))
        assertEquals(LODGING, WaypointType.infer(null, "Lodging"))
        assertEquals(LODGING, WaypointType.infer(null, "Campground"))
        assertEquals(STATION, WaypointType.infer(null, "Train Station"))
        assertEquals(PARKING, WaypointType.infer(null, "Parking Area"))
        assertEquals(SIGHT, WaypointType.infer(null, "Scenic Area"))
        assertEquals(SIGHT, WaypointType.infer(null, "Museum"))
        assertEquals(REST, WaypointType.infer(null, "Picnic Area"))
        assertEquals(CONVENIENCE, WaypointType.infer(null, "Convenience Store"))
        assertEquals(CONVENIENCE, WaypointType.infer(null, "Shopping Center"))
        assertEquals(OTHER, WaypointType.infer(null, "Flag, Blue"))
    }

    @Test
    fun japaneseAndTypeFirst() {
        assertEquals(TOILET, WaypointType.infer("トイレ", null))
        assertEquals(REST, WaypointType.infer(null, null, "道の駅 しょうなん"))
        assertEquals(STATION, WaypointType.infer(null, null, "東京駅"))
        assertEquals(CONVENIENCE, WaypointType.infer("コンビニ", "Restaurant"))
        assertEquals(PARKING, WaypointType.infer("駐輪場", null))
        assertEquals(FOOD, WaypointType.infer(null, null, "ランチのお店"))
        // type が分からなければ sym、それも分からなければ名前
        assertEquals(WATER, WaypointType.infer("???", "Drinking Water", "トイレ"))
        assertEquals(OTHER, WaypointType.infer(null, null, null))
        assertEquals(OTHER, WaypointType.infer("", "", "Waypoint 12"))
    }
}
