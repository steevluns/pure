package acr.browser.lightning.utils

import javax.inject.Inject

/**
 * No-op debug hook. LeakCanary is intentionally excluded from this app build.
 */
class LeakCanaryUtils @Inject constructor() {
    fun setup() = Unit
}
