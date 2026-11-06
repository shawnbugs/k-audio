package com.shawnbugs.kaudio

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.IntentCompat
import com.shawnbugs.kaudio.ui.A
import com.shawnbugs.kaudio.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Studio.init(this)
        // 只有深色一套，系统栏图标固定浅色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            // 界面基本自绘，这套配色只管 Material 组件（弹框、输入框、下拉菜单）
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = A.Brand,
                    onPrimary = A.OnBrand,
                    primaryContainer = A.BrandSoft,
                    onPrimaryContainer = A.Brand,
                    secondary = A.Violet,
                    secondaryContainer = A.BrandSoft,
                    onSecondaryContainer = A.Brand,
                    error = A.Red,
                    background = A.Bg,
                    onBackground = A.Text1,
                    surface = A.Card,
                    onSurface = A.Text1,
                    onSurfaceVariant = A.Text2,
                    surfaceContainerLowest = A.Card,
                    surfaceContainerLow = A.Card,
                    surfaceContainer = A.Card,
                    surfaceContainerHigh = A.Card,
                    surfaceContainerHighest = A.Field,
                    outline = A.Text3,
                    outlineVariant = A.Line,
                ),
            ) {
                // 系统字体调到很大时排版会挤爆：字号最多跟到 1.3 倍
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onStop() {
        super.onStop()
        Studio.onBackground()
    }

    /** 别的 App「分享」或「用 K剪音 打开」进来的文件 */
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        }
        if (uris.isNotEmpty()) Studio.importUris(uris)
    }
}
