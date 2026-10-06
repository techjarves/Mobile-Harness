package com.jarves.mh

import android.os.Bundle
import android.content.ComponentCallbacks2
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarves.mh.ui.LocaleHelper
import com.jarves.mh.ui.MainViewModel
import com.jarves.mh.ui.PocketDevApp
import com.jarves.mh.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm = mainViewModel
            val state by vm.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val localizedContext = remember(state.languageCode, context) {
                LocaleHelper.applyLanguage(context, state.languageCode)
            }
            val localizedConfiguration = localizedContext.resources.configuration
            val layoutDirection: LayoutDirection =
                if (localizedConfiguration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) Rtl else Ltr
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
                LocalLayoutDirection provides layoutDirection,
                LocalActivityResultRegistryOwner provides this,
            ) {
                PocketTheme(themeMode = state.themeMode) {
                    PocketDevApp(vm)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            mainViewModel.releaseMemoryCaches()
        }
    }
}
