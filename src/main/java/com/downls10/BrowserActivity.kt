package com.downls10

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Parcel
import android.util.Patterns
import android.util.AttributeSet
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.BaseAdapter
import android.text.Editable
import android.text.TextWatcher
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale
import org.json.JSONObject
import kotlin.jvm.JvmOverloads
import kotlin.math.abs

private data class Tab(
    val id: Long,
    val webView: WebView,
    var isHome: Boolean = true,
    var title: String = "صفحة جديدة",
    var desktopMode: Boolean = false,
    // Translation stays enabled for this tab while the user browses.
    var translationEnabled: Boolean = false,
    var translationInProgress: Boolean = false,
    var lastTranslatedUrl: String = "",
    val backStack: MutableList<String> = mutableListOf(),
    val forwardStack: MutableList<String> = mutableListOf(),
    // --- حفظ واستعادة موضع التمرير عند الرجوع/التقدم ---
    /** آخر موضع تمرير (بالبكسل) لكل رابط زاره هذا التبويب */
    val scrollPositions: MutableMap<String, Int> = mutableMapOf(),
    /** true أثناء تحميل صفحة جديدة (لا نسجّل التمرير حينها لأن الصفحة تبدأ من الصفر) */
    var pageLoading: Boolean = false,
    /** الموضع المطلوب استعادته الآن، أو -1 إذا لا توجد استعادة جارية */
    var pendingRestoreY: Int = -1,
    /** رقم يزيد عند بدء/إلغاء كل استعادة لإيقاف المحاولات القديمة */
    var restoreGeneration: Int = 0,
    /** يمنع تعديل رابط نتائج البحث أثناء الرجوع، حتى لو كان موضع التمرير صفرًا */
    var historyNavigationPending: Boolean = false,
    /** آخر موضع مؤكد لنتائج البحث، يبقى محفوظًا أثناء فتح المواقع والرجوع منها */
    var lastSearchResultsUrl: String? = null,
    var lastSearchResultsY: Int = 0
)

class BrowserActivity : Activity() {

    /** تخصيص قائمة الضغط المطوّل على النص داخل صفحات الويب. */
    override fun onActionModeStarted(mode: android.view.ActionMode?) {
        super.onActionModeStarted(mode)
        val menu = mode?.menu ?: return

        for (index in menu.size() - 1 downTo 0) {
            menu.removeItem(menu.getItem(index).itemId)
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        val pasteItem = menu.add(0, ACTION_PASTE_PAGE_TEXT, 99, "لصق")
        pasteItem.isEnabled = clip != null && clip.itemCount > 0
        pasteItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        pasteItem.setOnMenuItemClickListener {
            val pastedText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val webView = tabs.getOrNull(currentTabIndex)?.webView
            if (pastedText.isNotEmpty() && webView != null) {
                val quotedText = org.json.JSONObject.quote(pastedText)
                val script = """
                    (function(text) {
                        var el = document.activeElement;
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                            var start = typeof el.selectionStart === 'number' ? el.selectionStart : el.value.length;
                            var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
                            if (typeof el.setRangeText === 'function') {
                                el.setRangeText(text, start, end, 'end');
                            } else {
                                el.value = el.value.substring(0, start) + text + el.value.substring(end);
                            }
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                            el.dispatchEvent(new Event('change', {bubbles:true}));
                            return true;
                        }
                        if (el && el.isContentEditable) {
                            document.execCommand('insertText', false, text);
                            return true;
                        }
                        return false;
                    })($quotedText);
                """.trimIndent()
                webView.evaluateJavascript(script) { result ->
                    if (result != "true") {
                        Toast.makeText(this, "اضغط داخل مربع الكتابة أولاً للصق النص", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }

        val copyItem = menu.add(0, ACTION_COPY_SELECTED_TEXT, 100, "نسخ")
        copyItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        copyItem.setOnMenuItemClickListener {
            val webView = tabs.getOrNull(currentTabIndex)?.webView
            if (webView != null) {
                webView.evaluateJavascript(
                    "(function(){var s=window.getSelection();return s?s.toString():'';})()"
                ) { value ->
                    val selected = runCatching {
                        org.json.JSONTokener(value ?: "\"\"").nextValue() as? String
                    }.getOrNull().orEmpty()
                    if (selected.isNotEmpty()) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("النص المحدد", selected))
                    }
                }
            }
            // إغلاق قائمة التحديد بعد تنفيذ النسخ، من دون إظهار رسالة تأكيد.
            mode?.finish()
            true
        }

        val selectAllItem = menu.add(0, ACTION_SELECT_ALL_PAGE_TEXT, 101, "تحديد الكل")
        selectAllItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        selectAllItem.setOnMenuItemClickListener {
            val webView = tabs.getOrNull(currentTabIndex)?.webView
            webView?.evaluateJavascript(
                "(function(){var r=document.createRange();r.selectNodeContents(document.body);var s=window.getSelection();s.removeAllRanges();s.addRange(r);})()",
                null
            )
            true
        }
    }


    companion object {
        private const val ACTION_COPY_SELECTED_TEXT = 0x534501
        private const val ACTION_SELECT_ALL_PAGE_TEXT = 0x534502
        private const val ACTION_PASTE_PAGE_TEXT = 0x534503
        const val EXTRA_OPEN_URL = "extra_open_url"
        private const val SEARCH_PROVIDER_KEY = "searchProvider"
        private const val SEARCH_SEARXNG = "searxng"
        private const val SEARCH_GOOGLE = "google"
        private const val SEARCH_VIDEO = "video"
        private const val SEARXNG_SEARCH_BASE = "https://searx.ononoki.org/search?q="
        private const val SEARXNG_ENGINE_PREFIX = "!ddg !br !qw !yh "
        private val VIDEO_SEARCH_ENGINES = listOf(
            "Google Videos", "Bing Videos", "Yahoo Videos", "DuckDuckGo Videos",
            "Brave Search Videos", "Yandex Videos", "SearXNG Videos", "Mojeek",
            "YouTube", "Dailymotion", "Vimeo", "Internet Archive", "PeerTube",
            "Rumble", "Twitch", "Facebook Watch", "TikTok", "Instagram",
            "Reddit Videos", "Odysee", "Bilibili", "Metacafe"
        )
        private const val VIDEO_SEARCH_ENGINES_KEY = "videoSearchEngines"
        // بحث الفيديو يجمع النتائج المتاحة من محركات الفيديو المفعّلة في SearXNG.
    }

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTabs: Button
    private var suggestionsPopup: PopupWindow? = null
    private var suggestionGeneration = 0
    private var suggestionItems: List<String> = emptyList()

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0

    private var hideMedia = false
    private var hideImages = false
    private var hideVideos = false
    private var nightMode = false
    private var mobileUA: String = ""
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

    private var pendingStorageAction: (() -> Unit)? = null
    private val STORAGE_PERM_CODE = 4102
    private val ANDROID_PERM_CODE = 4103
    private val FILE_CHOOSER_CODE = 4104

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBookmarkExportContent: String? = null
    private var lastConfirmedSafetyUrl: String? = null
    // قرار واحد لمضيف الصفحة الرئيسية خلال جلسة المتصفح: true = سماح، false = رفض.
    // تشترك أخطاء SSL لموارد الصفحة في القرار نفسه حتى لو اختلف مضيف المورد.
    private val sslSiteDecisions = mutableMapOf<String, Boolean>()
    private val pendingSslHandlers = mutableMapOf<String, MutableList<android.webkit.SslErrorHandler>>()
    private val bgExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val PREFS = "browser_settings"
    private fun settingsPrefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun selectedSearchProvider(): String =
        settingsPrefs().getString(SEARCH_PROVIDER_KEY, SEARCH_GOOGLE) ?: SEARCH_GOOGLE

    private fun saveSearchProvider(provider: String) {
        settingsPrefs().edit().putString(SEARCH_PROVIDER_KEY, provider).apply()
    }

    private fun savedFontZoom(): Int =
        settingsPrefs().getInt("fontZoom", 100).coerceIn(50, 200)

    private fun saveFontZoom(value: Int) {
        settingsPrefs().edit().putInt("fontZoom", value.coerceIn(50, 200)).apply()
    }

    private fun cameraAllowed() = settingsPrefs().getBoolean("camera", false)
    private fun micAllowed() = settingsPrefs().getBoolean("mic", false)
    private fun locationAllowed() = settingsPrefs().getBoolean("location", false)

    private fun permissionStateText(label: String, enabled: Boolean): String =
        "$label — ${if (enabled) "مفعّل" else "معطّل"}"

    private var pendingAndroidPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingAndroidPermissionAnyOf: Boolean = false

    /** يطلب أذونات أندرويد المطلوبة ويتحقق من النتيجة قبل متابعة طلب الموقع. */
    private fun ensureAndroidPermissions(
        permissions: Array<String>,
        anyOf: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        val required = permissions.distinct()
        if (required.isEmpty()) {
            callback(true)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            callback(true)
            return
        }
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        if (pendingAndroidPermissionCallback != null) {
            callback(false)
            return
        }
        pendingAndroidPermissionCallback = callback
        pendingAndroidPermissionAnyOf = anyOf
        requestPermissions(missing.toTypedArray(), ANDROID_PERM_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ANDROID_PERM_CODE) return
        val callback = pendingAndroidPermissionCallback ?: return
        val anyOf = pendingAndroidPermissionAnyOf
        pendingAndroidPermissionCallback = null
        pendingAndroidPermissionAnyOf = false
        val grantedCount = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
        callback(if (anyOf) grantedCount > 0 else grantResults.isNotEmpty() && grantedCount == grantResults.size)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        title = ""

        webViewContainer = findViewById(R.id.webViewContainer)
        editUrl = findViewById(R.id.editUrl)
        // لا تفتح لوحة المفاتيح عند تشغيل المتصفح. لا نطلبها إلا بعد لمسة صريحة على حقل العنوان.
        editUrl.clearFocus()
        findViewById<View>(android.R.id.content).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editUrl.setOnClickListener {
            editUrl.requestFocus()
            editUrl.post {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editUrl, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // أظهر خيار "لصق" عند الضغط المطوّل على شريط العنوان،
        // مع استبدال النص المحدد أو الإدراج عند موضع المؤشر.
        editUrl.isLongClickable = true
        editUrl.setOnLongClickListener { view ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val pasteMenu = android.widget.PopupMenu(this, view)
            val pasteItem = pasteMenu.menu.add(0, 1, 0, "لصق")
            pasteItem.isEnabled = clip != null && clip.itemCount > 0
            pasteMenu.menu.add(0, 2, 1, "نسخ")
            pasteMenu.menu.add(0, 3, 2, "تحديد الكل")

            pasteMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val pastedText = clip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                        if (pastedText.isNotEmpty()) {
                            val start = editUrl.selectionStart.coerceAtLeast(0)
                            val end = editUrl.selectionEnd.coerceAtLeast(0)
                            val insertAt = minOf(start, end)
                            editUrl.text.replace(insertAt, maxOf(start, end), pastedText)
                            editUrl.setSelection((insertAt + pastedText.length).coerceAtMost(editUrl.length()))
                        }
                    }
                    2 -> {
                        val start = editUrl.selectionStart
                        val end = editUrl.selectionEnd
                        val selectedText = if (start >= 0 && end > start) {
                            editUrl.text.substring(start, end)
                        } else {
                            editUrl.text.toString()
                        }
                        if (selectedText.isNotEmpty()) {
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("النص المنسوخ", selectedText)
                            )
                            Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> editUrl.selectAll()
                }
                true
            }
            pasteMenu.show()
            true
        }
        progressBar = findViewById(R.id.progressBar)
        val btnBookmarkStar = findViewById<Button>(R.id.btnBookmarkStar)
        val btnDownloads = findViewById<Button>(R.id.btnDownloads)
        btnTabs = findViewById(R.id.btnTabs)
        val btnBrowserMenu = findViewById<Button>(R.id.btnBrowserMenu)
        val btnReload = findViewById<Button>(R.id.btnReload)

        CookieManager.getInstance().setAcceptCookie(true)
        DownloadsRepository.ensureLoaded(this)
        AdBlocker.cleanupRemovedLists(this)
        hideMedia = settingsPrefs().getBoolean("hideMedia", false)
        hideImages = settingsPrefs().getBoolean("hideImages", hideMedia)
        hideVideos = settingsPrefs().getBoolean("hideVideos", false)
        nightMode = settingsPrefs().getBoolean("nightMode", false)

        editUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                loadFromAddressBar()
                hideKeyboard()
                true
            } else false
        }

        btnBookmarkStar.setOnClickListener { addCurrentPageToBookmarks() }
        btnTabs.setOnClickListener { showTabsDialog() }
        btnDownloads.setOnClickListener { openDownLS10() }
        btnBrowserMenu.setOnClickListener { showBrowserMenu() }
        btnReload.setOnClickListener {
            currentTab().webView.reload()
        }
        setupGoogleSuggestions()

        if (savedInstanceState != null && savedInstanceState.getInt("tabCount", -1) >= 0) {
            restoreTabsFromInstanceState(savedInstanceState)
        } else {
            restoreTabsOrCreateHome()
        }
        handleOpenUrlIntent(intent)
    }

    /**
     * يحوّل حالة WebView إلى نص قابل للحفظ بشكل دائم في SharedPreferences.
     * saveState() يحفظ سجل الرجوع/التقدم داخل WebView، لذلك لا يكفي حفظ URL الحالي فقط.
     */
    private fun serializeWebViewState(webView: WebView): String? {
        return try {
            val state = Bundle()
            val history = webView.saveState(state) ?: return null
            if (history.size <= 0) return null

            val parcel = Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** يعيد حالة WebView المحفوظة، بما فيها سجل الرجوع/التقدم. */
    private fun restoreSerializedWebViewState(webView: WebView, encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val state = Bundle.CREATOR.createFromParcel(parcel)
                state.classLoader = WebView::class.java.classLoader
                webView.restoreState(state) != null
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tabCount", tabs.size)
        outState.putInt("currentTabIndex", currentTabIndex)
        tabs.forEachIndexed { i, tab ->
            outState.putBoolean("tab_${i}_isHome", tab.isHome)
            outState.putBoolean("tab_${i}_desktop", tab.desktopMode)
            outState.putStringArrayList("tab_${i}_back", ArrayList(tab.backStack))
            outState.putStringArrayList("tab_${i}_forward", ArrayList(tab.forwardStack))
            if (!tab.isHome) {
                val webState = Bundle()
                tab.webView.saveState(webState)
                outState.putBundle("tab_${i}_webstate", webState)
            }
        }
        // مهم: onSaveInstanceState وحده لا يكفي لإعادة تشغيل التطبيق لاحقًا.
        persistTabs()
    }

    /** يستعيد التبويبات من حالة أندرويد المحفوظة (تستخدم ذاكرة WebView المؤقتة بدل تحميل الصفحة من الإنترنت من جديد) */
    private fun restoreTabsFromInstanceState(state: Bundle) {
        val count = state.getInt("tabCount", 0)
        for (i in 0 until count) {
            val isHome = state.getBoolean("tab_${i}_isHome", true)
            val desktop = state.getBoolean("tab_${i}_desktop", false)
            val back = state.getStringArrayList("tab_${i}_back") ?: arrayListOf()
            val forward = state.getStringArrayList("tab_${i}_forward") ?: arrayListOf()
            val webState = state.getBundle("tab_${i}_webstate")

            val webView = WebView(this)
            setupWebView(webView)
            val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
            webView.settings.userAgentString = if (desktop) desktopUA else cleanedUA
            if (mobileUA.isEmpty()) mobileUA = cleanedUA

            val tab = Tab(id = System.currentTimeMillis() + i, webView = webView, isHome = isHome, desktopMode = desktop)
            tab.backStack.addAll(back)
            tab.forwardStack.addAll(forward)
            tabs.add(tab)

            if (!isHome && webState != null) {
                webView.restoreState(webState)
            }
        }
        val idx = state.getInt("currentTabIndex", 0).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            switchToTab(idx)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenUrlIntent(intent)
    }

    private fun handleOpenUrlIntent(intent: Intent?) {
        if (intent == null) return

        // الروابط القادمة من التطبيقات الأخرى (ومنها ChatGPT) تصل عادةً كـ ACTION_VIEW
        // مع URI في data. نفتحها دائمًا في تبويب جديد بدل استبدال التبويب الحالي.
        val extraUrl = intent.getStringExtra(EXTRA_OPEN_URL)
        val dataUrl = intent.data?.toString()
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)

        val url = listOf(extraUrl, dataUrl, sharedText)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .mapNotNull { value ->
                when {
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    else -> null
                }
            }
            .firstOrNull()

        if (!url.isNullOrBlank()) {
            // كل رابط خارجي يفتح تبويبًا جديدًا، سواء وصل من ChatGPT أو WhatsApp أو Telegram
            // أو أي تطبيق آخر يدعم روابط الويب.
            createBrowsingTab(url)
        }
    }

    // ---------------- استعادة/حفظ التبويبات ----------------

    private fun restoreTabsOrCreateHome() {
        val (saved, currentIndex) = TabPersistence.load(this)
        if (saved.isEmpty()) {
            createHomeTab()
            return
        }
        saved.forEach { savedTab ->
            if (savedTab.isHome) {
                createTabInternal(isHome = true, url = null)
            } else {
                createTabInternal(
                    isHome = false,
                    url = savedTab.url,
                    restoredBack = savedTab.backStack,
                    restoredForward = savedTab.forwardStack,
                    restoredWebState = savedTab.webState
                )
            }
        }
        val idx = currentIndex.coerceIn(0, tabs.size - 1)
        switchToTab(idx)
    }

    private fun persistTabs() {
        val saved = tabs.map { tab ->
            SavedTab(
                tab.isHome,
                if (tab.isHome) "" else (tab.webView.url ?: ""),
                tab.title,
                tab.backStack.toList(),
                tab.forwardStack.toList(),
                if (tab.isHome) null else serializeWebViewState(tab.webView)
            )
        }
        TabPersistence.save(this, saved, currentTabIndex)
    }

    override fun onPause() {
        super.onPause()
        persistTabs()
        // Keep login/session cookies, but persist pending cookie changes when the app backgrounds.
        // Third-party cookies remain disabled per WebView below; first-party cookies stay enabled.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // Cookie persistence failure must not crash the browser.
        }
    }

    // ---------------- إدارة التبويبات ----------------

    private fun createHomeTab() {
        createTabInternal(isHome = true, url = null)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createBrowsingTab(url: String) {
        createTabInternal(isHome = false, url = url)
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    private fun createTabInternal(
        isHome: Boolean,
        url: String?,
        restoredBack: List<String> = emptyList(),
        restoredForward: List<String> = emptyList(),
        restoredWebState: String? = null
    ) {
        val webView = WebView(this)
        setupWebView(webView)
        val cleanedUA = buildChromeLikeUserAgent(webView.settings.userAgentString)
        webView.settings.userAgentString = cleanedUA
        if (mobileUA.isEmpty()) mobileUA = cleanedUA

        val tab = Tab(id = System.currentTimeMillis(), webView = webView, isHome = isHome)
        tab.backStack.addAll(restoredBack)
        tab.forwardStack.addAll(restoredForward)
        if (!isHome && !url.isNullOrBlank()) {
            tab.title = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        }
        tabs.add(tab)

        if (!isHome) {
            // لا نعيد تحميل URL إذا كان لدينا WebView state محفوظ؛
            // restoreState يعيد سجل الرجوع/التقدم نفسه.
            val restored = restoreSerializedWebViewState(webView, restoredWebState)
            if (!restored && !url.isNullOrBlank()) {
                webView.loadUrl(url)
            }
        }
    }

    /** الرجوع والتقدم يعتمدان على سجل WebView الحقيقي مع استعادة موضع التمرير. */
    private fun goBackInTab(tab: Tab) {
        navigateHistory(tab, -1)
    }

    private fun goForwardInTab(tab: Tab) {
        navigateHistory(tab, 1)
    }

    // ---------------- حفظ واستعادة موضع التمرير (Scroll Position Restoration) ----------------

    private val blobBridge by lazy { BlobDownloadBridge(this) }

    private val SCROLL_RESTORE_INTERVAL_MS = 150L
    private val SCROLL_RESTORE_MAX_TRIES = 60          // ≈ 9 ثوانٍ كحد أقصى
    private val MAX_SAVED_SCROLL_ENTRIES = 100

    private fun rememberScroll(tab: Tab, url: String, y: Int) {
        if (url.isBlank() || url.startsWith("about:")) return
        tab.scrollPositions.remove(url)          // ليصبح الأحدث في آخر القائمة
        tab.scrollPositions[url] = y
        while (tab.scrollPositions.size > MAX_SAVED_SCROLL_ENTRIES) {
            val oldest = tab.scrollPositions.keys.firstOrNull() ?: break
            tab.scrollPositions.remove(oldest)
        }
    }

    /**
     * ينتقل في سجل التبويب خطوة للخلف (steps = -1) أو للأمام (steps = 1)،
     * ويعيد المستخدم إلى نفس مكان التمرير الذي كان فيه في تلك الصفحة.
     * يرجع false إذا لا توجد صفحة سابقة/تالية.
     */
    private fun navigateHistory(tab: Tab, steps: Int): Boolean {
        val wv = tab.webView
        val list = wv.copyBackForwardList()
        val targetIndex = list.currentIndex + steps
        if (targetIndex < 0 || targetIndex >= list.size) return false
        val targetUrl = list.getItemAtIndex(targetIndex)?.url

        // احفظ موضع الصفحة التي نغادرها (احتياطًا، المستمع يحفظه أصلًا أثناء التمرير)
        val leavingUrl = wv.url
        if (leavingUrl != null && tab.pendingRestoreY < 0) {
            // احفظ الموضع الحالي حتى لو كانت الصفحة لا تزال تُحمّل محتوى ديناميكيًا.
            rememberScroll(tab, leavingUrl, wv.scrollY)
        }

        cancelScrollRestore(tab)
        // ابحث أولًا عن الرابط المطابق تمامًا، ثم استخدم عنوانًا مكافئًا إذا غيّر
        // محرك البحث معاملات مثل num أو tracking أثناء فتح النتيجة.
        val savedY = targetUrl?.let { target ->
            tab.scrollPositions[target] ?: run {
                val targetUri = runCatching { Uri.parse(target) }.getOrNull()
                val targetHost = targetUri?.host?.lowercase(Locale.US)
                val targetPath = targetUri?.path.orEmpty().trimEnd('/')
                val targetQuery = targetUri?.getQueryParameter("q")
                // تطابق مرن لعنوان البحث إذا أضاف Google معاملات num/start أو معاملات تتبّع.
                tab.scrollPositions.entries.lastOrNull { (savedUrl, _) ->
                    val savedUri = runCatching { Uri.parse(savedUrl) }.getOrNull()
                    val savedHost = savedUri?.host?.lowercase(Locale.US)
                    val savedPath = savedUri?.path.orEmpty().trimEnd('/')
                    val savedQuery = savedUri?.getQueryParameter("q")
                    val isSearchHost = savedHost?.let {
                        it == "google.com" || it.startsWith("google.") || it.endsWith(".google.com")
                    } == true
                    val sameSearch = targetQuery != null && savedQuery == targetQuery
                    val samePage = savedHost == targetHost && savedPath == targetPath
                    (samePage && (sameSearch || targetQuery == null)) || (isSearchHost && sameSearch)
                }?.value
            }
        } ?: 0

        // عند الرجوع إلى نتائج البحث، استخدم آخر موضع مرئي محفوظ حتى لو تغيّر
        // رابط Google أو استبدل WebView عنوانه أثناء فتح النتيجة.
        val targetSearchQuery = targetUrl?.let { runCatching { Uri.parse(it).getQueryParameter("q") }.getOrNull() }
        val lastSearchQuery = tab.lastSearchResultsUrl?.let { runCatching { Uri.parse(it).getQueryParameter("q") }.getOrNull() }
        val restoredY = if (targetUrl != null && isSearchResultsPage(targetUrl) &&
            targetSearchQuery != null && targetSearchQuery == lastSearchQuery) {
            tab.lastSearchResultsY
        } else savedY

        // لا نسمح لإعادة ضبط حجم نتائج Google بتغيير عنوان الصفحة أثناء الرجوع.
        tab.historyNavigationPending = true
        wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        wv.goBackOrForward(steps)

        // استعد الموضع حتى لو كان صفرًا، حتى تُغلق حالة الرجوع المعلّقة بصورة صحيحة.
        startScrollRestore(tab, targetIndex, restoredY)
        return true
    }

    private fun cancelScrollRestore(tab: Tab) {
        tab.restoreGeneration++
        tab.pendingRestoreY = -1
    }

    private fun finishScrollRestore(tab: Tab) {
        tab.pendingRestoreY = -1
        tab.historyNavigationPending = false
        try { tab.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
    }

    /**
     * يحاول كل 150ms تمرير الصفحة إلى الموضع المحفوظ حتى ينجح.
     * إذا كانت الصفحة أقصر من الموضع المطلوب (المحتوى لم يكتمل أو يُحمَّل بالتمرير اللانهائي مثل جوجل)،
     * يبقى التمرير عند آخر الصفحة المتاحة فيُحمَّل المزيد من المحتوى، ثم يكمل المحاولة.
     * تتوقف المحاولات إذا لمس المستخدم الشاشة.
     */
    private fun startScrollRestore(tab: Tab, targetIndex: Int, targetY: Int) {
        val generation = ++tab.restoreGeneration
        tab.pendingRestoreY = targetY
        var tries = 0
        val step = object : Runnable {
            override fun run() {
                if (tab.restoreGeneration != generation) return       // أُلغيت أو استُبدلت باستعادة أحدث
                if (!tabs.contains(tab)) return                        // التبويب أُغلق
                val wv = tab.webView
                tries++
                // لا نحرّك الصفحة القديمة: ننتظر حتى يصبح موضع السجل هو الصفحة الهدف
                val onTarget = try { wv.copyBackForwardList().currentIndex == targetIndex } catch (e: Exception) { false }
                if (onTarget) {
                    wv.scrollTo(wv.scrollX, targetY)
                    if (abs(wv.scrollY - targetY) <= 2 && !tab.pageLoading) {
                        finishScrollRestore(tab)
                        return
                    }
                }
                if (tries >= SCROLL_RESTORE_MAX_TRIES) {
                    finishScrollRestore(tab)
                    return
                }
                mainHandler.postDelayed(this, SCROLL_RESTORE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(step, SCROLL_RESTORE_INTERVAL_MS)
    }

    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        webViewContainer.removeAllViews()
        currentTabIndex = index
        val tab = tabs[index]

        if (tab.isHome) {
            editUrl.setText("")
            progressBar.visibility = View.GONE
            webViewContainer.addView(buildSpeedDialView())
        } else {
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            webViewContainer.addView(
                tab.webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            editUrl.setText(tab.webView.url ?: "")
        }
        updateTabCountUI()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs[index]
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createHomeTab()
        } else {
            val newIndex = if (currentTabIndex >= tabs.size) tabs.size - 1 else currentTabIndex
            switchToTab(newIndex)
        }
        persistTabs()
    }

    private fun closeAllTabs() {
        webViewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        createHomeTab()
    }

    private fun updateTabCountUI() {
        btnTabs.text = tabs.size.toString()
    }

    private fun currentTab(): Tab = tabs[currentTabIndex]
    private fun currentWebView(): WebView = tabs[currentTabIndex].webView

    /** يزيل علامة "wv" (WebView) من هوية المتصفح الافتراضية لتقليل رفض بعض المواقع/إظهار تحقق زائد */
    private fun buildChromeLikeUserAgent(defaultUA: String): String {
        return defaultUA
            .replace("; wv)", ")")
            .replace(" wv;", "")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        editUrl.clearFocus()
    }

    // ---------------- صفحة البداية (Speed Dial) ----------------

    private fun buildSpeedDialView(): View {
        val root = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }

        val columns = 4
        val horizontalPadding = 16
        val screenWidth = resources.displayMetrics.widthPixels
        val cellWidth = (screenWidth - horizontalPadding * 2) / columns
        val iconSize = (cellWidth * 0.68f).toInt()

        val grid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(horizontalPadding, 48, horizontalPadding, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val items = SpeedDialStorage.getItems(this)
        items.forEach { item ->
            grid.addView(buildSpeedDialTile(title = item.title, item = item, cellWidth = cellWidth, iconSize = iconSize) {
                createBrowsingTab(item.url)
            })
        }

        // زر + إضافة
        val addTile = buildAddTile(cellWidth = cellWidth, iconSize = iconSize) { showAddSpeedDialDialog() }
        grid.addView(addTile)

        root.addView(grid)
        return root
    }

    private fun buildAddTile(cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
        }
        val plusBox = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = (iconSize / 22f).coerceAtLeast(20f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setBackgroundColor(Color.parseColor("#222222"))
        }
        val label = TextView(this).apply {
            text = "إضافة"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        container.addView(plusBox)
        container.addView(label)
        return container
    }

    private fun buildSpeedDialTile(title: String, item: SpeedDialItem, cellWidth: Int, iconSize: Int, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = GridLayout.LayoutParams().apply {
                width = cellWidth
                setMargins(4, 12, 4, 12)
            }
            setOnClickListener { onClick() }
            setOnLongClickListener {
                showSpeedDialItemMenu(item)
                true
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            tag = item.url
        }
        loadFaviconInto(icon, item.url)

        val label = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#808080"))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(icon)
        container.addView(label)
        return container
    }

    private fun faviconCacheFile(host: String): File {
        val safe = Base64.encodeToString(
            host.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
        val dir = File(filesDir, "speeddial_favicons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, safe + ".png")
    }

    private fun loadFaviconInto(imageView: ImageView, pageUrl: String) {
        val host = try { Uri.parse(pageUrl).host?.lowercase(Locale.US) } catch (e: Exception) { null } ?: return
        val cacheFile = faviconCacheFile(host)

        // استخدم الصورة المحفوظة أولاً حتى لا تتحول مربعات المواقع إلى أسود عند كل تشغيل.
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            if (cacheFile.exists()) {
                bitmap = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
            }

            if (bitmap == null) {
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                    connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    bitmap = BitmapFactory.decodeStream(connection.inputStream)

                    bitmap?.let { downloaded ->
                        runCatching {
                            cacheFile.outputStream().use { out ->
                                downloaded.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                        }
                    }
                } catch (e: Exception) {
                    bitmap = null
                } finally {
                    connection?.disconnect()
                }
            }

            val result = bitmap
            mainHandler.post {
                if (result != null && imageView.tag == pageUrl) {
                    imageView.setImageBitmap(result)
                }
            }
        }
    }
    private fun showAddSpeedDialDialog() {
        val input = EditText(this).apply {
            hint = "الصق الرابط هنا"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة موقع")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                val host = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                SpeedDialStorage.addItem(this, host, url)
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showSpeedDialItemMenu(item: SpeedDialItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("إعادة تسمية", "حذف")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(item)
                    1 -> {
                        SpeedDialStorage.removeItem(this, item.id)
                        switchToTab(currentTabIndex)
                    }
                }
            }
            .show()
    }

    private fun showRenameDialog(item: SpeedDialItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                SpeedDialStorage.renameItem(this, item.id, input.text.toString().trim())
                switchToTab(currentTabIndex)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------------- إعداد WebView ----------------

    private fun setupWebView(webView: WebView) {
        // تتبّع آخر نقطة لمس لاكتشاف الصور المصغّرة وملصقات الفيديو والصور
        // المعروضة بخلفية CSS، حتى لو لم يتعرف عليها HitTestResult كصورة.
        var lastTouchX = 0f
        var lastTouchY = 0f

        // النقرة الواحدة تظل من اختصاص WebView وتفتح الرابط بشكل طبيعي.
        // عند الضغط المطوّل على رابط نحدد نصه فقط، ولا نسمح بسحبه أو تشغيله.
        webView.setOnLongClickListener {
            val hitTest = webView.hitTestResult
            val imageType =
                hitTest.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            val imageUrl = hitTest.extra

            if (imageType && !imageUrl.isNullOrBlank() &&
                (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                showImageContextMenu(imageUrl, hitTest.type)
                true
            } else if (hitTest.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                // نستخدم إحداثيات بداية اللمس (ACTION_DOWN)، لا إحداثيات حركة الإصبع،
                // حتى لا تقفز نقطة التحديد إلى مكان آخر أثناء الضغط المطوّل.
                val density = resources.displayMetrics.density
                val cssScale = (density * webView.scale).coerceAtLeast(0.1f)
                val x = lastTouchX / cssScale
                val y = lastTouchY / cssScale
                val script = """
                    (function(x, y) {
                        try {
                            var el = document.elementFromPoint(x, y);
                            var link = el && el.closest ? el.closest('a') : null;
                            var selection = window.getSelection();
                            if (!selection) return false;

                            var range = document.createRange();
                            if (link && (link.innerText || link.textContent || '').trim()) {
                                // حدّد عنوان النتيجة الظاهر بالكامل، وليس الرابط أو الصفحة.
                                range.selectNodeContents(link);
                            } else if (document.caretRangeFromPoint) {
                                range = document.caretRangeFromPoint(x, y);
                                if (!range) return false;
                                var node = range.startContainer;
                                if (node && node.nodeType === Node.TEXT_NODE) {
                                    var text = node.textContent || '';
                                    var start = range.startOffset;
                                    var end = range.endOffset;
                                    while (start > 0 && !/\\s/.test(text.charAt(start - 1))) start--;
                                    while (end < text.length && !/\\s/.test(text.charAt(end))) end++;
                                    range.setStart(node, start);
                                    range.setEnd(node, end);
                                }
                            } else {
                                return false;
                            }

                            selection.removeAllRanges();
                            selection.addRange(range);
                            return true;
                        } catch (e) {
                            return false;
                        }
                    })($x, $y);
                """.trimIndent()
                webView.evaluateJavascript(script, null)
                true
            } else {
                // اترك WebView يحدد النصوص العادية بالطريقة الأصلية.
                false
            }
        }

        webView.isLongClickable = true

        // استخدم قائمة التحديد الأصلية في WebView؛ لا توجد دالة callback عامة في WebView.
        // امنع سحب النص المحدد وإفلاته داخل الصفحة أو خارجها.
        // هذا لا يعطّل الضغط المطوّل ولا تحديد النص ولا أمر النسخ.
        webView.setOnDragListener { _, _ -> true }

        if (nightMode) webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val settings = webView.settings
        settings.textZoom = savedFontZoom()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Allow legacy HTTP pages and HTTP media/resources embedded in HTTPS pages.
        // Cleartext traffic is enabled in the manifest for older Android versions too.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // النوافذ الجديدة تصل إلى onCreateWindow (مع نافذة التأكيد) فقط عند ضغط المستخدم؛
        // ما تفتحه الصفحة من نفسها بدون ضغط يبقى محجوبًا.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Require an explicit user gesture before a site can autoplay media.
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Never allow file-origin pages to reach other local files or network origins.
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
        
        // === الوضع الليلي الذكي (Force Dark) ===
        // FORCE_DARK_AUTO: يحوّل المواقع الفاتحة للداكن، ويحافظ على المواقع الداكنة أصلاً
        // يستثني الصور والفيديو والأيقونات تلقائياً في الإصدارات الحديثة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_AUTO)
        }
        
        // حقن CSS إضافي لحماية الألوان في الصور والأيقونات والخطوط الملونة
        // هذا يعمل مع Force Dark لضمان عدم انعكاس الألوان في العناصر التي يجب أن تبقى كما هي
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val darkModeCss = """
                @media (prefers-color-scheme: dark) {
                    img, video, canvas, picture, svg, [style*="color:"] {
                        filter: none !important;
                        -webkit-filter: none !important;
                    }
                    * {
                        text-shadow: none !important;
                    }
                }
            """.trimIndent()
            webView.evaluateJavascript("""
                (function() {
                    var style = document.createElement('style');
                    style.textContent = `$darkModeCss`;
                    document.head.appendChild(style);
                })();
            """.trimIndent(), null)
        }
        
        // OAuth providers may rely on cross-site cookies during redirects and sign-in.
        // Keep first-party cookies enabled and allow third-party cookies for login compatibility.
        // This improves federated sign-in but may permit additional cross-site tracking.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Keep WebView remote debugging disabled in user-facing builds.
        WebView.setWebContentsDebuggingEnabled(false)
        settings.blockNetworkImage = hideImages
        settings.loadsImagesAutomatically = !hideImages

        // Third-party cookies are enabled on the main browser WebView for OAuth redirects.

        // تسجيل موضع التمرير أثناء التصفح لكل رابط (يُستخدم عند الرجوع)
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val wv = v as? WebView ?: return@setOnScrollChangeListener
            val tab = tabs.find { it.webView == wv } ?: return@setOnScrollChangeListener
            if (tab.pageLoading || tab.pendingRestoreY >= 0) return@setOnScrollChangeListener
            val url = wv.url ?: return@setOnScrollChangeListener
            rememberScroll(tab, url, scrollY)
            if (isSearchResultsPage(url)) {
                tab.lastSearchResultsUrl = url
                tab.lastSearchResultsY = scrollY
            }
        }

        // إذا لمس المستخدم الشاشة أثناء الاستعادة نوقفها حتى لا نتحكم بتمريره
        webView.setOnTouchListener { v, event ->
            // احتفظ بنقطة بداية اللمس فقط؛ لا تحدّثها أثناء حركة الإصبع
            // لأن ذلك يسبب تحديد جزء خاطئ من عنوان الرابط أو تحريك موضع التحديد.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                tabs.find { it.webView == v }?.let { t ->
                    if (t.pendingRestoreY >= 0) {
                        cancelScrollRestore(t)
                        try { t.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT } catch (e: Exception) { }
                    }
                }
            }
            false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // استخدم مضيف الصفحة الرئيسية حتى لا تتكرر النافذة بسبب موارد من مضيفين مختلفين.
                val sslSiteKey = runCatching {
                    Uri.parse(view.url ?: error.url).host?.lowercase(Locale.US)
                }.getOrNull()
                    ?: runCatching { Uri.parse(error.url).host?.lowercase(Locale.US) }.getOrNull()
                    ?: error.url.lowercase(Locale.US)

                when (sslSiteDecisions[sslSiteKey]) {
                    true -> {
                        handler.proceed()
                        return
                    }
                    false -> {
                        handler.cancel()
                        return
                    }
                    null -> Unit
                }

                val pending = pendingSslHandlers[sslSiteKey]
                if (pending != null) {
                    pending.add(handler)
                    return
                }
                pendingSslHandlers[sslSiteKey] = mutableListOf(handler)

                val details = when (error.primaryError) {
                    android.net.http.SslError.SSL_EXPIRED -> "الشهادة منتهية الصلاحية"
                    android.net.http.SslError.SSL_IDMISMATCH -> "اسم الموقع لا يطابق الشهادة"
                    android.net.http.SslError.SSL_NOTYETVALID -> "الشهادة لم تصبح صالحة بعد"
                    android.net.http.SslError.SSL_DATE_INVALID -> "تاريخ الشهادة غير صالح"
                    android.net.http.SslError.SSL_UNTRUSTED -> "جهة إصدار الشهادة غير موثوقة"
                    else -> "يوجد خطأ في شهادة أمان الموقع"
                }

                fun finishSslDecision(allow: Boolean) {
                    // خزّن السماح أو الرفض حتى لا تتكرر الرسالة لموارد هذه الصفحة.
                    sslSiteDecisions[sslSiteKey] = allow
                    val waitingHandlers = pendingSslHandlers.remove(sslSiteKey).orEmpty()
                    waitingHandlers.forEach { sslHandler ->
                        if (allow) sslHandler.proceed() else sslHandler.cancel()
                    }
                }

                val dialog = AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("تحذير شهادة SSL")
                    .setMessage(
                        "$details. قد يكون الاتصال مراقباً أو غير آمن. هل تريد المتابعة إلى هذا الموقع رغم المخاطرة؟"
                    )
                    .setPositiveButton("متابعة رغم المخاطرة") { _, _ ->
                        finishSslDecision(true)
                    }
                    .setNegativeButton("رجوع") { _, _ ->
                        finishSslDecision(false)
                    }
                    .create()

                dialog.setOnCancelListener {
                    finishSslDecision(false)
                }
                dialog.show()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // لا تعرض نافذة منبثقة عند فشل DNS أو تعذّر فتح الموقع.
                // اترك WebView يعرض حالة الخطأ داخل الصفحة فقط.
                super.onReceivedError(view, request, error)
            }

            @android.annotation.TargetApi(android.os.Build.VERSION_CODES.O)
            override fun onSafeBrowsingHit(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                threatType: Int,
                callback: android.webkit.SafeBrowsingResponse
            ) {
                // Never allow a URL identified by Android Safe Browsing to load.
                callback.backToSafety(true)
                Toast.makeText(
                    this@BrowserActivity,
                    "تم حظر هذا الرابط لأن التصفح الآمن صنّفه خطرًا.",
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                AdBlocker.maybeBlock(this@BrowserActivity, request)?.let { return it }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // تجاهل صفحة التحويل download.htm فقط؛ لا تعرضها كأنها ملف قابل للتنزيل.
                // تبقى نافذة التأكيد كما هي عند اكتشاف رابط ملف حقيقي.
                if (request.isForMainFrame) {
                    val path = request.url.path?.lowercase(Locale.US).orEmpty()
                    val lastSegment = path.substringAfterLast('/')
                    if (lastSegment == "download.htm" || lastSegment == "download.html") {
                        return false
                    }
                }
                // لا تعترض روابط صفحات الويب لمجرد احتوائها على كلمة download أو
                // امتداد/مسار ملتبس؛ اترك التنقل الطبيعي يعمل. التنزيلات الحقيقية
                // تظل تمر عبر setDownloadListener وتعرض نافذة اسم الملف والحجم والامتداد.
                if (request.isForMainFrame) {
                    val known = DownloadsRepository.downloadList.find { it.url == url }
                    if (known != null) {
                        // رابط تنزيل معروف: كان يُتجاهل بصمت، الآن تظهر رسالة «الملف موجود: الاسم» أو يُستأنف التنزيل
                        confirmDownload(
                            url,
                            suggestedFileName = known.fileName,
                            userAgent = view.settings.userAgentString,
                            referer = view.url
                        )
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    // عند الضغط على أي نتيجة/رابط من صفحات بحث SearXNG أو Google أو بحث الفيديو،
                    // افتح الموقع في تبويب جديد واترك صفحة نتائج البحث مفتوحة في تبويبها.
                    // hasGesture يمنع تحويل التنقلات التلقائية وإعادات التوجيه إلى تبويبات جديدة.
                    val isUserClickedSearchResult =
                        isSearchResultsPage(view.url) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        request.hasGesture() &&
                        (url.startsWith("http://", ignoreCase = true) ||
                         url.startsWith("https://", ignoreCase = true))
                    if (isUserClickedSearchResult) {
                        openPopupInNewTab(url)
                        return true
                    }
                    navigateWithSafetyCheck(view, url)
                    return true
                }
                
                // === حظر شامل للنوافذ المنبثقة ===
                // أي تنقل ليس main frame (مثل target="_blank" في iframes، أو window.open غير الملتقط)
                // نعرض حوار تأكيد قبل الفتح.
                if ((url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
                    showPopupConfirmDialog(url)
                    return true // نمنع التنقل التلقائي، ننتظر قرار المستخدم
                }
                
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val navigatingTab = tabs.find { it.webView == view }
                // احفظ موضع صفحة نتائج البحث قبل أن يبدأ الانتقال إلى الموقع،
                // لأن مستمع التمرير يتوقف أثناء تحميل الصفحة الجديدة.
                navigatingTab?.let { tab ->
                    val previousUrl = view.url
                    if (previousUrl != null && !tab.pageLoading && tab.pendingRestoreY < 0) {
                        rememberScroll(tab, previousUrl, view.scrollY)
                    }
                    tab.pageLoading = true
                }
                // سجّل الموقع فور بدء فتحه، لا تنتظر onPageFinished.
                recordVisitedNavigation(url)
                // تُحفظ حالة التبويبات بعد اكتمال الصفحة أو عند إيقاف النشاط؛
                // تجنّب تسلسل WebView على خيط الواجهة عند بدء كل تحميل.
                // أظهر خلفية سوداء فور بدء التنقل؛ حقن CSS ينتظر ظهور DOM في onPageCommitVisible.
                if (nightMode) view.setBackgroundColor(Color.BLACK)
                if (isActiveTab(view)) editUrl.setText(url)
            }

            // أول ظهور لمحتوى الصفحة الجديدة: أسرع وقت لتطبيق الوضع الليلي
            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                if (nightMode) {
                    applyNightModeJs(view)
                    applySearxngNightModeJs(view)
                }
                if (isGoogleSearchResultsPage(url)) hideGoogleResultMenuButtons(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)

                // امنع سحب الصور كعناصر HTML عند الضغط المطوّل، دون تعطيل
                // النقر على الصور أو تحديد النص أو تمرير الصفحة أو تكبير الصور.
                view.evaluateJavascript(
                    """
                    (function() {
                        if (window.__vinusImageDragGuardInstalled) return;
                        window.__vinusImageDragGuardInstalled = true;

                        var disableImageDrag = function(root) {
                            if (!root || !root.querySelectorAll) return;
                            if (root.tagName === 'IMG') root.setAttribute('draggable', 'false');
                            root.querySelectorAll('img').forEach(function(img) {
                                img.setAttribute('draggable', 'false');
                            });
                        };

                        disableImageDrag(document);
                        document.addEventListener('dragstart', function(event) {
                            var target = event.target;
                            if (target && (target.tagName === 'IMG' ||
                                (target.closest && target.closest('picture')))) {
                                event.preventDefault();
                                event.stopPropagation();
                            }
                        }, true);

                        var style = document.createElement('style');
                        style.textContent =
                            'img, picture, svg image {' +
                            '-webkit-user-drag: none !important;' +
                            'user-drag: none !important;' +
                            '}';
                        (document.head || document.documentElement).appendChild(style);

                        if (window.MutationObserver && document.documentElement) {
                            new MutationObserver(function(records) {
                                records.forEach(function(record) {
                                    record.addedNodes.forEach(disableImageDrag);
                                });
                            }).observe(document.documentElement, {
                                childList: true,
                                subtree: true
                            });
                        }
                    })();
                    """.trimIndent(),
                    null
                )

                // === حقن CSS للوضع الليلي الذكي ===
                // حماية الصور، الأيقونات، الخطوط الملونة، والفيديو من الانعكاس
                if (nightMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    val darkProtectCss = """
                        @media (prefers-color-scheme: dark) {
                            img, video, canvas, picture, svg, svg * {
                                filter: none !important;
                                -webkit-filter: none !important;
                                opacity: 1 !important;
                            }
                            [style*="color:"], [style*="background:"] {
                                filter: none !important;
                            }
                            ::selection {
                                background-color: #3399ff !important;
                                color: white !important;
                            }
                        }
                    """.trimIndent()
                    view.evaluateJavascript("""
                        (function() {
                            var style = document.createElement('style');
                            style.textContent = `$darkProtectCss`;
                            document.head.appendChild(style);
                        })();
                    """.trimIndent(), null)
                }

                // عند الرجوع إلى نتائج البحث لا نعيد توجيه Google لإضافة num=20؛
                // لأن تغيير الرابط أثناء الاستعادة يغيّر سجل WebView ويعيد التمرير إلى البداية.
                val restoringPreviousPosition = tabs.find { it.webView == view }?.pendingRestoreY ?: -1
                val tabRestoring = tabs.find { it.webView == view }
                if (restoringPreviousPosition < 0 && tabRestoring?.historyNavigationPending != true &&
                    ensureGoogleSearchPageSize(view)) return
                if (isGoogleSearchResultsPage(url)) {
                    hideGoogleResultMenuButtons(view)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isGoogleSearchResultsPage(view.url)) {
                            hideGoogleResultMenuButtons(view)
                        }
                    }, 1000L)
                    injectGooglePaginationJs(view)
                    mainHandler.postDelayed({
                        if (!isFinishing && !isDestroyed && isGoogleSearchResultsPage(view.url)) {
                            injectGooglePaginationJs(view)
                        }
                    }, 900L)
                }
                tabs.find { it.webView == view }?.let { t ->
                    t.pageLoading = false
                    // صفحة جديدة عادية (بدون استعادة جارية): سجّل موضعها الحالي
                    if (t.pendingRestoreY < 0) {
                        view.url?.let { u -> rememberScroll(t, u, view.scrollY) }
                        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                    }
                }
                if (url != null) {
                    recordVisitedNavigation(url)
                    BrowserStorage.addHistory(this@BrowserActivity, view.title ?: url, url)
                }
                val resolvedTitle = view.title?.takeIf { it.isNotBlank() }
                    ?: url?.takeIf { it.isNotBlank() }
                    ?: "صفحة جديدة"
                tabs.find { it.webView == view }?.title = resolvedTitle
                if (nightMode) {
                    applyNightModeJs(view)
                    applySearxngNightModeJs(view)
                    // لا نكرر حقن CSS بعد 500ms؛ تم تطبيقه عند ظهور المحتوى وعند اكتمال الصفحة.
                    // نحفظ حكم الصفحة (داكنة/فاتحة) لتُعالج أسرع في الزيارات القادمة
                    mainHandler.postDelayed({ if (nightMode) recordNightResult(view) }, 2200)
                }
                val tabForView = tabs.find { it.webView == view }
                if (tabForView?.desktopMode == true) applyDesktopViewportJs(view)
                if (isActiveTab(view)) {
                    if (hideImages || hideVideos) applyHideMediaJs(view)
                }

                if (isSearchResultsPage(url)) {
                    // الحالة مبنية على سجل دائم، لذلك تبقى الألوان بعد الرجوع وإعادة فتح التطبيق.
                    applyVisitedSearchResultColors(view)
                    filterSearxngMicrosoftResults(view)
                    // تلوين الروابط يراقب الإضافات الديناميكية بنفسه؛ لا نعيد فحص الصفحة كاملة بعد 700ms.
                }

                // Keep translating every new page opened inside this translated tab.
                if (tabForView?.translationEnabled == true) {
                    mainHandler.postDelayed({
                        translateTabPageIfNeeded(view, tabForView)
                    }, 500L)
                }

                persistTabs()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (isActiveTab(view)) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                // نرد على الصفحة فورًا بـ WebView مؤقت يلتقط رابط النافذة فقط.
                // الاحتفاظ برسالة النافذة حتى يضغط المستخدم «سماح» كان يوقف التطبيق
                // (المتصفح يُلغي النافذة المعلّقة إذا طلبت الصفحة نافذة أخرى أو طال الانتظار).
                val temp = WebView(this@BrowserActivity)
                // This temporary WebView only observes popup URLs; it must never run
                // page JavaScript or access local files/content while capturing them.
                temp.settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(temp, false)
                var finished = false

                fun dispose() {
                    if (finished) return
                    finished = true
                    // لا نهدم الـ WebView داخل ردّه نفسه: ننفّذ ذلك بعد انتهاء الاستدعاء الحالي
                    mainHandler.post {
                        try { temp.stopLoading() } catch (e: Exception) { }
                        try { temp.destroy() } catch (e: Exception) { }
                    }
                }

                fun capture(rawUrl: String?) {
                    if (finished) return
                    val u = rawUrl?.trim().orEmpty()
                    val lower = u.lowercase()
                    if (u.isEmpty() || lower.startsWith("about:") || lower.startsWith("javascript:") ||
                        lower.startsWith("data:") || lower.startsWith("blob:")) return
                    dispose()
                    // كل نافذة منبثقة تحتاج موافقة صريحة، حتى لو طلبها الموقع
                    // أثناء نقرة على صفحة نتائج البحث. لا توجد استثناءات للفتح التلقائي.
                    showPopupConfirmDialog(u)
                }

                temp.webViewClient = object : WebViewClient() {
                    // لا نحمّل شيئًا من الشبكة داخل الـ WebView المؤقت
                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        capture(request.url.toString())
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        capture(url)
                    }
                }

                transport.webView = temp
                resultMsg.sendToTarget()

                // إن لم يصل أي رابط (نافذة فارغة) نتخلص من الـ WebView المؤقت
                mainHandler.postDelayed({ dispose() }, 8000)
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // Use Android's system document picker: grant access only to the file
                // the user selects, instead of requesting broad access to all storage.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    callback.onReceiveValue(null)
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Camera/microphone access is never granted to an insecure HTTP origin.
                if (!request.origin.scheme.equals("https", ignoreCase = true) ||
                    request.origin.host.isNullOrBlank()) {
                    request.deny()
                    return
                }
                // نمنح فقط ما هو مسموح في إعدادات المتصفح، ونرفض الباقي
                val granted = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> cameraAllowed()
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> micAllowed()
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true   // مطلوب لتشغيل فيديوهات DRM
                        else -> false
                    }
                }
                val deniedByUser = request.resources.any { res ->
                    (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && !cameraAllowed()) ||
                        (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && !micAllowed())
                }
                if (granted.isEmpty() || deniedByUser) {
                    request.deny()
                    return
                }

                val androidPerms = mutableListOf<String>()
                if (granted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) androidPerms.add(Manifest.permission.CAMERA)
                if (granted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) androidPerms.add(Manifest.permission.RECORD_AUDIO)

                runOnUiThread {
                    ensureAndroidPermissions(androidPerms.toTypedArray()) { ok ->
                        if (ok) request.grant(granted.toTypedArray()) else request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                val originUri = try { Uri.parse(origin) } catch (_: Exception) { Uri.EMPTY }
                if (!locationAllowed() ||
                    !originUri.scheme.equals("https", ignoreCase = true) ||
                    originUri.host.isNullOrBlank()) {
                    callback.invoke(origin, false, false)
                    return
                }
                ensureAndroidPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    anyOf = true
                ) { ok -> callback.invoke(origin, ok, false) }
            }
        }

        // The blob-download bridge is attached only during an active blob transfer.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val nameFromHeader = DownloadsRepository.fileNameFromContentDispositionOrNull(contentDisposition)
            val parsedUrl = try { Uri.parse(url) } catch (_: Exception) { Uri.EMPTY }
            val path = parsedUrl.path.orEmpty().lowercase(Locale.US)
            val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
            val responseMime = mimeType?.substringBefore(';')?.trim().orEmpty()
            val isHtmlPage = extension in setOf("html", "htm") ||
                responseMime.equals("text/html", ignoreCase = true)
            // تجاهل تنزيل صفحات HTML غير المرغوبة مثل download.htm حتى لو أرسل الموقع
            // اسم ملف في Content-Disposition؛ مع الإبقاء على حوار تنزيل الملفات الحقيقية.
            // ملفات blob تبقى ضمن مسار التنزيل المعتاد.
            if (isHtmlPage && !url.startsWith("blob:", ignoreCase = true)) {
                return@setDownloadListener
            }
            if (url.startsWith("blob:", ignoreCase = true)) {
                blobBridge.start(webView, url, nameFromHeader, mimeType)
            } else {
                confirmDownload(
                    url,
                    suggestedFileName = nameFromHeader,
                    userAgent = userAgent,
                    referer = webView.url,
                    mimeType = mimeType
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data != null) {
                val selected = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) selected.add(clip.getItemAt(index).uri)
                }
                data.data?.let { if (it !in selected) selected.add(it) }
                selected.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }

        if (requestCode == BookmarksFileManager.EXPORT_REQUEST_CODE) {
            val content = pendingBookmarkExportContent
            pendingBookmarkExportContent = null
            if (resultCode == Activity.RESULT_OK && data?.data != null && content != null) {
                val result = BookmarksFileManager.writeExport(this, data.data!!, content)
                Toast.makeText(this, if (result.first) "تم تصدير العلامات المرجعية" else "فشل التصدير: ${result.second}", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (requestCode == BookmarksFileManager.IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val result = BookmarksFileManager.readImport(this, data.data!!)
                val html = result.first
                if (html == null) {
                    Toast.makeText(this, "فشل الاستيراد: ${result.second}", Toast.LENGTH_LONG).show()
                } else {
                    val parsed = BrowserStorage.parseBookmarksHtml(html)
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "لم يتم العثور على علامات مرجعية في الملف", Toast.LENGTH_LONG).show()
                    } else {
                        val added = BrowserStorage.importBookmarks(this, parsed)
                        Toast.makeText(this, "تم استيراد $added علامة مرجعية جديدة", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun isActiveTab(view: WebView): Boolean =
        tabs.isNotEmpty() && currentTabIndex < tabs.size && tabs[currentTabIndex].webView == view

    // ---------------- التنقل + فحص الأمان ----------------

    /** يسجّل الرابط كمزار. روابط جوجل الوسيطة (/url?q=...) تُفك ليُسجَّل الموقع الحقيقي. */
    private fun isVisitedSite(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try { Uri.parse(url).host } catch (e: Exception) { null }
        return VisitedSites.isVisited(this, host)
    }

    private fun visitedSiteColor(url: String?, unvisitedColor: Int = Color.CYAN): Int {
        return if (isVisitedSite(url)) Color.parseColor("#808080") else unvisitedColor
    }

    /**
     * يجبر صفحة SearXNG المستخدمة لبحث الويب والفيديو على ألوان داكنة واضحة.
     * بعض نسخ SearXNG لا تتبع تفضيل الوضع الليلي في WebView، لذلك لا نعتمد على
     * خوارزمية اكتشاف ألوان الصفحة وحدها.
     */
    /**
     * يفرض الألوان الداكنة على نتائج بحث Google فقط.
     * لا يغيّر SearXNG أو بحث الفيديو أو الوضع الليلي داخل المواقع.
     */
    /**
     * لا يفرض المتصفح ألوانًا مخصصة على نتائج البحث؛ ينظّف فقط أي نمط قديم
     * كان قد أُضيف سابقًا، لتبقى ألوان الموقع وWebView الافتراضية.
     */
    private fun applySearxngNightModeJs(webView: WebView) {
        val js = """
            (function() {
                try {
                    var style = document.getElementById('__downls10_google_search_dark__');
                    if (style) style.remove();
                    if (document.documentElement) {
                        document.documentElement.style.removeProperty('background-color');
                        document.documentElement.style.removeProperty('color-scheme');
                    }
                    if (document.body) {
                        document.body.style.removeProperty('background-color');
                        document.body.style.removeProperty('color');
                    }
                    if (window.__downls10GoogleDarkObserver) {
                        window.__downls10GoogleDarkObserver.disconnect();
                        window.__downls10GoogleDarkObserver = null;
                    }
                    if (window.__downls10GoogleDarkTimer) {
                        clearInterval(window.__downls10GoogleDarkTimer);
                        window.__downls10GoogleDarkTimer = null;
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // حذف عنصر قائمة النقاط الثلاث من DOM في نتائج بحث Google، وليس إخفاءه بـ CSS.
    private fun hideGoogleResultMenuButtons(webView: WebView) {
        val js = """
            (function() {
              try {
                var menuSelectors = [
                  '[aria-label="About this result"]',
                  '[aria-label="حول هذه النتيجة"]',
                  '[data-tooltip="About this result"]',
                  '[aria-label*="About this result" i]',
                  '[aria-label*="حول هذه النتيجة"]'
                ];

                function removeResultMenus(root) {
                  (root || document).querySelectorAll(menuSelectors.join(',')).forEach(function(node) {
                    var button = node.closest('button,[role="button"]') || node;
                    if (button && button !== document.body && button !== document.documentElement) button.remove();
                  });
                }

                // إخفاء كتلة "تم البحث أيضًا عن" وروابط عمليات البحث ذات الصلة أسفلها.
                function removeRelatedSearches(root) {
                  var scope = root || document;
                  var headings = scope.querySelectorAll
                    ? scope.querySelectorAll('h1,h2,h3,h4,[role="heading"],div,span')
                    : [];
                  var labels = [
                    'تم البحث أيضًا عن', 'تم البحث ايضا عن',
                    'بحث أيضًا عن', 'بحث ايضا عن',
                    'people also search for', 'related searches'
                  ];
                  headings.forEach(function(el) {
                    if (!el || !el.textContent) return;
                    var label = el.textContent.replace(/\s+/g, ' ').trim().toLowerCase();
                    if (!labels.some(function(x) { return label === x || label.indexOf(x) === 0; })) return;

                    var candidate = el;
                    // اصعد إلى أصغر حاوية تضم عنوان القسم وروابطه، دون حذف الصفحة كلها.
                    for (var level = 0; level < 6 && candidate.parentElement; level++) {
                      var parent = candidate.parentElement;
                      if (parent === document.body || parent === document.documentElement) break;
                      candidate = parent;
                      var links = candidate.querySelectorAll ? candidate.querySelectorAll('a') : [];
                      var textLength = (candidate.innerText || candidate.textContent || '').length;
                      if (links.length >= 2 && textLength < 1800) break;
                    }
                    if (candidate && candidate !== document.body &&
                        candidate !== document.documentElement && candidate.parentElement) {
                      candidate.remove();
                    }
                  });
                }

                function clean(root) {
                  removeResultMenus(root);
                  removeRelatedSearches(root);
                }

                clean(document);
                if (!window.__vinusGoogleResultMenuObserver) {
                  window.__vinusGoogleResultMenuObserver = new MutationObserver(function(records) {
                    records.forEach(function(record) {
                      record.addedNodes.forEach(function(node) {
                        if (node.nodeType === 1) clean(node);
                      });
                    });
                  });
                  window.__vinusGoogleResultMenuObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true
                  });
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isGoogleSearchResultsPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val googleHost = host == "google.com" || host.startsWith("google.") ||
            host.endsWith(".google.com") || host.endsWith(".googleusercontent.com")
        return googleHost && (uri.getQueryParameter("q") != null || uri.path?.contains("/search") == true)
    }

    /**
     * اطلب 20 نتيجة في صفحة Google. لا يغيّر أي محرك بحث أو صفحة موقع أخرى.
     */
    private fun ensureGoogleSearchPageSize(webView: WebView): Boolean {
        val current = webView.url ?: return false
        if (!isGoogleSearchResultsPage(current)) return false
        return try {
            val uri = Uri.parse(current)
            if (uri.getQueryParameter("num") == "20") return false
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (name != "num") {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.appendQueryParameter("num", "20")
            val target = builder.build().toString()
            if (target != current) {
                webView.loadUrl(target)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * يستبدل زر "المزيد من نتائج البحث" في Google بزر صفحة تالية،
     * وينتقل إلى start + 20 مع إبقاء عبارة البحث وبقية خياراتها.
     */
    private fun injectGooglePaginationJs(webView: WebView) {
        if (!nightMode && !isGoogleSearchResultsPage(webView.url)) {
            // الترقيم ميزة لنتائج Google فقط، مستقلة عن الوضع الليلي.
        }
        val js = """
            (function() {
              try {
                if (!(/(^|\.)google\./i.test(location.hostname) || location.hostname.toLowerCase().endsWith('.google.com'))) return;
                var params = new URLSearchParams(location.search);
                if (!params.has('q')) return;
                var currentStart = parseInt(params.get('start') || '0', 10);
                if (!isFinite(currentStart) || currentStart < 0) currentStart = 0;
                var nextUrl = new URL(location.href);
                nextUrl.searchParams.set('num', '20');
                nextUrl.searchParams.set('start', String(currentStart + 20));

                var labels = ['المزيد من نتائج البحث', 'More results', 'عرض المزيد من النتائج', 'المزيد'];
                document.querySelectorAll('a,button,[role="button"]').forEach(function(el) {
                  var label = (el.innerText || el.getAttribute('aria-label') || '').trim();
                  if (labels.some(function(x) { return label.toLowerCase() === x.toLowerCase(); })) {
                    el.style.setProperty('display', 'none', 'important');
                  }
                });

                var old = document.getElementById('__downls10_google_next_page');
                if (!old) {
                  old = document.createElement('a');
                  old.id = '__downls10_google_next_page';
                  old.textContent = 'الصفحة التالية';
                  old.style.cssText = 'display:block;box-sizing:border-box;margin:28px auto 36px;padding:15px 26px;width:calc(100% - 24px);max-width:320px;text-align:center;border:1px solid #555;border-radius:14px;background:#202124;color:#fff!important;font-size:17px;font-weight:600;text-decoration:none;direction:rtl;box-shadow:0 3px 10px rgba(0,0,0,.28);';
                }
                // انقل الزر إلى ما بعد عمود نتائج البحث بالكامل، حتى يظهر أسفل آخر نتيجة.
                // لا نضعه بعد #rso وحده لأن Google قد يستخدمه لحاوية جزئية أعلى النتائج.
                var resultsBottom = document.querySelector('#center_col') ||
                                    document.querySelector('#search') ||
                                    document.querySelector('#rso');
                if (resultsBottom && resultsBottom.parentNode) {
                  resultsBottom.insertAdjacentElement('afterend', old);
                } else {
                  var bottom = document.querySelector('#botstuff');
                  (bottom || document.body).appendChild(old);
                }
                old.href = nextUrl.toString();
                old.onclick = function(e) {
                  e.preventDefault();
                  location.href = nextUrl.toString();
                };
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun isSearchResultsPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        val path = uri.path?.lowercase(Locale.US) ?: ""

        // صفحات نتائج البحث المعروفة: Google ومحركات البحث الأخرى، مع نطاقات Google المحلية.
        val knownSearchHost =
            host == "google.com" || host.startsWith("google.") || host.endsWith(".google.com") ||
            host == "bing.com" || host.endsWith(".bing.com") ||
            host == "search.yahoo.com" ||
            host == "duckduckgo.com" || host.endsWith(".duckduckgo.com") ||
            host == "search.brave.com" ||
            host.endsWith(".yandex.com") || host.endsWith(".yandex.ru") ||
            host == "search.aol.com" ||
            host == "search.naver.com" ||
            host == "www.ecosia.org" ||
            host == "search.qwant.com"

        if (knownSearchHost) {
            return path.contains("search") ||
                uri.getQueryParameter("q") != null ||
                uri.getQueryParameter("query") != null ||
                uri.getQueryParameter("p") != null ||
                uri.getQueryParameter("text") != null
        }

        // بحث الفيديو (مثل صفحة نتائج YouTube) يُعامل كصفحة نتائج:
        // الضغط على نتيجة منها يفتح تبويباً مباشرة بلا نافذة تأكيد.
        val isVideoSearchHost =
            host == "youtube.com" || host == "www.youtube.com" ||
            host == "m.youtube.com" || host == "youtube-nocookie.com"
        if (isVideoSearchHost && (path == "/results" || path.startsWith("/results/"))) {
            return uri.getQueryParameter("search_query") != null ||
                uri.getQueryParameter("q") != null
        }

        // SearXNG ومحركات البحث المستضافة على نطاقات مختلفة.
        // لا نصنّف بقية صفحات المواقع على أنها نتائج بحث لمجرد وجود معامل q في الرابط.
        return (path == "/search" || path.startsWith("/search/")) &&
            (uri.getQueryParameter("q") != null ||
             uri.getQueryParameter("query") != null ||
             uri.getQueryParameter("search_query") != null ||
             uri.getQueryParameter("text") != null)
    }

    private fun filterSearxngMicrosoftResults(webView: WebView) {
        val host = try {
            Uri.parse(webView.url ?: "").host?.lowercase(Locale.US)
        } catch (_: Exception) {
            null
        }
        if (host != "searx.ononoki.org") return

        val js = """
            (function() {
                try {
                    var badHosts = ["bing.com", "microsoft.com"];

                    function isBadHost(host) {
                        host = String(host || "").toLowerCase().replace(/^www\./, "");
                        return badHosts.some(function(b) {
                            return host === b || host.endsWith("." + b);
                        });
                    }

                    document.querySelectorAll('a[href]').forEach(function(a) {
                        try {
                            var u = new URL(a.href, location.href);
                            if (!isBadHost(u.hostname)) return;

                            var card = a.closest("article.result, .result, li.result");
                            if (card) card.remove();
                        } catch (e) {}
                    });
                } catch (e) {}
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    /**
     * لا نفرض ألوانًا مخصصة على روابط نتائج البحث أو الروابط التي سبق فتحها.
     * نترك WebView والموقع نفسه يحددان ألوان الروابط الافتراضية.
     */
    private fun applyVisitedSearchResultColors(webView: WebView) {
        // لا نفرض أي لون مخصص على روابط نتائج البحث؛ اترك ألوان الموقع الافتراضية.
        val js = """
            (function() {
              try {
                var key='__downls10_visited_links_style__';
                var style=document.getElementById(key);
                if(style) style.remove();
                document.querySelectorAll('a.__downls10_visited_link__').forEach(function(a){
                  a.classList.remove('__downls10_visited_link__');
                });
                if(window.__downls10_visited_links_observer__){
                  window.__downls10_visited_links_observer__.disconnect();
                  window.__downls10_visited_links_observer__=null;
                }
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun recordVisitedNavigation(url: String?) {
        if (url.isNullOrBlank()) return
        val uri = try { Uri.parse(url) } catch (e: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        var realUrl = url
        val host = uri.host ?: return
        if (host.contains("google.") && uri.path == "/url") {
            val target = uri.getQueryParameter("q") ?: uri.getQueryParameter("url")
            if (!target.isNullOrBlank()) realUrl = target
        }
        val realHost = try { Uri.parse(realUrl).host } catch (e: Exception) { null }
        VisitedSites.recordVisit(this, realHost)
        VisitedSites.recordUrl(this, realUrl)
    }

    private fun loadAndRecord(webView: WebView, url: String) {
        recordVisitedNavigation(url)
        webView.loadUrl(url)
    }

    /** يلتقط روابط الملفات المباشرة ذات الامتداد المعروف فقط، وليس صفحات البحث أو صفحات الويب. */
    private fun looksLikeDirectFileUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val path = uri.path?.lowercase(Locale.US).orEmpty()
        val fileName = path.substringAfterLast('/')
        // لا تعتبر المسارات بلا نقطة/امتداد ملفات؛ مثل /search أو /download-page.
        if (fileName.isBlank() || '.' !in fileName) return false
        val ext = fileName.substringAfterLast('.', "").substringBefore(';')
        if (ext.isBlank() || ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return false

        val webPageExtensions = setOf(
            "html", "htm", "php", "asp", "aspx", "jsp", "cgi", "do",
            "action", "ashx", "axd", "pl"
        )
        val knownFileExtensions = setOf(
            "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "pdf", "txt", "csv", "json", "xml", "doc", "docx", "xls", "xlsx",
            "ppt", "pptx", "mp3", "m4a", "aac", "wav", "ogg", "flac",
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "jpg", "jpeg", "png",
            "gif", "webp", "bmp", "svg", "iso", "img", "exe", "msi", "dmg",
            "deb", "rpm", "torrent", "epub", "mobi"
        )
        return ext !in webPageExtensions && ext in knownFileExtensions
    }

    /** يتحقق مما إذا كان الرابط مسار تنزيل واضحًا؛ لا يصنّف روابط البحث العادية كتنزيل. */
    private fun mightBeDownloadUrl(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "http" && scheme != "https") return false

        if (looksLikeDirectFileUrl(rawUrl)) return true

        // افحص قيمة معاملات التنزيل نفسها بدل البحث عن كلمات قد تظهر داخل نص البحث.
        val downloadParams = setOf("download", "dl", "attachment", "file", "export")
        for (name in uri.queryParameterNames) {
            if (name.lowercase(Locale.US) in downloadParams) {
                val value = uri.getQueryParameter(name)?.lowercase(Locale.US).orEmpty()
                if (value in setOf("1", "true", "yes", "download", "attachment")) return true
            }
        }

        val path = uri.path?.lowercase(Locale.US).orEmpty().trimEnd('/')
        val downloadPaths = setOf("/download", "/dl", "/file", "/export", "/attachment")
        return path in downloadPaths
    }

    /**
     * زر تنزيل صغير فوق الفيديوهات داخل صفحات الويب.
     * لا يبدأ التنزيل إلا بعد ضغط المستخدم، ثم يستخدم مسار التنزيل المعتاد في التطبيق.
     */
    private inner class VideoDownloadBridge(private val sourceWebView: WebView) {
        @JavascriptInterface
        fun downloadVideo(rawUrl: String?, rawName: String?, rawMime: String?) {
            val videoUrl = rawUrl?.trim().orEmpty()
            val uri = try { Uri.parse(videoUrl) } catch (_: Exception) { Uri.EMPTY }
            if (videoUrl.isBlank() ||
                (uri.scheme != "https" && uri.scheme != "http" && uri.scheme != "blob")) {
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "رابط الفيديو غير صالح للتنزيل", Toast.LENGTH_SHORT).show()
                }
                return
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val fileName = rawName?.takeIf { it.isNotBlank() } ?: "video.mp4"
                val mime = rawMime?.takeIf { it.isNotBlank() } ?: "video/mp4"
                if (videoUrl.startsWith("blob:", ignoreCase = true)) {
                    blobBridge.start(sourceWebView, videoUrl, fileName, mime)
                } else {
                    confirmDownload(
                        videoUrl,
                        suggestedFileName = fileName,
                        userAgent = sourceWebView.settings.userAgentString,
                        referer = sourceWebView.url,
                        mimeType = mime,
                        showToast = true
                    )
                }
            }
        }
    }

    /** يعرض حوار تأكيد التنزيل مع اسم الملف وحجمه وامتداده، ثم ينفذ الإجراء المختار من المستخدم. */
    private fun confirmDownload(
        url: String,
        suggestedFileName: String?,
        userAgent: String?,
        referer: String?,
        mimeType: String? = null,
        showToast: Boolean = true
    ) {
        var dialogShown = false

        DownloadsRepository.probeAndConfirm(
            this@BrowserActivity,
            url,
            suggestedFileName,
            userAgent,
            referer,
            mimeType
        ) { preview ->
            if (!dialogShown) {
                dialogShown = true
                showDownloadDialog(preview, url, userAgent, referer, mimeType, showToast)
            }
        }

        // خطة بديلة: إذا استغرق الاستكشاف أكثر من 5 ثوانٍ، عرض الحوار بمعلومات أساسية
        Handler(Looper.getMainLooper()).postDelayed({
            if (!dialogShown) {
                dialogShown = true
                // إنشاء معاينة أساسية من المعلومات المتاحة
                val fileName = suggestedFileName?.takeIf { it.isNotBlank() } 
                    ?: url.substringAfterLast('/', "").takeIf { it.isNotBlank() } 
                    ?: "download"
                val ext = fileName.substringAfterLast(".", "bin")
                val isHls = url.substringBefore('?').substringBefore('#')
                    .endsWith(".m3u8", ignoreCase = true) ||
                    mimeType?.contains("mpegurl", ignoreCase = true) == true ||
                    ext.equals("m3u8", ignoreCase = true)
                // لا تعرض حجم ملف قائمة HLS على أنه حجم الفيديو إذا تأخر فحص المقاطع.
                val basicPreview = DownloadsRepository.DownloadPreview(
                    fileName, if (isHls) -1L else 0L, ext
                )
                showDownloadDialog(basicPreview, url, userAgent, referer, mimeType, showToast)
            }
        }, 20000)
    }

    /** يعرض حوار التنزيل مع المعلومات المتاحة */
    private fun showDownloadDialog(
        preview: DownloadsRepository.DownloadPreview,
        url: String,
        userAgent: String?,
        referer: String?,
        mimeType: String?,
        showToast: Boolean
    ) {
        val sizeText = if (preview.totalSize >= 0L) {
            val bytes = preview.totalSize
            if (bytes < 1024L) "$bytes B"
            else {
                val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
                var amount = bytes.toDouble() / 1024.0
                var unit = 0
                while (amount >= 1024.0 && unit < units.lastIndex) {
                    amount /= 1024.0
                    unit++
                }
                String.format(java.util.Locale.US, if (amount < 10.0) "%.2f %s" else "%.1f %s", amount, units[unit])
            }
        } else "حجم غير معروف"

        val dialogView = layoutInflater.inflate(R.layout.dialog_download_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileName).text = preview.fileName
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileSize).text = sizeText
        dialogView.findViewById<android.widget.TextView>(R.id.tvFileExt).text = preview.extension.ifBlank { "-" }

        val dialog = android.app.AlertDialog.Builder(this@BrowserActivity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnAdd).setOnClickListener {
            DownloadsRepository.addDownloadItemOnly(
                this@BrowserActivity, url, preview.fileName, userAgent, referer,
                preview.totalSize
            )
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnDownload).setOnClickListener {
            val startDownload = {
                DownloadsRepository.startNewDownload(
                    this@BrowserActivity, url,
                    showToast = showToast,
                    suggestedFileName = preview.fileName,
                    userAgent = userAgent,
                    referer = referer,
                    mimeType = mimeType
                )
            }
            // Android 9 and older need the legacy write permission for the public download folder.
            // Android 10+ uses MediaStore and must not be asked for broad storage access.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                ensureAndroidPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                    if (granted) startDownload()
                    else Toast.makeText(
                        this@BrowserActivity,
                        "يحتاج التطبيق إذن التخزين لحفظ الملف. يمكنك اختيار ملف من مدير الملفات عند الحاجة.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                startDownload()
            }
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun navigateWithSafetyCheck(webView: WebView, rawUrl: String) {
        val trimmedUrl = rawUrl.trim()
        if (trimmedUrl.isBlank()) return
        val lowerUrl = trimmedUrl.lowercase(Locale.US)

        // روابط التطبيقات (intent:// وغيرها) تحتاج Android أو التطبيق المرتبط بها؛
        // لا يمكن لمحرك WebView عرض مخطط تطبيق غير ويب كصفحة إنترنت.
        if (lowerUrl.startsWith("intent:")) {
            openExternalAppLink(webView, trimmedUrl, Uri.parse(trimmedUrl))
            return
        }

        // Block confirmed phishing URLs from the OpenPhish feed before navigation.
        // Android Safe Browsing remains an independent, non-bypassable protection layer.
        if ((lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) &&
            AdBlocker.isHighRiskUrl(this, trimmedUrl)
        ) {
            Toast.makeText(this, "تم منع فتح هذا الرابط لأنه مُدرج في قاعدة بيانات OpenPhish للتصيّد.", Toast.LENGTH_LONG).show()
            return
        }

        // المخططات التي تمثل ملفات/تنزيلات تذهب إلى مدير التنزيل.
        if (lowerUrl.startsWith("magnet:") || lowerUrl.startsWith("ftp://")) {
            DownloadsRepository.startNewDownload(this, trimmedUrl, showToast = true, referer = webView.url)
            return
        }
        if (MegaSupport.isMegaFileLink(trimmedUrl)) {
            DownloadsRepository.startNewDownload(this, trimmedUrl, showToast = true, referer = webView.url)
            return
        }

        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        tabs.find { it.webView == webView }?.let { cancelScrollRestore(it) }

        val uri = runCatching { Uri.parse(trimmedUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.US)

        // أزلنا فحص تصنيفات المواقع المحلي ورسائل التحذير التي كانت تمنع فتحها.
        // روابط HTTP وHTTPS تُحمّل مباشرة داخل المتصفح دون فحص قائمة النطاقات.
        if (scheme == "http" || scheme == "https") {
            lastConfirmedSafetyUrl = null
            loadAndRecord(webView, trimmedUrl)
            return
        }

        // الروابط الأخرى مثل tel/mailto/sms/geo تفتح عبر التطبيق المناسب في Android.
        if (!scheme.isNullOrBlank()) {
            openExternalAppLink(webView, trimmedUrl, uri!!)
            return
        }

        // اترك WebView يتعامل مع الروابط النسبية أو العناوين التي لا تحتوي مخططاً.
        loadAndRecord(webView, trimmedUrl)
    }

    // يتحقق من أن رابط الرجوع HTTPS صالح قبل تحميله داخل WebView.
    private fun isValidExternalFallback(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val fallback = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        return fallback.scheme.equals("https", ignoreCase = true) &&
            !fallback.host.isNullOrBlank() &&
            fallback.userInfo == null
    }

    // يفتح روابط التطبيقات الخارجية (Google Play، تيليجرام، واتساب وغيرها).
    // عند غياب التطبيق أو فشل تشغيله، نعود إلى fallback HTTPS بعد تمريره بفحص الأمان.
    private fun openExternalAppLink(webView: WebView, rawUrl: String, uri: Uri) {
        var fallbackUrl: String? = null
        try {
            val externalIntent = if (uri.scheme.equals("intent", ignoreCase = true)) {
                val parsed = Intent.parseUri(rawUrl, Intent.URI_INTENT_SCHEME)
                fallbackUrl = parsed.getStringExtra("browser_fallback_url")?.trim()
                parsed.apply {
                    // لا تسمح لرابط الويب بتحديد Activity أو Selector صريح.
                    component = null
                    selector = null
                    action = Intent.ACTION_VIEW
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    // لا نمرر صلاحيات URI أو أعلام تشغيل غير مطلوبة من الرابط.
                    flags = 0
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            }

            val targetUri = externalIntent.data
            val targetScheme = targetUri?.scheme?.lowercase(Locale.US)
            // امنع المخططات المحلية/الخطرة؛ اسمح بروابط التطبيقات والمتاجر المعتادة فقط.
            if (targetUri == null || targetScheme.isNullOrBlank() ||
                targetScheme in setOf("file", "content", "javascript", "data", "about")
            ) {
                throw SecurityException("Unsupported external URI scheme")
            }

            // لا تستخدم resolveActivity هنا: قد يعيد null على Android 11+
            // بسبب قيود رؤية التطبيقات (Package Visibility)، حتى عندما يكون
            // هناك تطبيق قادر فعلاً على فتح الرابط. جرّب التشغيل مباشرةً
            // ودع Android يختار التطبيق المناسب، ثم التقط فشل التشغيل أدناه.
            try {
                startActivity(externalIntent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // لا يوجد تطبيق لهذا المخطط؛ جرّب رابط HTTPS البديل إن وجد.
            } catch (_: SecurityException) {
                // رفض النظام التشغيل؛ جرّب رابط HTTPS البديل الآمن.
            }

            // روابط market://details?id=... تحصل على صفحة Google Play HTTPS عند غياب المتجر.
            if (fallbackUrl.isNullOrBlank() && targetScheme == "market" &&
                targetUri.host.equals("details", ignoreCase = true)
            ) {
                val appId = targetUri.getQueryParameter("id")
                if (!appId.isNullOrBlank() && appId.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
                    fallbackUrl = "https://play.google.com/store/apps/details?id=" + Uri.encode(appId)
                }
            }

            if (isValidExternalFallback(fallbackUrl)) {
                navigateWithSafetyCheck(webView, fallbackUrl!!.trim())
            } else {
                Toast.makeText(
                    this,
                    "لا يوجد تطبيق مثبت لفتح هذا الرابط ولا يتوفر رابط HTTPS بديل آمن.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: Exception) {
            if (isValidExternalFallback(fallbackUrl)) {
                navigateWithSafetyCheck(webView, fallbackUrl!!.trim())
            } else {
                Toast.makeText(this, "تعذّر فتح الرابط أو التطبيق المرتبط به.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showBlockedInterstitial(webView: WebView, message: String) {
        val html = """
            <html dir="rtl"><body style="background:#000;color:#fff;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0;">
            <h2>🚫 $message</h2></body></html>
        """
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun setupGoogleSuggestions() {
        editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                val generation = ++suggestionGeneration
                if (query.length < 2 || (query.contains(".") && !query.contains(" "))) {
                    suggestionsPopup?.dismiss()
                    return
                }
                bgExecutor.execute {
                    val result = try {
                        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&hl=ar&q=" + Uri.encode(query))
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                        try {
                            val json = connection.inputStream.bufferedReader().use { it.readText() }
                            val arr = org.json.JSONArray(json)
                            val values = arr.optJSONArray(1) ?: org.json.JSONArray()
                            (0 until values.length()).mapNotNull { values.optString(it).takeIf { v -> v.isNotBlank() } }.distinct().take(8)
                        } finally { connection.disconnect() }
                    } catch (_: Exception) { emptyList<String>() }
                    mainHandler.post {
                        if (generation == suggestionGeneration && editUrl.hasFocus() && editUrl.text.toString().trim() == query) {
                            showSuggestionRows(result)
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        editUrl.setOnFocusChangeListener { _, focused -> if (!focused) suggestionsPopup?.dismiss() }
    }

    private fun showSuggestionRows(items: List<String>) {
        suggestionsPopup?.dismiss()
        if (items.isEmpty()) return
        suggestionItems = items
        val list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.DKGRAY)
            dividerHeight = 1
            adapter = object : BaseAdapter() {
                override fun getCount() = suggestionItems.size
                override fun getItem(position: Int) = suggestionItems[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = LinearLayout(this@BrowserActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 2, 8, 2)
                        setBackgroundColor(0xFF202020.toInt())
                    }
                    val label = TextView(this@BrowserActivity).apply {
                        text = getItem(position)
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        // سطر أفقي واحد؛ تتحرك الكتابة الطويلة لإظهار كامل الاقتراح.
                        maxLines = 1
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                        marqueeRepeatLimit = -1
                        isSelected = true
                        setPadding(8, 6, 8, 6)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    label.setOnClickListener {
                        editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                        suggestionsPopup?.dismiss(); loadFromAddressBar(); hideKeyboard()
                    }
                    val insert = Button(this@BrowserActivity).apply {
                        text = "➜"
                        setTextColor(Color.LTGRAY)
                        textSize = 14f
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(8, 0, 8, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            (48 * resources.displayMetrics.density).toInt(),
                            (40 * resources.displayMetrics.density).toInt()
                        )
                        setOnClickListener {
                            editUrl.setText(getItem(position)); editUrl.setSelection(editUrl.text.length)
                            suggestionsPopup?.dismiss()
                        }
                    }
                    row.addView(label); row.addView(insert)
                    return row
                }
            }
        }
        // إظهار خمسة اقتراحات كحد أقصى؛ عند وجود المزيد يمكن تمرير القائمة.
        val rowHeight = (52 * resources.displayMetrics.density).toInt()
        suggestionsPopup = PopupWindow(
            list,
            editUrl.width.coerceAtLeast(250),
            (items.size.coerceAtMost(5) * rowHeight),
            false
        ).apply {
            // جعل النافذة ثابتة: لا تختفي عند النقر خارجها أو عند التفاعل مع شريط البحث
            isOutsideTouchable = false
            isFocusable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF202020.toInt()))
            
            // منع إغلاق النافذة عند لمس شريط البحث (editUrl) أو التمرير داخل القائمة
            setTouchInterceptor { v, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    true // استهلاك الحدث، لا تغلق النافذة
                } else if (event.action == MotionEvent.ACTION_DOWN) {
                    val location = IntArray(2)
                    v.getLocationOnScreen(location)
                    val x = event.rawX
                    val y = event.rawY
                    // إذا كان النقر داخل حدود القائمة، لا تغلق
                    val inList = x >= location[0] && x <= location[0] + v.width &&
                                 y >= location[1] && y <= location[1] + v.height
                    // إذا كان النقر على editUrl (شريط البحث)، لا تغلق
                    val editUrlLocation = IntArray(2)
                    editUrl.getLocationOnScreen(editUrlLocation)
                    val inEditUrl = x >= editUrlLocation[0] && x <= editUrlLocation[0] + editUrl.width &&
                                    y >= editUrlLocation[1] && y <= editUrlLocation[1] + editUrl.height
                    !inList && !inEditUrl
                } else {
                    false
                }
            }
            
            // إظهار الاقتراحات تحت شريط البحث مباشرةً (وليس فوقه)، حتى لو كانت لوحة المفاتيح مفتوحة
            val location = IntArray(2)
            editUrl.getLocationOnScreen(location)
            val yBelowSearchBar = location[1] + editUrl.height
            val decorView = window.decorView
            showAtLocation(decorView, Gravity.NO_GRAVITY, location[0], yBelowSearchBar)
        }
        
        // إغلاق النافذة عند الضغط على زر الرجوع (Back)
        list.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                suggestionsPopup?.dismiss()
                true
            } else {
                false
            }
        }
    }

    private fun buildSearchUrl(query: String): String {
        val encoded = Uri.encode(query)
        val isNight = nightMode
        return when (selectedSearchProvider()) {
            SEARCH_SEARXNG -> {
                var url = SEARXNG_SEARCH_BASE + Uri.encode(SEARXNG_ENGINE_PREFIX + query)
                // إضافة معلمة الوضع الليلي لمحركات SearXNG التي تدعمها
                if (isNight) url += "&theme=dark"
                url
            }
            // بحث فيديو: اطلب نتائج من 22 محرك/مصدر فيديو عبر أوامر SearXNG.
            // تظل قائمة المحركات مخفية، ولا يتغير مسار بحث SearXNG العادي أو Google.
            SEARCH_VIDEO -> {
                val videoEngineBangs = listOf(
                    "!biv", "!brvid", "!ddv", "!gov", "!qwv", "!dm", "!od",
                    "!ptb", "!ppd", "!ru", "!sep", "!vm", "!wcv", "!yt",
                    "!mvw", "!sevid", "!in", "!nico", "!nvrv", "!acf", "!iq", "!sogouv"
                ).joinToString(" ")
                var url = "https://searx.ononoki.org/search?q=" +
                    Uri.encode("$videoEngineBangs $query")
                // إضافة معلمة الوضع الليلي لبحث الفيديو أيضاً
                if (isNight) url += "&theme=dark"
                url
            }
            else -> "https://www.google.com/search?q=" + encoded
        }
    }

    private fun loadFromAddressBar() {
        var input = editUrl.text.toString().trim()
        if (input.isEmpty()) return

        input = when {
            Patterns.WEB_URL.matcher(input).matches() && !input.startsWith("http") -> "https://$input"
            input.contains(" ") || !input.contains(".") -> {
                val searchUrl = buildSearchUrl(input)
                searchUrl
            }
            !input.startsWith("http://") && !input.startsWith("https://") -> "https://$input"
            else -> input
        }

        if (currentTab().isHome) {
            currentTab().isHome = false
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                currentWebView(),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        navigateWithSafetyCheck(currentWebView(), input)
    }

    // ---------------- قائمة ☰ ----------------

    private fun showBrowserMenu() {
        val options = arrayOf(
            "إخفاء الوسائط",
            "العلامات المرجعية",
            if (currentTab().desktopMode) "عرض الجوال" else "عرض سطح المكتب",
            "مانع الإعلانات وحماية التصفح",
            "تكبير/تصغير الخط",
            "السجل",
            "ترجمة إلى العربية",
            "الأذونات",
            "البحث",
            if (nightMode) "إيقاف الوضع الليلي" else "تفعيل الوضع الليلي"
        )

        AlertDialog.Builder(this)
            .setTitle("خيارات المتصفح")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHideMediaDialog()
                    1 -> showBookmarksDialog()
                    2 -> toggleDesktopMode()
                    3 -> showAdBlockSettingsDialog()
                    4 -> showFontZoomDialog()
                    5 -> showHistoryDialog()
                    6 -> runTranslate()
                    7 -> showPermissionsDialog()
                    8 -> showSearchProviderDialog()
                    9 -> toggleNightMode()
                }
            }
            .show()
    }

    private fun showSearchProviderDialog() {
        val providers = arrayOf(
            SEARCH_SEARXNG to "SearXNG",
            SEARCH_GOOGLE to "Google",
            SEARCH_VIDEO to "Videos"
        )
        val selected = selectedSearchProvider()
        val items = providers.map { (id, label) ->
            if (id == selected) "$label    ✅" else label
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("البحث")
            .setItems(items) { dialog, which ->
                val provider = providers[which].first
                saveSearchProvider(provider)
                dialog.dismiss()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }


    private fun showPermissionsDialog() {
        fun rows() = listOf(
            permissionStateText("الكاميرا (الأمامية والخلفية)", cameraAllowed()),
            permissionStateText("الميكروفون", micAllowed()),
            permissionStateText("الموقع الجغرافي", locationAllowed()),
            "تحديث الخدمات — فحص إصدار WebView"
        )

        val items = ArrayList(rows())
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.select_dialog_item, items)
        val dialog = AlertDialog.Builder(this)
            .setTitle("الأذونات")
            .setAdapter(adapter, null)
            .setNegativeButton("إغلاق", null)
            .create()
        dialog.show()

        // نحدّد المستمع بعد show() حتى تبقى القائمة مفتوحة وتتحدّث الحالة أمام المستخدم
        dialog.listView.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> togglePermission("camera", !cameraAllowed(), arrayOf(Manifest.permission.CAMERA), false)
                1 -> togglePermission("mic", !micAllowed(), arrayOf(Manifest.permission.RECORD_AUDIO), false)
                2 -> togglePermission(
                    "location", !locationAllowed(),
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), true
                )
                3 -> {
                    showUpdateServicesDialog()
                    return@setOnItemClickListener
                }
            }
            items.clear()
            items.addAll(rows())
            adapter.notifyDataSetChanged()
        }
    }

    /** يعرض فقط خدمة نظام فعلية يمكن معرفة إصدارها وتوجيه المستخدم لتحديثها رسمياً. */
    private fun showUpdateServicesDialog() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        } else null
        val message = if (packageInfo != null) {
            "مكوّن WebView: ${packageInfo.packageName}\nالإصدار: ${packageInfo.versionName ?: "غير معروف"}\n\nيتم تحديث WebView من متجر Google Play أو من تحديثات النظام، بحسب الجهاز."
        } else {
            "تعذّر قراءة إصدار WebView من النظام على هذا الجهاز. افتح تحديثات النظام أو متجر التطبيقات للتحقق من وجود تحديث."
        }
        AlertDialog.Builder(this)
            .setTitle("تحديث الخدمات")
            .setMessage(message)
            .setPositiveButton("فتح صفحة WebView الرسمية") { _, _ ->
                val packageName = packageInfo?.packageName ?: "com.google.android.webview"
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                try {
                    startActivity(marketIntent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "تعذّر فتح متجر التطبيقات", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun togglePermission(key: String, newValue: Boolean, androidPerms: Array<String>, anyOf: Boolean) {
        settingsPrefs().edit().putBoolean(key, newValue).apply()
        // عند السماح نطلب صلاحية أندرويد مسبقًا حتى تعمل مباشرة عندما يطلبها الموقع
        if (newValue) ensureAndroidPermissions(androidPerms, anyOf) { }
    }

    /** لا يُفتح أي Popup قبل موافقة المستخدم؛ تُصفّ الطلبات حتى لا يُهمل أي طلب. */
    private var popupDialogShowing = false
    private val pendingPopupUrls = java.util.ArrayDeque<String>()

    private fun showPopupConfirmDialog(url: String) {
        if (isFinishing || isDestroyed) return
        if (url.isBlank()) return
        // لا نسمح بتجاوز نافذة التأكيد عند وصول عدة نوافذ منبثقة بسرعة.
        if (!pendingPopupUrls.contains(url)) pendingPopupUrls.addLast(url)
        showNextPopupConfirmDialog()
    }

    private fun showNextPopupConfirmDialog() {
        if (popupDialogShowing || isFinishing || isDestroyed || pendingPopupUrls.isEmpty()) return

        val url = pendingPopupUrls.removeFirst()
        popupDialogShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("نافذة منبثقة")
            .setMessage("طلب هذا الموقع فتح نافذة جديدة. هل تسمح بفتحها؟")
            .setPositiveButton("سماح") { _, _ ->
                // لا يُفتح الرابط إلا من هذا الزر.
                openPopupInNewTab(url)
            }
            .setNegativeButton("رفض") { _, _ ->
                // الرفض نهائي لهذا الطلب: لا تفتح الرابط ولا تنشئ تبويبًا.
                // لا نعيد محاولة الرابط عند إغلاق مربع الحوار.
            }
            .create()

        // إغلاق النافذة بزر الرجوع أو بالضغط خارجها يُعامل كرفض أيضًا.
        dialog.setOnCancelListener {
            // لا تنفّذ أي إجراء لفتح الرابط عند الإلغاء.
        }

        dialog.setOnDismissListener {
            popupDialogShowing = false
            // اعرض الطلب التالي بعد إغلاق النافذة الحالية.
            mainHandler.post { showNextPopupConfirmDialog() }
        }
        dialog.show()
    }

    private fun openPopupInNewTab(rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        val lower = cleanUrl.lowercase()
        val isWebUrl = lower.startsWith("http://") || lower.startsWith("https://")

        // إذا كانت النافذة المنبثقة تحتوي رابط ملف مباشر، فزر «سماح» يبدأ
        // التنزيل مباشرة بدل تحميل الرابط داخل WebView ثم فقدان حدث التنزيل.
        if (isWebUrl && looksLikeDirectFileUrl(cleanUrl)) {
            confirmDownload(
                cleanUrl,
                suggestedFileName = null,
                userAgent = currentWebView().settings.userAgentString,
                referer = currentWebView().url
            )
            return
        }

        if (!isWebUrl || MegaSupport.isMegaFileLink(cleanUrl)) {
            // magnet/ftp/Mega/تطبيق خارجي: يتولاها مسار التنقل الحالي بدون تبويب فارغ
            navigateWithSafetyCheck(currentWebView(), cleanUrl)
            return
        }

        createTabInternal(isHome = false, url = null)
        switchToTab(tabs.size - 1)
        navigateWithSafetyCheck(tabs[tabs.size - 1].webView, cleanUrl)
        persistTabs()
    }
    private fun toggleSetting(key: String, value: Boolean, onEnabled: (Boolean) -> Unit) {
        settingsPrefs().edit().putBoolean(key, value).apply()
        onEnabled(value)
        Toast.makeText(this, if (value) "تم التفعيل" else "تم الإيقاف", Toast.LENGTH_SHORT).show()
    }

    private fun requestAndroidPermIfNeeded(permission: String) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), ANDROID_PERM_CODE)
        }
    }

    // فتح مدير التنزيلات بإنشاء شاشة سليمة بدل إعادة ترتيب نسخة قديمة من النشاط.
    private fun openDownLS10() {
        try {
            startActivity(Intent(this, DownLS10MainActivity::class.java))
        } catch (e: Exception) {
            android.util.Log.e("DownLS10", "Failed to open download manager", e)
            Toast.makeText(this, "تعذر فتح مدير التنزيلات", Toast.LENGTH_LONG).show()
        }
    }

    // إعدادات إخفاء الصور والفيديو بشكل مستقل.
    private fun showHideMediaDialog() {
        val options = arrayOf(
            if (hideImages) "إظهار الصور" else "إخفاء الصور",
            if (hideVideos) "إظهار الفيديو" else "إخفاء الفيديو"
        )
        AlertDialog.Builder(this)
            .setTitle("إخفاء الوسائط بالكامل")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setHideImages(!hideImages)
                    1 -> setHideVideos(!hideVideos)
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun setHideImages(enabled: Boolean) {
        hideImages = enabled
        hideMedia = hideImages
        settingsPrefs().edit()
            .putBoolean("hideImages", hideImages)
            .putBoolean("hideMedia", hideMedia)
            .apply()
        tabs.forEach { tab ->
            tab.webView.settings.blockNetworkImage = hideImages
            tab.webView.settings.loadsImagesAutomatically = !hideImages
            applyHideMediaJs(tab.webView)
        }
        Toast.makeText(this, if (enabled) "تم إخفاء الصور" else "تم إظهار الصور", Toast.LENGTH_SHORT).show()
    }

    private fun setHideVideos(enabled: Boolean) {
        hideVideos = enabled
        settingsPrefs().edit().putBoolean("hideVideos", hideVideos).apply()
        tabs.forEach { tab -> applyHideMediaJs(tab.webView) }
        Toast.makeText(
            this,
            if (enabled) "تم إخفاء صورة الفيديو مع استمرار تشغيله" else "تم إظهار الفيديو",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyHideMediaJs(webView: WebView) {
        // الإخفاء يعتمد على عناصر HTML وخصائص MIME وليس على امتداد الملف فقط،
        // لذلك يشمل WebP/AVIF/GIF/SVG وغيرها وأي صيغة فيديو تعرض داخل عنصر فيديو.
        val hideImagesCss = if (hideImages) """
            img, picture, svg, canvas, image, [role="img"], input[type="image"],
            video[poster], [style*="background-image"], [style*="background:"],
            *::before, *::after {
                visibility: hidden !important;
                background-image: none !important;
                -webkit-mask-image: none !important;
                mask-image: none !important;
            }
            * { background-image: none !important; }
        """ else """
            img, picture, svg, canvas, image, [role="img"], input[type="image"],
            video[poster] {
                visibility: visible !important;
            }
        """

        // إخفاء الفيديوهات مهما كان امتدادها، وكذلك مشغلات الفيديو المضمّنة المعروفة.
        // لا نحذف العناصر ولا نوقف التنزيل أو التشغيل؛ نخفي العرض فقط.
        val hideVideosCss = if (hideVideos) """
            video, video *, video[poster],
            iframe.__downls10_video_iframe,
            object.__downls10_video_object,
            embed.__downls10_video_embed,
            .__downls10_video_ad_container {
                visibility: hidden !important;
                opacity: 0 !important;
            }
        """ else """
            video, video *, video[poster],
            iframe.__downls10_video_iframe,
            object.__downls10_video_object,
            embed.__downls10_video_embed,
            .__downls10_video_ad_container {
                visibility: visible !important;
                opacity: 1 !important;
            }
        """

        val cssJson = org.json.JSONObject.quote(hideImagesCss + "\n" + hideVideosCss)
        val js = """
            (function(){
                var css = $cssJson;
                var styleId = '__downls10_hide_media__';

                function installStyle() {
                    var root = document.head || document.documentElement;
                    if (!root) return;
                    var style = document.getElementById(styleId);
                    if (!style) {
                        style = document.createElement('style');
                        style.id = styleId;
                        root.appendChild(style);
                    }
                    style.textContent = css;
                }

                function isVideoResource(value, mime) {
                    var v = String(value || '').toLowerCase();
                    var m = String(mime || '').toLowerCase();
                    return m.indexOf('video/') === 0 ||
                        /\.(mp4|m4v|webm|ogv|ogg|mov|qt|avi|mkv|flv|wmv|mpg|mpeg|mpe|m2v|3gp|3g2|ts|m2ts|mts|vob|mxf|asf|f4v|f4p|f4a|f4b|m3u8|mpd)(?:$|[?#&])/.test(v) ||
                        /(youtube\.com|youtu\.be|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|twitch\.tv|facebook\.com\/.*video|fb\.watch|player\.bilibili\.com|streamable\.com|wistia\.com|jwplayer)/.test(v);
                }

                function markVideoEmbeds(root) {
                    var scope = root && root.querySelectorAll ? root : document;
                    // صيغ إعلانات الفيديو الشائعة: Sticky, In-Stream, Floating Outstream وIn-Banner.
                    // غالباً يكون عنوان iframe عاماً، لذلك نفحص أسماء الحاويات وخصائص بيانات الإعلان أيضاً.
                    var videoAdPattern = /sticky[\s_-]*video|in[\s_-]*stream|floating[\s_-]*outstream|outstream[\s_-]*video|in[\s_-]*banner[\s_-]*video|video[\s_-]*ad|full[\s_-]*screen[\s_-]*ad|overlay[\s_-]*ad|k[\s_-]*fee|the[\s_-]*nun|wanna[\s_-]*break[\s_-]*from[\s_-]*the[\s_-]*ads?|glitch/i;
                    function markVideoAdContainer(el) {
                        var node = el;
                        for (var depth = 0; node && depth < 7 && node !== document.body; depth++, node = node.parentElement) {
                            var meta = [
                                node.id || '',
                                node.className && typeof node.className === 'string' ? node.className : '',
                                node.getAttribute && (node.getAttribute('data-ad-format') || ''),
                                node.getAttribute && (node.getAttribute('data-ad-type') || ''),
                                node.getAttribute && (node.getAttribute('aria-label') || ''),
                                node.getAttribute && (node.getAttribute('title') || '')
                            ].join(' ');
                            if (videoAdPattern.test(meta)) {
                                node.classList.add('__downls10_video_ad_container');
                                return;
                            }
                        }
                    }
                    scope.querySelectorAll('[id], [class], [data-ad-format], [data-ad-type], [aria-label]').forEach(function(el) {
                        var meta = [
                            el.id || '',
                            el.className && typeof el.className === 'string' ? el.className : '',
                            el.getAttribute('data-ad-format') || '',
                            el.getAttribute('data-ad-type') || '',
                            el.getAttribute('aria-label') || '',
                            el.getAttribute('title') || ''
                        ].join(' ');
                        if (videoAdPattern.test(meta)) el.classList.add('__downls10_video_ad_container');
                    });
                    scope.querySelectorAll('iframe, object, embed').forEach(function(el) {
                        var src = el.getAttribute('src') || el.getAttribute('data') || el.getAttribute('data-src') || '';
                        var type = el.getAttribute('type') || '';
                        var isVideo = isVideoResource(src, type);
                        if (!isVideo && el.tagName === 'IFRAME') {
                            var title = (el.getAttribute('title') || '').toLowerCase();
                            isVideo = /video|player|مشغل|full[\s_-]*screen[\s_-]*ad|overlay[\s_-]*ad|k[\s_-]*fee|the[\s_-]*nun|wanna[\s_-]*break[\s_-]*from[\s_-]*the[\s_-]*ads?|glitch/i.test(title);
                        }
                        if (isVideo) {
                            el.classList.add('__downls10_video_' + el.tagName.toLowerCase());
                            markVideoAdContainer(el);
                        }
                    });
                    // بعض المواقع تضع رابط الفيديو في data-src أو تنشئ عناصر الفيديو بعد التحميل.
                    scope.querySelectorAll('video, source').forEach(function(el) {
                        var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
                        if (el.tagName === 'VIDEO' || isVideoResource(src, el.getAttribute('type'))) {
                            var parent = el.tagName === 'VIDEO' ? el : el.closest('video');
                            if (parent) parent.classList.add('__downls10_video_element');
                        }
                    });
                }

                function apply(root) {
                    installStyle();
                    markVideoEmbeds(root || document);
                }

                apply(document);
                if (!window.__downls10MediaObserver && document.documentElement) {
                    window.__downls10MediaObserver = new MutationObserver(function(records) {
                        records.forEach(function(record) {
                            if (record.type === 'childList') {
                                record.addedNodes.forEach(function(node) {
                                    if (node.nodeType === 1) apply(node);
                                });
                            } else if (record.type === 'attributes') {
                                apply(record.target && record.target.parentNode);
                            }
                        });
                    });
                    window.__downls10MediaObserver.observe(document.documentElement, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['src', 'data-src', 'data', 'type', 'title']
                    });
                }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // ⭐ إضافة الصفحة الحالية مباشرة
    private fun addCurrentPageToBookmarks() {
        if (currentTab().isHome) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        val url = currentWebView().url
        val title = currentWebView().title
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "لا توجد صفحة لإضافتها", Toast.LENGTH_SHORT).show()
            return
        }
        if (BrowserStorage.isBookmarked(this, url)) {
            Toast.makeText(this, "الصفحة مضافة مسبقاً للعلامات", Toast.LENGTH_SHORT).show()
            return
        }
        BrowserStorage.addBookmark(this, title ?: url, url)
        Toast.makeText(this, "تمت الإضافة للعلامات المرجعية", Toast.LENGTH_SHORT).show()
    }

    // 2) العلامات المرجعية (عرض + حذف + استيراد/تصدير)
    private fun showBookmarksDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 6, 12, 6)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val bookmarks = BrowserStorage.getBookmarks(this)
            // إظهار ست علامات مرجعية كحد أقصى؛ وما زاد يظهر بالتمرير داخل القائمة.
            val rowHeight = (52 * resources.displayMetrics.density).toInt()
            val visibleRows = bookmarks.size.coerceAtMost(6)
            val listHeight = (rowHeight * visibleRows).coerceAtLeast(rowHeight)
            scroll.layoutParams = scroll.layoutParams?.apply {
                height = listHeight
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight
            )
            if (bookmarks.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "لا توجد علامات مرجعية بعد"
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 6, 0, 6)
                })
            } else {
                bookmarks.forEach { bookmark ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        // إزالة الفراغات الرأسية بين العلامات مع إبقاء الصف قابلًا للمس.
                        setPadding(0, 0, 0, 0)
                        minimumHeight = (52 * resources.displayMetrics.density).toInt()
                    }
                    val titleText = TextView(this).apply {
                        text = bookmark.title
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            createBrowsingTab(bookmark.url)
                            dialog.dismiss()
                        }
                    }
                    val deleteBtn = Button(this).apply {
                        text = "✕"
                        setTextColor(Color.WHITE)
                        minHeight = (52 * resources.displayMetrics.density).toInt()
                        setPadding(16, 0, 16, 0)
                        setOnClickListener {
                            BrowserStorage.removeBookmark(this@BrowserActivity, bookmark.url)
                            refresh()
                        }
                    }
                    row.addView(titleText)
                    row.addView(deleteBtn)
                    container.addView(row)

                    container.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(Color.parseColor("#333333"))
                    })
                }
            }
        }

        val titleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 8)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 0, 4)
        }
        titleRow.addView(TextView(this).apply {
            text = "العلامات المرجعية"
            textSize = 20f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlertDialog.Builder(this@BrowserActivity)
                    .setMessage("هل تريد حذف جميع العلامات المرجعية؟")
                    .setPositiveButton("موافق") { _, _ ->
                        BrowserStorage.getBookmarks(this@BrowserActivity).forEach { BrowserStorage.removeBookmark(this@BrowserActivity, it.url) }
                        refresh()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        })
        titleHeader.addView(titleRow)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        actionsRow.addView(Button(this).apply {
            text = "تصدير"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportBookmarks { refresh() } }
        })
        actionsRow.addView(Button(this).apply {
            text = "استيراد"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { importBookmarks { refresh() } }
        })
        titleHeader.addView(actionsRow)

        dialog.setCustomTitle(titleHeader)
        refresh()
        dialog.show()
    }

    private fun exportBookmarks(onFinished: () -> Unit) {
        pendingBookmarkExportContent = BrowserStorage.buildBookmarksHtml(this)
        try {
            BookmarksFileManager.startExport(this, pendingBookmarkExportContent!!)
        } catch (e: Exception) {
            pendingBookmarkExportContent = null
            Toast.makeText(this, "فشل فتح حفظ العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    private fun importBookmarks(onFinished: () -> Unit) {
        try {
            BookmarksFileManager.startImport(this)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل فتح اختيار ملف العلامات: ${e.message ?: "غير معروف"}", Toast.LENGTH_LONG).show()
        }
        onFinished()
    }

    // 3) عرض سطح المكتب (خاص بالتبويب الحالي فقط)
    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.desktopMode = !tab.desktopMode
        currentWebView().settings.userAgentString = if (tab.desktopMode) desktopUA else mobileUA
        currentWebView().reload()
        Toast.makeText(this, if (tab.desktopMode) "عرض سطح المكتب مفعّل لهذا التبويب" else "عرض الجوال مفعّل لهذا التبويب", Toast.LENGTH_SHORT).show()
    }

    private fun applyDesktopViewportJs(webView: WebView) {
        val js = """
            (function(){
                var meta = document.querySelector('meta[name=viewport]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.setAttribute('name', 'viewport');
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=1024, initial-scale=1, user-scalable=yes');
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // الوضع الليلي: يقلب ألوان أي موقع فاتح إلى أسود، ويترك الموقع الداكن كما هو.
    // الحالة محفوظة دائماً (تبقى بعد إغلاق التطبيق)، ويُطبَّق مباشرة على كل التبويبات عند التشغيل.
    private fun toggleNightMode() {
        nightMode = !nightMode
        settingsPrefs().edit().putBoolean("nightMode", nightMode).apply()
        tabs.forEach { tab ->
            tab.webView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
            if (!tab.isHome) {
                if (nightMode) {
                    applyNightModeJs(tab.webView)
                    applySearxngNightModeJs(tab.webView)
                } else {
                    removeNightModeJs(tab.webView)
                }
            }
        }
        Toast.makeText(this, if (nightMode) "الوضع الليلي مفعّل (ثابت دائماً)" else "تم إيقاف الوضع الليلي", Toast.LENGTH_SHORT).show()
    }

    private var lastNightInject = 0L

    /** آخر حكم محفوظ لهذا الموقع: true = داكن أصلاً، false = فاتح، null = غير معروف. */
    private fun nightHostState(host: String?): Boolean? {
        if (host.isNullOrBlank()) return null
        val raw = settingsPrefs().getString("nightHosts", null) ?: return null
        return try {
            val obj = org.json.JSONObject(raw)
            if (obj.has(host)) obj.getBoolean(host) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun rememberNightHost(host: String?, dark: Boolean) {
        if (host.isNullOrBlank()) return
        try {
            val raw = settingsPrefs().getString("nightHosts", null)
            var obj = if (raw != null) org.json.JSONObject(raw) else org.json.JSONObject()
            if (obj.length() > 400) obj = org.json.JSONObject()
            if (obj.has(host) && obj.getBoolean(host) == dark) return
            obj.put(host, dark)
            settingsPrefs().edit().putString("nightHosts", obj.toString()).apply()
        } catch (e: Exception) { }
    }

    /** يقرأ ما حكمت به الصفحة (داكنة أم فاتحة) ويحفظه لهذا الموقع ليُطبَّق أسرع في الزيارة القادمة. */
    private fun recordNightResult(webView: WebView) {
        val host = try { Uri.parse(webView.url ?: "").host } catch (e: Exception) { null } ?: return
        webView.evaluateJavascript(
            "(window.__downls10NightDark === true ? '1' : (window.__downls10NightDark === false ? '0' : ''))"
        ) { result ->
            val value = result?.trim('"')
            if (value == "1") rememberNightHost(host, true)
            else if (value == "0") rememberNightHost(host, false)
        }
    }

    /**
     * يطبّق الوضع الليلي على الصفحة. آمن للاستدعاء المتكرر (يُستدعى عند بدء الصفحة، وظهورها، وتقدّم التحميل، واكتمالها).
     * الصفحة الفاتحة تُقلب إلى أسود، والصفحة الداكنة أصلاً تبقى كما هي (يُفحص لون الخلفية الفعلي في عدة نقاط من الشاشة).
     */
    /**
     * لا يغيّر المتصفح ألوان صفحات الويب أو نتائج البحث؛ يزيل أي تأثير ليلي
     * قديم كي يعرض WebView ألوان كل موقع وروابطه الافتراضية.
     */
    private fun applyNightModeJs(webView: WebView) {
        // لا تفحص الخلفية بعد حقن CSS، لأن ذلك سيجعل الصفحة تبدو سوداء
        // ثم يزيل التأثير في الاستدعاء التالي. خزّن نتيجة الفحص الأصلية لكل صفحة.
        val js = """
            (function(){
              try {
                var id='__downls10_night_mode__';
                var root=document.documentElement;
                if(!root) return;
                function colorInfo(c){
                  if(!c || c==='transparent') return null;
                  var m=c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/i);
                  if(!m) return null;
                  var alpha=(m[4]===undefined)?1:parseFloat(m[4]);
                  if(alpha<0.15) return null;
                  var r=+m[1],g=+m[2],b=+m[3];
                  return {lum:(r*299+g*587+b*114)/1000,alpha:alpha};
                }
                function visible(el){
                  if(!el || !el.getBoundingClientRect) return false;
                  var r=el.getBoundingClientRect(),cs=getComputedStyle(el);
                  return r.width>Math.min(120,innerWidth*0.25) &&
                         r.height>Math.min(60,innerHeight*0.1) &&
                         cs.display!=='none' && cs.visibility!=='hidden' &&
                         parseFloat(cs.opacity||'1')>0;
                }
                if(typeof window.__downls10NightOriginalDark==='undefined'){
                  // لا نحكم أثناء تحميل الصفحة؛ بعض المواقع لا تنشئ body أو تضبط خلفيتها إلا لاحقاً.
                  if(document.readyState==='loading' || !document.body) return;
                  var darkSamples=0,lightSamples=0,knownSamples=0;
                  // افحص الخلفية المرئية فعلياً عبر نقاط متعددة من الشاشة، مع الصعود عبر العناصر الشفافة.
                  // هذا يمنع البطاقات البيضاء القليلة من جعل موقع أسود بالكامل يبدو فاتحاً.
                  for(var sy=0;sy<3;sy++){
                    for(var sx=0;sx<3;sx++){
                      var px=Math.max(1,Math.min(innerWidth-1,Math.round((sx+0.5)*innerWidth/3)));
                      var py=Math.max(1,Math.min(innerHeight-1,Math.round((sy+0.5)*innerHeight/3)));
                      var stack=document.elementsFromPoint ? document.elementsFromPoint(px,py) : [document.elementFromPoint(px,py)];
                      var found=null;
                      for(var si=0;si<stack.length && !found;si++){
                        var el=stack[si];
                        while(el && el!==root && el!==document.body){
                          var bg=colorInfo(getComputedStyle(el).backgroundColor);
                          if(bg && bg.alpha>=0.85){found=bg;break;}
                          el=el.parentElement;
                        }
                        if(!found){
                          var bodyBg=colorInfo(getComputedStyle(document.body).backgroundColor);
                          var rootBg=colorInfo(getComputedStyle(root).backgroundColor);
                          found=bodyBg || rootBg;
                        }
                      }
                      if(found){
                        knownSamples++;
                        if(found.lum<105) darkSamples++;
                        else if(found.lum>175) lightSamples++;
                      }
                    }
                  }
                  // استخدم لون html/body كمرجع إضافي عندما تكون الخلفية مرسومة على مستوى الصفحة.
                  var rootBg=colorInfo(getComputedStyle(root).backgroundColor);
                  var bodyBg=colorInfo(getComputedStyle(document.body).backgroundColor);
                  var pageBg=bodyBg || rootBg;
                  var mostlyDark=knownSamples>0 && darkSamples/knownSamples>=0.55 &&
                                 darkSamples>=lightSamples;
                  window.__downls10NightOriginalDark = mostlyDark ||
                    (!!pageBg && pageBg.lum<80 && lightSamples<=darkSamples);
                }
                var st=document.getElementById(id);
                // الموقع الداكن أصلاً لا نغيّر ألوانه.
                if(window.__downls10NightOriginalDark){
                  if(st) st.remove();
                  window.__downls10NightDark=true;
                  return;
                }
                if(!st){ st=document.createElement('style'); st.id=id; (document.head||root).appendChild(st); }
                // الوضع الليلي الحقيقي: اجعل مساحات الصفحة ونتائج البحث شفافة فوق خلفية سوداء،
                // مع إبقاء الصور والشعارات وSVG وأيقونات المواقع بألوانها وأشكالها الأصلية.
                // لا نستخدم invert أو grayscale ولا نمسّ background-image الخاصة بالمواقع.
                st.textContent =
                  'html,body{background:#000!important;background-color:#000!important;color:#f2f2f2!important;color-scheme:dark}'+
                  // غيّر لون النصوص العادية فقط؛ اترك الروابط والأزرار والأيقونات بألوان الموقع الأصلية.
                  'body :not(a):not(button):not(input):not(textarea):not(select):not(svg):not(canvas):not(img):not(video):not(picture):not(iframe):not([role="img"]):not([role="button"]):not([class*="icon"]):not([class*="Icon"]):not([class*="logo"]):not([class*="Logo"]):not([class*="download"]):not([class*="Download"]){color:#ededed!important;-webkit-text-fill-color:currentColor!important}'+
                  // لا نفرض لوناً على الروابط؛ تبقى الروابط المهمة بألوانها الأصلية.
                  'a,a:visited,a:hover,a:active,button,[role="button"],svg,img,picture,video,canvas,iframe,[class*="icon"],[class*="Icon"],[class*="logo"],[class*="Logo"],[class*="download"],[class*="Download"]{filter:none!important;-webkit-filter:none!important}'+
                  'input,textarea,select{background-color:#171717!important;color:#f2f2f2!important;-webkit-text-fill-color:#f2f2f2!important;color-scheme:dark;border-color:#444!important}'+
                  'input::placeholder,textarea::placeholder{color:#bdbdbd!important;-webkit-text-fill-color:#bdbdbd!important}';
                // سوّد الحاويات الفاتحة الكبيرة فقط، ولا تلمس خلفيات الصور أو الأيقونات أو الأزرار.
                var protectedSelector='a,button,input,textarea,select,svg,img,picture,video,canvas,iframe,[role="img"],[role="button"],[class*="icon"],[class*="Icon"],[class*="logo"],[class*="Logo"],[class*="download"],[class*="Download"]';
                var nodes=document.body ? document.body.querySelectorAll('*') : [];
                for(var ni=0;ni<nodes.length;ni++){
                  var node=nodes[ni];
                  if(node.matches(protectedSelector)) continue;
                  var box=node.getBoundingClientRect();
                  if(box.width<innerWidth*0.25 || box.height<Math.max(35,innerHeight*0.045)) continue;
                  var cs=getComputedStyle(node);
                  if(cs.display==='none'||cs.visibility==='hidden'||parseFloat(cs.opacity||'1')<0.05) continue;
                  if(cs.backgroundImage && cs.backgroundImage!=='none') continue;
                  var bg=colorInfo(cs.backgroundColor);
                  if(bg && bg.lum>170 && bg.alpha>0.15 &&
                     box.width*box.height>innerWidth*innerHeight*0.055){
                    node.style.setProperty('background-color','#000','important');
                  }
                }
                // اجعل روابط عناوين النتائج الداكنة مقروءة على الخلفية السوداء،
                // مع الحفاظ على الروابط الملونة مثل روابط التحميل والروابط البرتقالية.
                function fixDarkTextLinks(rootNode){
                  var links=(rootNode||document).querySelectorAll('a');
                  for(var ai=0;ai<links.length;ai++){
                    var link=links[ai];
                    if(link.closest('button,[role="button"],[class*="icon"],[class*="Icon"],[class*="logo"],[class*="Logo"]')) continue;
                    var rgb=colorInfo(getComputedStyle(link).color);
                    if(rgb && rgb.lum<135){
                      link.style.setProperty('color','#f2f2f2','important');
                      link.style.setProperty('-webkit-text-fill-color','#f2f2f2','important');
                    }
                  }
                }
                fixDarkTextLinks(document);
                if(!window.__downls10NightLinkObserver){
                  window.__downls10NightLinkObserver=new MutationObserver(function(records){
                    records.forEach(function(record){
                      record.addedNodes.forEach(function(node){
                        if(node.nodeType===1){
                          if(node.matches && node.matches('a')) fixDarkTextLinks(node.parentNode||document);
                          fixDarkTextLinks(node);
                        }
                      });
                    });
                  });
                  window.__downls10NightLinkObserver.observe(document.documentElement,{childList:true,subtree:true});
                }
                window.__downls10NightDark=false;
              } catch(e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun removeNightModeJs(webView: WebView) {
        val js = """
            (function(){var s=document.getElementById('__downls10_night_mode__');if(s)s.remove();
              window.__downls10NightDark=undefined;
              if(window.__downls10NightObserver){window.__downls10NightObserver.disconnect();window.__downls10NightObserver=null;}
              if(window.__downls10NightTimer){clearInterval(window.__downls10NightTimer);window.__downls10NightTimer=null;}
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    // 4) مانع الإعلانات وحماية التصفح (كل القوائم)
    private fun showAdBlockSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 4, 16, 4)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        fun refresh() {
            container.removeAllViews()
            val globalRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 6)
            }
            globalRow.addView(TextView(this).apply {
                text = "مانع الإعلانات"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            globalRow.addView(Button(this).apply {
                text = if (AdBlocker.isGloballyEnabled(this@BrowserActivity)) "مفعّل" else "معطّل"
                setOnClickListener {
                    AdBlocker.setGloballyEnabled(this@BrowserActivity, !AdBlocker.isGloballyEnabled(this@BrowserActivity))
                    tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                    refresh()
                }
            })
            container.addView(globalRow)
            AdBlocker.LISTS.forEach { def ->
                val block = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 5, 0, 5)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val nameText = TextView(this).apply {
                    text = def.displayName
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val toggleBtn = Button(this).apply {
                    val enabled = AdBlocker.isEnabled(this@BrowserActivity, def.id)
                    text = if (enabled) "مفعّل" else "متوقف"
                    contentDescription = if (def.id == "openphish") "تفعيل أو إيقاف حماية التصيّد" else "تفعيل أو إيقاف ${def.displayName}"
                    setOnClickListener {
                        val newVal = !AdBlocker.isEnabled(this@BrowserActivity, def.id)
                        AdBlocker.setEnabled(this@BrowserActivity, def.id, newVal)
                        tabs.forEach { tab -> if (!tab.isHome) tab.webView.reload() }
                        refresh()
                    }
                }
                row.addView(nameText)
                row.addView(toggleBtn)

                val statusRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val statusText = TextView(this).apply {
                    text = "${AdBlocker.ruleCount(this@BrowserActivity, def.id)} قاعدة"
                    setTextColor(Color.LTGRAY)
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val updateBtn = Button(this).apply {
                    text = if (def.id == "openphish") "تحديث يدوي" else "تحديث"
                    setOnClickListener {
                        AdBlocker.updateList(this@BrowserActivity, def.id) { success, count, error ->
                            if (!success) {
                                Toast.makeText(this@BrowserActivity, "${def.displayName}: فشل - $error", Toast.LENGTH_LONG).show()
                            }
                            refresh()
                        }
                    }
                }
                statusRow.addView(statusText)
                statusRow.addView(updateBtn)

                block.addView(row)
                block.addView(statusRow)
                container.addView(block)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }
        }

        refresh()

        AlertDialog.Builder(this)
            .setTitle("مانع الإعلانات وحماية التصفح")
            .setView(scroll)
            .setPositiveButton("إغلاق", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    val metrics = resources.displayMetrics
                    dialog.window?.setLayout(
                        (metrics.widthPixels * 0.90f).toInt(),
                        (metrics.heightPixels * 0.72f).toInt()
                    )
                }
                dialog.show()
            }
    }

    // 5) تكبير/تصغير الخط
    private fun showFontZoomDialog() {
        val current = savedFontZoom()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 8, 24, 8)
        }

        val sizeText = TextView(this).apply {
            text = "حجم الخط: ${current}%"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val decrease = Button(this).apply {
            text = "تصغير -"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val increase = Button(this).apply {
            text = "تكبير +"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        controls.addView(decrease)
        controls.addView(increase)

        val close = Button(this).apply {
            text = "إغلاق"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }

        root.addView(sizeText)
        root.addView(controls)
        root.addView(close)

        val dialog = AlertDialog.Builder(this)
            .setTitle("تكبير/تصغير الخط")
            .setView(root)
            .create()

        fun applyZoom(value: Int) {
            val zoom = value.coerceIn(50, 200)
            currentWebView().settings.textZoom = zoom
            saveFontZoom(zoom)
            sizeText.text = "حجم الخط: ${zoom}%"
        }

        decrease.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue - 10)
        }

        increase.setOnClickListener {
            val currentValue = currentWebView().settings.textZoom.coerceIn(50, 200)
            applyZoom(currentValue + 10)
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // 6) السجل
    private fun showHistoryDialog() {
        val history = BrowserStorage.getHistory(this)
        if (history.isEmpty()) {
            Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        var historyDialog: AlertDialog? = null

        history.forEach { entry ->
            val displayText = entry.title.ifBlank { entry.url }
            val row = TextView(this).apply {
                text = displayText
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 20, 0, 20)
                setOnClickListener {
                    historyDialog?.dismiss()
                    createBrowsingTab(entry.url)
                }
            }
            container.addView(row)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#333333"))
            })
        }

        historyDialog = AlertDialog.Builder(this)
            .setTitle("سجل التصفح")
            .setView(scroll)
            .setPositiveButton("مسح السجل") { _, _ -> BrowserStorage.clearHistory(this) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    // 7) ترجمة إلى العربية
    private fun runTranslate() {
        val tab = currentTab()
        if (tab.isHome) {
            Toast.makeText(this, "لا توجد صفحة لترجمتها", Toast.LENGTH_SHORT).show()
            return
        }

        // Translation remains enabled for this tab; every new page is translated automatically.
        tab.translationEnabled = true
        tab.lastTranslatedUrl = ""
        tab.translationInProgress = false
        Toast.makeText(this, "جاري ترجمة الموقع إلى العربية...", Toast.LENGTH_SHORT).show()
        translateTabPageIfNeeded(tab.webView, tab)
    }

    private fun translateTabPageIfNeeded(webView: WebView, tab: Tab) {
        if (!tab.translationEnabled || tab.translationInProgress) return
        val url = webView.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (url == tab.lastTranslatedUrl) return

        tab.translationInProgress = true
        PageTranslator.translatePage(webView, "ar") { success, message ->
            tab.translationInProgress = false
            if (success) tab.lastTranslatedUrl = url
            if (isActiveTab(webView) && !isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- قائمة التبويبات ----------------

    private fun showTabsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("الصفحات المفتوحة")
            .setView(scroll)
            .setNegativeButton("إغلاق", null)
            .create()

        fun refresh() {
            container.removeAllViews()
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 20)
            }
            val addBtn = Button(this).apply {
                text = "إضافة صفحة جديدة"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    createHomeTab()
                    dialog.dismiss()
                }
            }
            val closeAllBtn = Button(this).apply {
                text = "إغلاق الكل"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    // إغلاق جميع الصفحات مباشرةً دون نافذة تأكيد.
                    closeAllTabs()
                    dialog.dismiss()
                }
            }
            actionsRow.addView(addBtn)
            actionsRow.addView(closeAllBtn)
            container.addView(actionsRow)

            // ستة تبويبات ظاهرة كحد أقصى؛ أي تبويب إضافي يظهر بالتمرير داخل القائمة.
            val density = resources.displayMetrics.density
            val rowHeight = (48 * density).toInt()
            val visibleRows = tabs.size.coerceAtMost(6)
            val fixedContentHeight = (104 * density).toInt()
            scroll.layoutParams = scroll.layoutParams?.apply {
                height = rowHeight * visibleRows + fixedContentHeight
            } ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rowHeight * visibleRows + fixedContentHeight
            )

            tabs.forEachIndexed { index, tab ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // تقليل الفراغات بين الصفحات مع الحفاظ على مساحة مناسبة للضغط.
                    setPadding(0, 0, 0, 0)
                    minimumHeight = rowHeight
                }
                val label = if (tab.isHome) "الصفحة الرئيسية" else tab.title
                val tabUrl = if (tab.isHome) null else tab.webView.url
                val titleText = TextView(this).apply {
                    text = if (index == currentTabIndex) "● $label" else label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        switchToTab(index)
                        dialog.dismiss()
                    }
                }
                val closeBtn = Button(this).apply {
                    text = "✕"
                    setTextColor(Color.WHITE)
                    minHeight = rowHeight
                    setPadding(20, 0, 20, 0)
                    setOnClickListener {
                        closeTab(index)
                        refresh()
                    }
                }
                row.addView(titleText)
                row.addView(closeBtn)
                container.addView(row)

                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Color.parseColor("#333333"))
                })
            }

        }

        refresh()
        dialog.show()
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        view: View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        if (view is WebView) {
            val hitTest = view.getHitTestResult()
            val isImage = hitTest.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE

            if (isImage) {
                // احتفظ بقائمة الصور الخاصة بالتطبيق كما هي.
                val imageUrl = hitTest.extra
                if (!imageUrl.isNullOrBlank() &&
                    (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                    showImageContextMenu(imageUrl, hitTest.type)
                }
                return
            }

            // لا تبتلع قائمة السياق للنصوص. ترك WebView/Android يكمل مسار
            // تحديد النص يسمح بإظهار ActionMode المخصص (نسخ/لصق/تحديد الكل).
            super.onCreateContextMenu(menu, view, menuInfo)
            return
        }
        super.onCreateContextMenu(menu, view, menuInfo)
    }

    // ============= معالج الصور المخصص =============

    private fun showImageContextMenu(imageUrl: String, hitType: Int) {
        val isLinkedImage = hitType == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
        
        val dialog = AlertDialog.Builder(this@BrowserActivity)
            .setTitle("خيارات الصورة")
            .setItems(arrayOf("فتح الصورة", "تنزيل الصورة")) { _, which ->
                when (which) {
                    0 -> openImageFullScreen(imageUrl)
                    1 -> downloadImage(imageUrl)
                }
            }
            .create()
        dialog.show()
    }

    private fun openImageFullScreen(imageUrl: String) {
        if (isFinishing || isDestroyed) return

        // استخدم Dialog عادي بملء الشاشة بدلاً من AlertDialog مخصص؛
        // هذا يمنع تعارض قياسات نافذة AlertDialog مع محتوى الصورة على بعض إصدارات Android.
        val viewer = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = TouchImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "الصورة"
        }

        val closeBtn = android.widget.TextView(this).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                (52 * density).toInt(),
                (52 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, (12 * density).toInt(), (12 * density).toInt(), 0)
            }
            setOnClickListener { viewer.dismiss() }
            contentDescription = "إغلاق الصورة"
            elevation = 10 * density
        }

        container.addView(imageView)
        container.addView(closeBtn)
        viewer.setContentView(container)
        viewer.setOnDismissListener { imageView.setImageDrawable(null) }
        viewer.show()
        viewer.window?.apply {
            setBackgroundDrawableResource(android.R.color.black)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            decorView.setPadding(0, 0, 0, 0)
            setStatusBarColor(Color.BLACK)
            setNavigationBarColor(Color.BLACK)
        }

        loadImageInto(imageView, imageUrl)
    }

    private fun loadImageInto(imageView: ImageView, url: String) {
        // تحميل بسيط باستخدام Thread
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@BrowserActivity, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadImage(imageUrl: String) {
        // استخدام نفس نظام التنزيل الموجود
        val nameFromUrl = imageUrl.substringAfterLast("/").takeIf { it.contains(".") } ?: "image.jpg"
        confirmDownload(
            imageUrl,
            suggestedFileName = nameFromUrl,
            userAgent = currentTab().webView.settings.userAgentString,
            referer = currentTab().webView.url,
            showToast = true
        )
    }

    // ImageView مع دعم التكبير باللمس (Pinch to zoom)
    class TouchImageView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
    ) : ImageView(context, attrs, defStyle) {

        private var scale = 1f
        private var lastScale = 1f
        private var translateX = 0f
        private var translateY = 0f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        private val minScale = 1f
        private val maxScale = 5f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleGestureDetector.onTouchEvent(event)
            
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleGestureDetector.isInProgress) return true
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateX += dx
                    translateY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // إعادة التمركز عند الإفلات
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.save()
            canvas.translate(translateX, translateY)
            canvas.scale(scale, scale, width / 2f, height / 2f)
            super.onDraw(canvas)
            canvas.restore()
        }

        private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val focusX = detector.focusX
                val focusY = detector.focusY
                
                // تعديل الترجمة للحفاظ على نقطة التركيز
                translateX = (translateX * newScale / scale).coerceIn(-width * (newScale - 1), width * (newScale - 1))
                translateY = (translateY * newScale / scale).coerceIn(-height * (newScale - 1), height * (newScale - 1))
                
                scale = newScale
                invalidate()
                return true
            }
        }
    }

    override fun onBackPressed() {
        val tab = currentTab()
        if (!tab.isHome && navigateHistory(tab, -1)) {
            return
        }
        if (!tab.isHome) {
            tab.isHome = true
            switchToTab(currentTabIndex)
            persistTabs()
            return
        }
        finishAffinity()
    }

    override fun onDestroy() {
        // Preserve existing first-party login/session cookies; do not clear the cookie store.
        // Third-party cookies remain disabled for each WebView.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // Cookie persistence errors must not prevent activity cleanup.
        }
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }
}
