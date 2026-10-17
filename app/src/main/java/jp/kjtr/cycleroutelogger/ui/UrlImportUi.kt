package jp.kjtr.cycleroutelogger.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import jp.kjtr.cycleroutelogger.R
import java.util.Locale

/** GPX の読み込み元を選ぶボタン（ファイル / QR コード / URL）。 */
@Composable
fun ImportMenuButton(onFile: () -> Unit, onQr: () -> Unit, onUrl: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.FileOpen, contentDescription = stringResource(R.string.import_gpx)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_from_file)) },
                leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                onClick = { open = false; onFile() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_from_qr)) },
                leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                onClick = { open = false; onQr() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_from_url)) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                onClick = { open = false; onUrl() },
            )
        }
    }
}

/** QR コードを読む関数（引数は読み込み先のプラン。null なら後で聞く）。 */
@Composable
fun rememberQrScan(vm: MainViewModel): (Long?) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { planId: Long? ->
            QrScan.start(
                context,
                onText = { vm.importFromQr(it, planId) },
                onUnavailable = { vm.qrUnavailable(planId, it) },
                onDownloading = { vm.toast(context, context.getString(R.string.qr_downloading)) },
            )
        }
    }
}

/**
 * Google Code Scanner（GmsBarcodeScanning）。読み取りの画面は Google Play 開発者サービスが出すので、アプリに CAMERA 権限は要らない。
 * 読み取り機能（モジュール）が端末に無ければ ModuleInstallClient でダウンロードしてから始める。
 */
object QrScan {
    fun start(context: Context, onText: (String) -> Unit, onUnavailable: (String?) -> Unit, onDownloading: () -> Unit) {
        val gaa = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (gaa != ConnectionResult.SUCCESS) {
            onUnavailable(null)
            return
        }
        val scanner = runCatching {
            GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        }.getOrElse {
            onUnavailable(it.message)
            return
        }

        fun scan() {
            scanner.startScan()
                .addOnSuccessListener { b ->
                    val v = b.rawValue ?: b.displayValue
                    if (v.isNullOrBlank()) onUnavailable(null) else onText(v)
                }
                .addOnFailureListener { e -> onUnavailable(e.message) }
            // 取り消し（戻る）は何もしない
        }

        val mi = ModuleInstall.getClient(context)
        mi.areModulesAvailable(scanner)
            .addOnSuccessListener { r ->
                if (r.areModulesAvailable()) {
                    scan()
                    return@addOnSuccessListener
                }
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        when (update.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                mi.unregisterListener(this)
                                scan()
                            }
                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                mi.unregisterListener(this)
                                onUnavailable(null)
                            }
                            else -> Unit
                        }
                    }
                }
                mi.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build())
                    .addOnSuccessListener { resp ->
                        if (resp.areModulesAlreadyInstalled()) {
                            mi.unregisterListener(listener)
                            scan()
                        } else {
                            onDownloading()
                        }
                    }
                    .addOnFailureListener { e ->
                        mi.unregisterListener(listener)
                        onUnavailable(e.message)
                    }
            }
            // 確かめられなければ、とりあえず読み取りを始めてみる（失敗すれば案内）
            .addOnFailureListener { scan() }
    }
}

/** URL の入力・取得中・確認・失敗のダイアログ（アプリ全体で 1 か所に出す）。 */
@Composable
fun UrlImportDialogs(vm: MainViewModel) {
    val input by vm.urlInput.collectAsStateWithLifecycle()
    val state by vm.urlImport.collectAsStateWithLifecycle()

    input?.let { req ->
        UrlInputDialog(
            initial = req.initial,
            onDone = { url -> vm.closeUrlInput(); vm.importFromUrl(url, req.targetPlanId) },
            onDismiss = { vm.closeUrlInput() },
        )
    }

    when (val s = state) {
        null -> Unit
        is UrlImportUi.Loading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.url_loading_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.url, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    val total = s.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(progress = { (s.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.url_loading_bytes_total, mb(s.bytes), mb(total)), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (s.bytes > 0) Text(stringResource(R.string.url_loading_bytes, mb(s.bytes)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancelUrlImport() }) { Text(stringResource(R.string.cancel)) } },
        )
        is UrlImportUi.Confirm -> {
            val d = s.item.data
            val title = d.name ?: d.tracks.firstOrNull()?.name ?: s.item.fileName
            AlertDialog(
                onDismissRequest = { vm.cancelUrlImport() },
                title = { Text(stringResource(if (s.replace != null) R.string.url_confirm_reload_title else R.string.url_confirm_title)) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.url_confirm_routes, d.tracks.size, Format.km(s.distanceM), d.pointCount))
                        d.tracks.take(6).forEach { t ->
                            Text("・" + (t.name ?: s.item.fileName.substringBeforeLast('.')), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        if (d.tracks.size > 6) Text(stringResource(R.string.url_confirm_more, d.tracks.size - 6), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.url_confirm_waypoints, d.waypoints.size))
                        if (d.decimated) Text(stringResource(R.string.url_confirm_decimated), style = MaterialTheme.typography.bodySmall)
                        if (d.partial) Text(stringResource(R.string.url_confirm_partial), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.url_confirm_source), style = MaterialTheme.typography.labelMedium)
                        Text(s.sourceLabel, style = MaterialTheme.typography.bodySmall)
                        s.replace?.let { r ->
                            Text(stringResource(R.string.url_confirm_replace, r.routes, r.waypoints), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.confirmUrlImport() }) { Text(stringResource(R.string.url_import_do)) } },
                dismissButton = { TextButton(onClick = { vm.cancelUrlImport() }) { Text(stringResource(R.string.url_import_cancel)) } },
            )
        }
        is UrlImportUi.Failed -> AlertDialog(
            onDismissRequest = { vm.cancelUrlImport() },
            title = { Text(stringResource(R.string.url_failed_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(s.message) } },
            confirmButton = {
                if (s.retryInput != null) {
                    TextButton(onClick = { vm.retryUrlInput() }) {
                        Text(stringResource(if (s.retryInput.isEmpty()) R.string.url_paste_instead else R.string.url_retry_input))
                    }
                } else {
                    TextButton(onClick = { vm.cancelUrlImport() }) { Text(stringResource(R.string.close)) }
                }
            },
            dismissButton = if (s.retryInput != null) ({ TextButton(onClick = { vm.cancelUrlImport() }) { Text(stringResource(R.string.close)) } }) else null,
        )
    }
}

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

@Composable
private fun UrlInputDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.url_input_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("https://") },
                    singleLine = false,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it.trim() }
                        }) { Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.url_paste)) }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(stringResource(R.string.url_input_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.url_input_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
