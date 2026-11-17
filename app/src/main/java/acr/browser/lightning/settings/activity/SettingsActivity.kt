/*
 * Copyright 2014 A.C.R. Development
 */
package acr.browser.lightning.settings.activity

import acr.browser.lightning.ThemableActivity
import acr.browser.lightning.compose.BrowserTheme
import acr.browser.lightning.compose.slideInFrom
import acr.browser.lightning.di.injector
import acr.browser.lightning.settings.SettingsScreenStateProvider
import acr.browser.lightning.settings.framework.SettingsFrameworkPresenter
import acr.browser.lightning.settings.framework.SettingsFrameworkScreen
import acr.browser.lightning.settings.licenses.LicensesScreen
import acr.browser.lightning.settings.licenses.LicensesScreenPresenter
import acr.browser.lightning.settings.navigation.SettingsNavigation
import acr.browser.lightning.settings.navigation.SettingsNavigator
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import javax.inject.Inject

class SettingsActivity : ThemableActivity() {

    companion object {
        const val EXTRA_SETTINGS_DESTINATION = "settings_destination"
    }

    @Inject internal lateinit var settingsScreenStateProvider: SettingsScreenStateProvider
    @Inject internal lateinit var licensesScreenPresenterFactory: LicensesScreenPresenter.Factory
    @Inject internal lateinit var settingsNavigator: SettingsNavigator

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.settingsComponentBuilder()
            .incognitoMode(false)
            .activity(this)
            .build()
            .inject(this)

        super.onCreate(savedInstanceState)

        val initialDestination = intent
            .getStringExtra(EXTRA_SETTINGS_DESTINATION)
            ?.let { destination -> SettingsNavigation.values().firstOrNull { it.name == destination } }
            ?: SettingsNavigation.ROOT

        setContent {
            LaunchedEffect(initialDestination) {
                if (initialDestination != SettingsNavigation.ROOT) {
                    settingsNavigator.navigateTo(initialDestination)
                }
            }
            BrowserTheme(appThemeStateFlow) {
                val navigationState by settingsNavigator.events.collectAsState(SettingsNavigation.ROOT)
                AnimatedContent(navigationState, transitionSpec = {
                    when {
                        targetState == initialState.parent -> slideInFrom { -it / 2 }
                        else -> slideInFrom { it / 2 }
                    }
                }) { state ->
                    when (state) {
                        SettingsNavigation.LICENSES -> LicensesScreen(
                            useBlackStatusBarStateFlow,
                            viewModel(
                                key = "licenses",
                                factory = licensesScreenPresenterFactory
                            ),
                            onClickUrl = {
                                startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                            }
                        ) {
                            settingsNavigator.navigateTo(SettingsNavigation.ABOUT)
                        }

                        else -> {
                            val frameworkState = settingsScreenStateProvider.provideState(state)
                            SettingsFrameworkScreen(
                                useBlackStatusBarStateFlow,
                                viewModel(
                                    key = state.name,
                                    factory = SettingsFrameworkPresenter.Factory(
                                        settingsFrameworkState = { frameworkState },
                                        settingsNavigator = settingsNavigator,
                                    )
                                )
                            ) {
                                if (state == SettingsNavigation.ROOT ||
                                    (initialDestination != SettingsNavigation.ROOT && state == initialDestination)
                                ) {
                                    finish()
                                } else {
                                    settingsNavigator.navigateTo(SettingsNavigation.ROOT)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
