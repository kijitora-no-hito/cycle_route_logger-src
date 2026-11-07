package jp.kjtr.cycleroutelogger.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.photo.PhotoRepository
import kotlinx.coroutines.delay

/**
 * アプリ内カメラ（CameraX）。1 タップで撮ってフォトライブラリ（Pictures/自転車ルートロガー）に保存し、アプリに登録する。
 * 連写できる。戻るで走行画面へ。カメラの権限は初めて開いたときに求める（Android 9 以下はストレージの書き込みも）。
 */
@Composable
fun CameraScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val needed = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    var hasPerm by remember { mutableStateOf(needed.all { granted(it) }) }
    var asked by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = needed.all { p -> granted(p) }
        asked = true
    }
    LaunchedEffect(Unit) { if (!hasPerm) permLauncher.launch(needed.toTypedArray()) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var shots by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    val flashAlpha by animateFloatAsState(if (flash) 0.8f else 0f, label = "flash")
    LaunchedEffect(flash) { if (flash) { delay(120); flash = false } }
    val lastThumb by vm.lastCapturedThumb.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPerm) {
            val previewView = remember { PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView（スクリーンショット・画面の重ね描きと相性がよい）
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                } }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            DisposableEffect(lifecycleOwner) {
                val future = ProcessCameraProvider.getInstance(context)
                var provider: ProcessCameraProvider? = null
                future.addListener({
                    runCatching {
                        val p = future.get()
                        provider = p
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }.onFailure { vm.toast(context, context.getString(R.string.camera_failed)) }
                }, ContextCompat.getMainExecutor(context))
                onDispose { runCatching { provider?.unbindAll() } }
            }
            Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_perm_explain), color = Color.White)
                Spacer(Modifier.size(12.dp))
                Button(onClick = {
                    if (asked) BackgroundSettings.openAppDetails(context) else permLauncher.launch(needed.toTypedArray())
                }) { Text(stringResource(if (asked) R.string.open_app_settings else R.string.perm_allow)) }
            }
        }

        // 上: 戻る・注意
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallFloatingActionButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Surface(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    stringResource(R.string.camera_safety),
                    color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // 下: 最後の写真・シャッター・枚数
        if (hasPerm) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    lastThumb?.let { path ->
                        val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                        if (bmp != null) {
                            Image(
                                bmp, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .size(84.dp)
                        .border(5.dp, Color.White, CircleShape)
                        .padding(9.dp)
                        .background(if (busy) Color.LightGray else Color.White, CircleShape)
                        .clip(CircleShape)
                        .clickable(enabled = !busy) {
                            busy = true
                            flash = true
                            val now = System.currentTimeMillis()
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, PhotoRepository.captureName(now) + ".jpg")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                if (Build.VERSION.SDK_INT >= 29) {
                                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + PhotoRepository.ALBUM)
                                }
                            }
                            val options = ImageCapture.OutputFileOptions.Builder(
                                context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                            ).build()
                            imageCapture.takePicture(
                                options, ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        output.savedUri?.let { vm.onPhotoCaptured(it, now) }
                                        shots++
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        vm.toast(context, context.getString(R.string.camera_save_failed))
                                    }
                                },
                            )
                        },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (shots > 0) Text(stringResource(R.string.camera_shots, shots), color = Color.White)
                }
            }
        }
    }
}
