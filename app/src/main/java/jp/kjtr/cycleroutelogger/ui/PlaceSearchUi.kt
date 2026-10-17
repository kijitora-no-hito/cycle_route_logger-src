package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.NominatimSearch
import jp.kjtr.cycleroutelogger.geo.PlaceResult
import jp.kjtr.cycleroutelogger.geo.PlaceSearchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 場所名の検索の画面側の状態（ルートプラン・ルートを描く・走行画面で共用。DESIGN.md §4.6）。
 * 検索は [submit]（検索ボタン / IME の検索キー）のときだけ。入力中に送ることはない（Nominatim の利用ポリシー）。
 */
@Stable
class PlaceSearchState {
    /** 走行画面のように畳んでおく画面で、検索欄を開いているか。 */
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var results by mutableStateOf<List<PlaceResult>>(emptyList())
    /** 0 件・失敗の文。 */
    var message by mutableStateOf<String?>(null)
    var searchedQuery by mutableStateOf("")
    /** 結果の一覧を地図に重ねているか。 */
    var listShown by mutableStateOf(false)
    /** 一覧で選んだ場所（地図に印、下にカード）。 */
    var picked by mutableStateOf<PlaceResult?>(null)

    fun clearResults() {
        results = emptyList()
        message = null
        listShown = false
        picked = null
    }

    /** 検索欄を閉じる（入力・結果・選んだ場所も消す）。 */
    fun close() {
        clearResults()
        query = ""
        open = false
    }

    /** 検索する。[near] = 今の地図の表示範囲（近い結果を優先）。 */
    fun submit(scope: CoroutineScope, vm: MainViewModel, near: GeoBounds?) {
        val q = NominatimSearch.normalize(query)
        if (q.isEmpty() || searching) return
        searching = true
        message = null
        picked = null
        scope.launch {
            try {
                val r = vm.app.placeSearch.search(q, near)
                results = r
                message = if (r.isEmpty()) vm.app.getString(R.string.place_none, q) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaceSearchException) {
                results = emptyList()
                message = when (e.reason) {
                    PlaceSearchException.Reason.OFFLINE -> vm.app.getString(R.string.place_err_offline)
                    PlaceSearchException.Reason.TIMEOUT -> vm.app.getString(R.string.place_err_timeout)
                    PlaceSearchException.Reason.RATE_LIMITED -> vm.app.getString(R.string.place_err_rate)
                    PlaceSearchException.Reason.NETWORK -> vm.app.getString(R.string.place_err_network)
                    PlaceSearchException.Reason.HTTP_ERROR -> vm.app.getString(R.string.place_err_http, e.httpCode)
                    PlaceSearchException.Reason.BAD_RESPONSE -> vm.app.getString(R.string.place_err_bad)
                }
            } catch (e: Exception) {
                results = emptyList()
                message = vm.app.getString(R.string.place_err_network)
            } finally {
                searchedQuery = q
                searching = false
                listShown = true
            }
        }
    }
}

@Composable
fun rememberPlaceSearchState(): PlaceSearchState = remember { PlaceSearchState() }

/** 距離の出し方: 現在地が分かれば現在地から、無ければ地図の中心から。 */
data class PlaceOrigin(val point: LatLon, val isHere: Boolean)

@Composable
private fun distanceText(origin: PlaceOrigin?, p: PlaceResult): String? {
    origin ?: return null
    val d = Format.distance(Geo.distanceM(origin.point, p.latLon))
    return stringResource(if (origin.isHere) R.string.place_from_here else R.string.place_from_center, d)
}

/** 出典（ODbL の帰属表示）。検索結果を出す所には必ず置く。 */
@Composable
fun PlaceAttribution(vm: MainViewModel, modifier: Modifier = Modifier) {
    Text(
        vm.app.placeSearch.attribution,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = modifier,
    )
}

/**
 * 検索欄（地図の上に重ねる 1 行）。[onSubmit] で検索（キーボードは閉じる）。[onCollapse] があれば右端に［閉じる］（畳む）。
 */
@Composable
fun PlaceSearchBar(
    state: PlaceSearchState,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.place_search_hint),
    onCollapse: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun go() {
        keyboard?.hide()
        focus.clearFocus()
        onSubmit()
    }
    val desc = stringResource(R.string.place_search_open)
    Surface(modifier, shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, tonalElevation = 2.dp) {
        Row(Modifier.height(48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) {
                    Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = desc },
                )
            }
            if (state.searching) {
                CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(20.dp), strokeWidth = 2.dp)
            } else if (state.query.isNotEmpty()) {
                IconButton(onClick = { state.query = ""; state.clearResults() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.place_clear))
                }
            }
            IconButton(onClick = { go() }, enabled = !state.searching && state.query.isNotBlank()) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.place_search))
            }
            if (onCollapse != null) {
                IconButton(onClick = onCollapse) { Icon(Icons.Filled.ExpandLess, contentDescription = stringResource(R.string.place_search_close)) }
            }
        }
    }
}

/**
 * 検索結果の一覧（地図の上に重ねる）。名前・住所の要約・[origin] からの距離。0 件・失敗の文もここに出す。下に出典。
 * [listShown] が false なら何も出さない。
 */
@Composable
fun PlaceResultsPanel(
    vm: MainViewModel,
    state: PlaceSearchState,
    origin: PlaceOrigin?,
    maxHeight: Dp,
    onPick: (PlaceResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.listShown || (state.results.isEmpty() && state.message == null)) return
    Surface(modifier, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Column(Modifier.heightIn(max = maxHeight.coerceAtLeast(96.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.message ?: stringResource(R.string.place_results, state.searchedQuery, state.results.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.message != null && state.results.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
                TextButton(onClick = { state.listShown = false }) { Text(stringResource(R.string.close)) }
            }
            if (state.results.isNotEmpty()) {
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(state.results) { r ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    NominatimSearch.addressSummary(r),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                distanceText(origin, r)?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            PlaceAttribution(vm, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

/** 一覧で選んだ場所のカード（名前・住所・距離・操作のボタン・出典）。 */
@Composable
fun PickedPlaceCard(
    vm: MainViewModel,
    place: PlaceResult,
    origin: PlaceOrigin?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Place, contentDescription = stringResource(R.string.place_marker), tint = colorResource(R.color.place_result))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(NominatimSearch.addressSummary(place).ifBlank { null }, distanceText(origin, place)).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
            PlaceAttribution(vm, Modifier.padding(top = 2.dp, end = 12.dp))
        }
    }
}
