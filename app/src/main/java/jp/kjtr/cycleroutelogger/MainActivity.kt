package jp.kjtr.cycleroutelogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import jp.kjtr.cycleroutelogger.ui.AppNav
import jp.kjtr.cycleroutelogger.ui.CycleTheme
import jp.kjtr.cycleroutelogger.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 では edge-to-edge が強制。各画面は WindowInsets で避ける。
        enableEdgeToEdge()
        setContent {
            CycleTheme { AppNav(vm) }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 他のアプリからの「開く」「共有」で来た GPX を読み込む（どのプランに入れるかを聞く）。 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // 更新の確認（debug だけ。DESIGN.md §10）: `am start … --es updateAs 0.2.0` = いまの版をその版として比べ、すぐ releases.json を読む（設定 OFF なら読まない）。
        if (BuildConfig.DEBUG) intent.getStringExtra("updateAs")?.let { (application as CycleApp).updates.pretend(it) }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.importUris(uris, null)
    }
}
