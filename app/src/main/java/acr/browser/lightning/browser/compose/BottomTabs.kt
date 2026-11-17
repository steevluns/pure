package acr.browser.lightning.browser.compose

import acr.browser.lightning.BrowserUiEvent
import acr.browser.lightning.browser.BrowserContract
import acr.browser.lightning.downloadmanager.DownloadsActivity
import android.content.Intent
import acr.browser.lightning.R
import acr.browser.lightning.browser.BrowserComposeState
import acr.browser.lightning.browser.BrowserPresenter
import acr.browser.lightning.browser.compose.sheets.BookmarksBottomSheet
import acr.browser.lightning.browser.tab.TabModel
import acr.browser.lightning.compose.imeInsetsPadding
import acr.browser.lightning.preview.TopCropTransformation
import acr.browser.lightning.search.SuggestionsModel
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import kotlinx.coroutines.flow.StateFlow

@Composable
fun BottomTabs(
    useBlackStatusBarStateFlow: StateFlow<Boolean?>,
    frameLayout: FrameLayout,
    browserViewState: BrowserComposeState,
    presenter: BrowserPresenter,
    suggestionsModel: SuggestionsModel,
    snackbarHostState: SnackbarHostState,
    lazyListState: LazyListState,
) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(56.dp)
            )
        }
    ) { innerPadding ->
        BrowserStatusBar(
            browserComposeState = browserViewState,
            useBlackStatusBarStateFlow = useBlackStatusBarStateFlow,
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BookmarksBottomSheet(browserViewState, presenter)
            // Keep the complete navigation bar (search, refresh, tab count and menu) at the top.
            BottomTabNavigationBar(browserViewState, presenter, suggestionsModel)
            BrowserFindInPage(browserViewState, presenter)
            AndroidView(
                factory = { frameLayout },
                modifier = Modifier
                    .imeInsetsPadding(innerPadding = innerPadding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceDim)
                    .weight(1f, false),
            )
            TabsBottomSheet(browserViewState, lazyListState, presenter)
            BrowserDialogs(browserViewState, presenter)
        }
    }
}

@Composable
fun BottomTabNavigationBar(
    browserViewState: BrowserComposeState,
    presenter: BrowserPresenter,
    suggestionsModel: SuggestionsModel,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.height(56.dp)
    ) {
        Box(
            contentAlignment = Alignment.TopCenter
        ) {
            HorizontalDivider()
            BrowserProgressIndicator(browserViewState)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Force LTR ordering so the search field stays on the left
                // and the tab, downloads, and overflow controls stay on the right.
                BrowserSearchBar(browserViewState, presenter, suggestionsModel)
                TabCountButton(browserViewState) {
                    presenter.onEvent(BrowserUiEvent.TabCountClick)
                }
                // Keep refresh fixed beside the tabs button, independent of search-bar state.
                IconButton(
                    onClick = { presenter.onEvent(BrowserUiEvent.RefreshOrStopClick) }
                ) {
                    Icon(
                        modifier = Modifier.size(24.dp),
                        painter = when (browserViewState.isRefresh) {
                            true -> painterResource(R.drawable.ic_action_refresh)
                            false -> painterResource(R.drawable.ic_action_delete)
                        },
                        contentDescription = "refresh"
                    )
                }
                IconButton(
                    enabled = browserViewState.isBookmarkEnabled,
                    onClick = { presenter.onEvent(BrowserUiEvent.StarClick) }
                ) {
                    BookmarkIcon(browserViewState.isBookmarked)
                }
                IconButton(
                    onClick = {
                        context.startActivity(Intent(context, DownloadsActivity::class.java))
                    }
                ) {
                    Text(
                        text = "↓",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                BrowserOverflowMenu(presenter, browserViewState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsBottomSheet(
    browserViewState: BrowserComposeState,
    lazyListState: LazyListState,
    presenter: BrowserPresenter,
) {
    val gridState = rememberLazyGridState()
    if (browserViewState.scrollToTab != -1) {
        LaunchedEffect(browserViewState.scrollToTab) {
            gridState.scrollToItem(browserViewState.scrollToTab)
            presenter.onEvent(BrowserUiEvent.TabScroll)
        }
    }
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showBottomSheet by remember { mutableStateOf(browserViewState.openTabs) }
    if (showBottomSheet != browserViewState.openTabs) {
        if (showBottomSheet) {
            LaunchedEffect(null) {
                sheetState.hide()
                showBottomSheet = false
            }
        } else {
            showBottomSheet = true
            LaunchedEffect(null) {
                sheetState.show()
            }
        }
    }
    if (!showBottomSheet) return
    ModalBottomSheet(
        dragHandle = {},
        sheetState = sheetState,
        onDismissRequest = { presenter.onEvent(BrowserUiEvent.TabDrawerMoved(isOpen = false)) }
    ) {
        Row(
            modifier = Modifier
                .height(64.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(
                onClick = {
                    val selectedTabId = browserViewState.tabs
                        .firstOrNull { it.isSelected }?.id
                        ?: browserViewState.tabs.firstOrNull()?.id
                    if (selectedTabId != null) {
                        presenter.onEvent(
                            BrowserUiEvent.CloseBrowser(
                                selectedTabId,
                                BrowserContract.CloseTabEvent.CLOSE_ALL
                            )
                        )
                    }
                },
                enabled = browserViewState.tabs.isNotEmpty()
            ) {
                Text(
                    text = "إغلاق الكل",
                    color = MaterialTheme.colorScheme.error
                )
            }
            IconButton(
                enabled = browserViewState.isBackEnabled,
                onClick = { presenter.onEvent(BrowserUiEvent.BackClick) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_back),
                    contentDescription = ""
                )
            }
            IconButton(
                enabled = browserViewState.isForwardEnabled,
                onClick = { presenter.onEvent(BrowserUiEvent.ForwardClick) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_forward),
                    contentDescription = ""
                )
            }
            IconButton(onClick = { presenter.onEvent(BrowserUiEvent.HomeClick) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_home),
                    contentDescription = ""
                )
            }
            IconButton(onClick = { presenter.onEvent(BrowserUiEvent.ToolsClick) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_page_tools),
                    contentDescription = ""
                )
            }
            IconButton(
                enabled = browserViewState.isBookmarkEnabled,
                onClick = { presenter.onEvent(BrowserUiEvent.StarClick) }
            ) {
                BookmarkIcon(browserViewState.isBookmarked)
            }
            IconButton(onClick = { presenter.onEvent(BrowserUiEvent.NewTabClick) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_plus),
                    contentDescription = "New tab"
                )
            }
            IconButton(
                onClick = {
                    context.startActivity(Intent(context, DownloadsActivity::class.java))
                }
            ) {
                Text(
                    text = "↓",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = gridState,
        ) {
            itemsIndexed(
                items = browserViewState.tabs,
                key = { _, item -> item.id },
                contentType = { _, item -> item.isSelected },
            ) { index, tab ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.78f)
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null
                        )
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large
                        )
                        .combinedClickable(
                            onClick = { presenter.onEvent(BrowserUiEvent.TabClick(index)) },
                            onLongClick = { presenter.onEvent(BrowserUiEvent.TabLongClick(index)) }
                        )
                        .optionalBorder(tab.isSelected)
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (tab.icon) {
                            TabModel.Favicon.Frozen -> Icon(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(horizontal = 4.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                                painter = painterResource(R.drawable.ic_frozen),
                                contentDescription = null
                            )

                            is TabModel.Favicon.Icon -> Image(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(horizontal = 4.dp),
                                bitmap = tab.icon.bitmap,
                                contentDescription = null
                            )

                            TabModel.Favicon.None -> Icon(
                                modifier = Modifier
                                    .size(24.dp)
                                    .padding(horizontal = 4.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                                painter = painterResource(R.drawable.ic_webpage),
                                contentDescription = null
                            )
                        }
                        Text(
                            modifier = Modifier
                                .weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (tab.isPopup) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                            text = tab.title
                        )
                        IconButton(
                            modifier = Modifier.size(30.dp),
                            onClick = { presenter.onEvent(BrowserUiEvent.TabClose(index)) }
                        ) {
                            Icon(
                                modifier = Modifier.size(20.dp),
                                painter = painterResource(R.drawable.ic_action_delete),
                                contentDescription = stringResource(R.string.close_tab)
                            )
                        }
                    }
                    val transformations = with(LocalDensity.current) {
                        remember {
                            listOf(
                                TopCropTransformation,
                                RoundedCornersTransformation(8.dp.toPx())
                            )
                        }
                    }
                    AsyncImage(
                        model = when (tab.preview) {
                            is TabModel.Preview.Image -> ImageRequest.Builder(LocalContext.current)
                                .data(tab.preview.path)
                                .memoryCacheKey("${tab.preview.time}-${tab.preview.path}")
                                .transformations(transformations)
                                .build()

                            TabModel.Preview.None -> null
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun Modifier.optionalBorder(apply: Boolean): Modifier {
    if (apply) {
        return border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.medium
        )
    }
    return this
}
