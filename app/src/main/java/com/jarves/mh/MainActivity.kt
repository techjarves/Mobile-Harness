package com.jarves.mh

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarves.mh.ui.AppStrings
import com.jarves.mh.ui.LocaleHelper
import com.jarves.mh.ui.MainViewModel
import com.jarves.mh.ui.PocketDevApp
import com.jarves.mh.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    private var appliedLanguageCode: String? = null

    // Apply the in-app language to the Activity itself, not only to the Compose tree:
    // dialogs, dropdown menus and popups get their own window whose context is the Activity.
    override fun attachBaseContext(newBase: Context) {
        appliedLanguageCode = AppStrings.languageCode(newBase)
        super.attachBaseContext(AppStrings.context(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.languageCode) {
                if (state.languageCode != appliedLanguageCode) recreate()
            }
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
}
