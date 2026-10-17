package jp.kjtr.cycleroutelogger.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalConvenienceStore
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.plan.WaypointType

/** 経由地の種類ごとの名前・アイコン・色。 */
data class WaypointStyle(@StringRes val label: Int, val icon: ImageVector, val color: Color)

fun WaypointType.style(): WaypointStyle = when (this) {
    WaypointType.FOOD -> WaypointStyle(R.string.wp_food, Icons.Filled.Restaurant, Color(0xFFE65100))
    WaypointType.REST -> WaypointStyle(R.string.wp_rest, Icons.Filled.Weekend, Color(0xFF2E7D32))
    WaypointType.SIGHT -> WaypointStyle(R.string.wp_sight, Icons.Filled.PhotoCamera, Color(0xFFAD1457))
    WaypointType.PARKING -> WaypointStyle(R.string.wp_parking, Icons.Filled.LocalParking, Color(0xFF1565C0))
    WaypointType.LODGING -> WaypointStyle(R.string.wp_lodging, Icons.Filled.Bed, Color(0xFF4527A0))
    WaypointType.STATION -> WaypointStyle(R.string.wp_station, Icons.Filled.Train, Color(0xFF37474F))
    WaypointType.TOILET -> WaypointStyle(R.string.wp_toilet, Icons.Filled.Wc, Color(0xFF00838F))
    WaypointType.CONVENIENCE -> WaypointStyle(R.string.wp_convenience, Icons.Filled.LocalConvenienceStore, Color(0xFFF9A825))
    WaypointType.WATER -> WaypointStyle(R.string.wp_water, Icons.Filled.WaterDrop, Color(0xFF0277BD))
    WaypointType.OTHER -> WaypointStyle(R.string.wp_other, Icons.Filled.Flag, Color(0xFF616161))
}

@Composable
fun WaypointType.label(): String = stringResource(style().label)

/** 丸の中に種類のアイコン（地図のマーカー・一覧で共用）。 */
@Composable
fun WaypointBadge(type: WaypointType, size: Dp = 32.dp, highlighted: Boolean = false, modifier: Modifier = Modifier) {
    val st = type.style()
    Box(
        modifier = modifier
            .size(size)
            .background(st.color, CircleShape)
            .border(if (highlighted) 3.dp else 2.dp, if (highlighted) Color(0xFFFFEB3B) else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(st.icon, contentDescription = null, tint = Color.White, modifier = Modifier.padding(size * 0.18f))
    }
}
