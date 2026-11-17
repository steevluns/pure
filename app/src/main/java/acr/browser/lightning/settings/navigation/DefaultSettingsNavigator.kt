package acr.browser.lightning.settings.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/**
 * Default implementation of [SettingsNavigator].
 *
 * A StateFlow retains the latest destination so an initial navigation request
 * (for example, opening Ad Block Settings directly from the browser menu) is
 * not lost before the Settings screen starts collecting navigation updates.
 */
class DefaultSettingsNavigator @Inject constructor() : SettingsNavigator {

    private val destination = MutableStateFlow(SettingsNavigation.ROOT)

    override val events = destination

    override fun navigateTo(settingsNavigation: SettingsNavigation) {
        destination.value = settingsNavigation
    }
}
