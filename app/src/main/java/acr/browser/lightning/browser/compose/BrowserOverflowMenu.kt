package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.menu.MenuSelection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
fun BrowserOverflowMenu(presenter: BrowserPresenter, browserViewState: BrowserComposeState) {
    Box {
        var dropDownExpanded by remember { mutableStateOf(false) }
        var searchEngineDialog by remember { mutableStateOf(false) }
        var searchEngineNames by remember { mutableStateOf(emptyList<String>()) }
        var selectedSearchEngine by remember { mutableStateOf(-1) }
        var customSearchUrl by remember { mutableStateOf("") }
        var customSearchDialog by remember { mutableStateOf(false) }
        var gameSearchDialog by remember { mutableStateOf(false) }
        var videoSearchDialog by remember { mutableStateOf(false) }
        IconButton(onClick = {
            dropDownExpanded = !dropDownExpanded
        }) {
            Icon(
                painter = painterResource(R.drawable.more),
                contentDescription = "more"
            )
        }
        DropdownMenu(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .defaultMinSize(minWidth = 175.dp),
            shape = MaterialTheme.shapes.small,
            properties = PopupProperties(focusable = true, clippingEnabled = false),
            expanded = dropDownExpanded,
            onDismissRequest = { dropDownExpanded = false }
        ) {
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_incognito)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.NEW_INCOGNITO_TAB))
                        dropDownExpanded = false
                    }
                )
            }
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_history)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.HISTORY))
                        dropDownExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_translate_to_arabic)) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.TRANSLATE_TO_ARABIC))
                    dropDownExpanded = false
                }
            )
            if (browserViewState.enableFullMenu) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.action_copy)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.COPY_LINK))
                        dropDownExpanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.hide_images)) },
                onClick = { presenter.toggleImageBlocking(); dropDownExpanded = false },
                trailingIcon = {
                    androidx.compose.material3.Checkbox(
                        checked = browserViewState.blockImagesEnabled,
                        onCheckedChange = null
                    )
                }
            )
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.hide_videos)) },
                onClick = { presenter.toggleVideoBlocking(); dropDownExpanded = false },
                trailingIcon = {
                    androidx.compose.material3.Checkbox(
                        checked = browserViewState.blockVideosEnabled,
                        onCheckedChange = null
                    )
                }
            )
            DropdownMenuItem(
                text = { DropdownText("النافذة المنبثقة") },
                onClick = {
                    presenter.togglePopupConfirmation()
                    dropDownExpanded = false
                },
                trailingIcon = {
                    Text(
                        text = if (browserViewState.popupConfirmationEnabled) "فتح" else "إغلاق",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.action_bookmarks)) },
                onClick = {
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.BOOKMARKS))
                    dropDownExpanded = false
                }
            )
            DropdownMenuItem(
                text = { DropdownText("محرك البحث") },
                onClick = {
                    dropDownExpanded = false
                    presenter.loadSearchEngineOptions { names, selected ->
                        searchEngineNames = names
                        selectedSearchEngine = selected
                        searchEngineDialog = true
                    }
                }
            )
            DropdownMenuItem(
                text = { DropdownText("محركات البحث ٢") },
                onClick = {
                    dropDownExpanded = false
                    gameSearchDialog = true
                }
            )
            DropdownMenuItem(
                text = { DropdownText("بحث فيديو") },
                onClick = {
                    dropDownExpanded = false
                    videoSearchDialog = true
                }
            )
            // Keep Ad Block available in the overflow menu and open the existing
            // dedicated ad-block settings screen when selected.
            DropdownMenuItem(
                text = { DropdownText(stringResource(R.string.settings_adblock)) },
                onClick = {
                    dropDownExpanded = false
                    presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.ADBLOCK_SETTINGS))
                }
            )
            if (!browserViewState.isIncognito) {
                DropdownMenuItem(
                    text = { DropdownText(stringResource(R.string.settings)) },
                    onClick = {
                        presenter.onEvent(BrowserUiEvent.MenuClick(MenuSelection.SETTINGS))
                        dropDownExpanded = false
                    }
                )
            }
        }
        if (searchEngineDialog) {
            AlertDialog(
                onDismissRequest = { searchEngineDialog = false },
                title = { Text("محرك البحث") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(searchEngineNames.size) { index ->
                            androidx.compose.material3.RadioButton(
                                selected = selectedSearchEngine == index,
                                onClick = {
                                    selectedSearchEngine = index
                                    if (index == 0) {
                                        searchEngineDialog = false
                                        customSearchDialog = true
                                    } else {
                                        presenter.selectSearchEngine(index)
                                        searchEngineDialog = false
                                    }
                                }
                            )
                            Text(
                                text = searchEngineNames[index],
                                modifier = Modifier
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { searchEngineDialog = false }) { Text("إغلاق") }
                }
            )
        }
        if (gameSearchDialog) {
            AlertDialog(
                onDismissRequest = { gameSearchDialog = false },
                title = { Text("محركات البحث ٢") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        listOf(
                            "Steam Store" to "https://store.steampowered.com/search/?term=",
                            "GOG Games" to "https://www.gog.com/en/games?query=",
                            "itch.io Games" to "https://itch.io/search?q=",
                            "SearXNG" to "https://searx.be/search?q="
                        ).forEach { (name, url) ->
                            TextButton(
                                onClick = {
                                    presenter.selectGameSearchEngine(url)
                                    gameSearchDialog = false
                                }
                            ) { Text(name) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { gameSearchDialog = false }) { Text("إغلاق") }
                }
            )
        }
        if (videoSearchDialog) {
            AlertDialog(
                onDismissRequest = { videoSearchDialog = false },
                title = { Text("بحث فيديو") },
                text = {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        listOf(
                            "YouTube" to "https://www.youtube.com/results?search_query=",
                            "Google Videos" to "https://www.google.com/search?tbm=vid&q=",
                            "Bing Videos" to "https://www.bing.com/videos/search?q=",
                            "DuckDuckGo Videos" to "https://duckduckgo.com/?iax=videos&ia=videos&q=",
                            "Dailymotion" to "https://www.dailymotion.com/search/",
                            "Vimeo" to "https://vimeo.com/search?q=",
                            "Odysee" to "https://odysee.com/\$/search?q=",
                            "Rumble" to "https://rumble.com/search/video?q=",
                            "Bilibili" to "https://search.bilibili.com/all?keyword=",
                            "Twitch" to "https://www.twitch.tv/search?term="
                        ).forEach { (name, url) ->
                            TextButton(
                                onClick = {
                                    presenter.selectGameSearchEngine(url)
                                    videoSearchDialog = false
                                }
                            ) { Text(name) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { videoSearchDialog = false }) { Text("إغلاق") }
                }
            )
        }
        if (customSearchDialog) {
            AlertDialog(
                onDismissRequest = { customSearchDialog = false },
                title = { Text("محرك بحث مخصص") },
                text = {
                    OutlinedTextField(
                        value = customSearchUrl,
                        onValueChange = { customSearchUrl = it },
                        label = { Text("رابط البحث") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        presenter.selectSearchEngine(0, customSearchUrl)
                        customSearchDialog = false
                    }) { Text("حفظ") }
                },
                dismissButton = {
                    TextButton(onClick = { customSearchDialog = false }) { Text("إلغاء") }
                }
            )
        }
    }
}

@Composable
private fun DropdownText(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium
)
