package jp.kjtr.cycleroutelogger.plan

/**
 * 経由地の種類。[code] は DB と GPX の `<type>` に書く値（変えないこと）。
 * [gpxSym] は Garmin 系の `<sym>` 名（ほかのアプリで開いたときの目印用）。
 * 画面の名前・アイコン・色は ui/WaypointStyle.kt。
 */
enum class WaypointType(val code: String, val gpxSym: String) {
    FOOD("food", "Restaurant"),
    REST("rest", "Picnic Area"),
    SIGHT("sight", "Scenic Area"),
    PARKING("bike_parking", "Parking Area"),
    LODGING("lodging", "Lodging"),
    STATION("station", "Train Station"),
    TOILET("toilet", "Restroom"),
    CONVENIENCE("convenience", "Convenience Store"),
    WATER("water", "Drinking Water"),
    OTHER("other", "Flag, Blue");

    companion object {
        fun fromCode(code: String?): WaypointType = entries.firstOrNull { it.code == code } ?: OTHER

        /**
         * GPX の `<type>` / `<sym>`（と名前）から種類を推定する。このアプリが書いた `<type>` の値はそのまま。
         * 分からなければ [OTHER]。「Restroom」が「rest」に当たらないよう、判定の順番に意味がある。
         */
        fun infer(type: String?, sym: String?, name: String? = null): WaypointType {
            entries.firstOrNull { it.code.equals(type?.trim(), ignoreCase = true) }?.let { return it }
            for (src in listOf(type, sym, name)) {
                val s = src?.lowercase()?.trim() ?: continue
                if (s.isEmpty()) continue
                RULES.firstOrNull { (_, words) -> words.any { s.contains(it) } }?.let { return it.first }
            }
            return OTHER
        }

        private val RULES: List<Pair<WaypointType, List<String>>> = listOf(
            // 「道の駅」は駅ではなく休憩地点
            REST to listOf("道の駅", "rest area", "rest stop"),
            TOILET to listOf("toilet", "restroom", "wc", "lavatory", "トイレ", "便所", "化粧室"),
            WATER to listOf("drinking water", "water", "fountain", "給水", "水飲み", "水場"),
            CONVENIENCE to listOf("convenience", "コンビニ", "shopping", "store", "shop", "market", "スーパー", "売店"),
            PARKING to listOf("bike parking", "bicycle parking", "parking", "駐輪", "駐車"),
            LODGING to listOf("lodging", "hotel", "hostel", "motel", "camp", "宿", "ホテル", "旅館", "キャンプ"),
            STATION to listOf("station", "train", "railway", "transit", "transport", "駅", "鉄道"),
            FOOD to listOf("restaurant", "food", "cafe", "café", "coffee", "dining", "pizza", "bar", "飲食", "食", "レストラン", "カフェ", "喫茶", "ランチ"),
            SIGHT to listOf("scenic", "view", "sight", "attraction", "museum", "landmark", "photo", "summit", "tourism", "観光", "名所", "展望", "景"),
            REST to listOf("rest", "picnic", "break", "bench", "park", "休憩", "道の駅", "公園"),
        )
    }
}
