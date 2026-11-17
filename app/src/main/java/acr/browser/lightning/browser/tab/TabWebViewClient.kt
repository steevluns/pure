package acr.browser.lightning.browser.tab

import acr.browser.lightning.R
import acr.browser.lightning.adblock.AdBlocker
import acr.browser.lightning.adblock.IndependentBlockLists
import acr.browser.lightning.adblock.allowlist.AllowListModel
import acr.browser.lightning.browser.tab.settings.TabSettings
import acr.browser.lightning.concurrency.TabCoroutineScope
import acr.browser.lightning.databinding.DialogAuthRequestBinding
import acr.browser.lightning.databinding.DialogSslWarningBinding
import acr.browser.lightning.di.FaviconCacheDir
import acr.browser.lightning.di.GeneratedHtmlDir
import acr.browser.lightning.extensions.resizeAndShow
import acr.browser.lightning.js.TextReflow
import acr.browser.lightning.log.Logger
import acr.browser.lightning.ssl.SslState
import acr.browser.lightning.ssl.SslWarningPreferences
import acr.browser.lightning.utils.ThreadSafeFileProvider
import acr.browser.lightning.utils.isSpecialUrl
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.LayoutInflater
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * A [WebViewClient] that supports the tab adaptation.
 */
class TabWebViewClient @AssistedInject constructor(
    private val adBlocker: Deferred<@JvmSuppressWildcards AdBlocker>,
    private val independentBlockLists: IndependentBlockLists,
    private val allowListModel: AllowListModel,
    private val urlHandler: UrlHandler,
    private val userPreferencesDataStore: acr.browser.lightning.preference.UserPreferencesDataStore,
    @Assisted private val headers: Map<String, String>,
    private val sslWarningPreferences: SslWarningPreferences,
    private val textReflow: TextReflow,
    private val logger: Logger,
    @FaviconCacheDir private val faviconCacheDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @GeneratedHtmlDir private val generatedHtmlDirThreadSafeFileProvider: ThreadSafeFileProvider,
    @Assisted("cache") private val cacheStoragePathHandler: InternalStoragePathHandler,
    @Assisted("files") private val filesStoragePathHandler: InternalStoragePathHandler,
    @Assisted private val tabCoroutineScope: TabCoroutineScope,
    @Assisted private val tabSettings: TabSettings,
) : WebViewClient() {

    /**
     * The factory for constructing the client.
     */
    @AssistedFactory
    interface Factory {

        /**
         * Create the client.
         */
        fun create(
            headers: Map<String, String>,
            @Assisted("cache") cacheStoragePathHandler: InternalStoragePathHandler,
            @Assisted("files") filesStoragePathHandler: InternalStoragePathHandler,
            tabCoroutineScope: TabCoroutineScope,
            tabSettings: TabSettings,
        ): TabWebViewClient
    }

    private val cache by lazy {
        runBlocking {
            faviconCacheDirThreadSafeFileProvider.file()
        }
    }

    private val files by lazy {
        runBlocking {
            generatedHtmlDirThreadSafeFileProvider.file()
        }
    }

    /**
     * Emits changes to the current URL.
     */
    val urlSharedFlow: MutableSharedFlow<String> = MutableSharedFlow()

    /** Emits the full URL currently awaiting popup approval. */
    val popupUrlSharedFlow: MutableSharedFlow<String> = MutableSharedFlow()

    /** Emits a request to close a popup rejected by the user. */
    val closeWindowSharedFlow: MutableSharedFlow<Unit> = MutableSharedFlow()

    /**
     * The current SSL state of the page.
     */
    val sslStateFlow: MutableStateFlow<SslState> = MutableStateFlow(SslState.None)

    /**
     * Emits changes to the can go back state of the browser.
     */
    val goBackSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emits changes to the can go forward state of the browser.
     */
    val goForwardSharedFlow: MutableSharedFlow<Boolean> = MutableSharedFlow()

    /**
     * Emit when the tab has started loading a new page
     */
    val startedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * Emit when the tab has finished rendering its content.
     */
    val finishedSharedFlow = MutableSharedFlow<Unit>()

    /**
     * The latest search query entered by the user, or the latest loaded URL, whichever event
     * happened later.
     */
    var searchQuery: String = ""

    /**
     * The text selection in the search query, either the start and end of the selection if the
     * values are different, or the cursor position if they are the same.
     */
    var searchQuerySelection: Pair<Int, Int> = Pair(0, 0)

    private var currentUrl: String = ""
    private var isReflowRunning: Boolean = false
    private var zoomScale: Float = 0.0F
    private var urlWithSslError: String? = null
    private var popupConfirmationEnabled: Boolean = false
    private var popupNavigationApproved: Boolean = false

    fun enablePopupConfirmation() {
        popupConfirmationEnabled = true
    }

    // The built-in ad blocker is controlled only by its own master switch.
    // Independent subscriptions are checked separately in shouldInterceptRequest.
    private fun shouldBlockRequest(requestUri: Uri): Boolean =
        runBlocking {
            userPreferencesDataStore.adBlockEnabled.get() && adBlocker.await().isAd(requestUri)
        }

    private fun updateUrl(url: String) {
        searchQuery = if (!url.isSpecialUrl()) {
            url
        } else {
            ""
        }
        searchQuerySelection = Pair(0, searchQuery.length)
        currentUrl = url
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        updateUrl(url)
        tabCoroutineScope.launch {
            urlSharedFlow.emit(url)
            goBackSharedFlow.emit(view.canGoBack())
            goForwardSharedFlow.emit(view.canGoForward())
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val shouldAskForPopupApproval = popupConfirmationEnabled &&
            runBlocking { userPreferencesDataStore.popupConfirmationEnabled.get() }
        if (shouldAskForPopupApproval && !popupNavigationApproved &&
            url != "about:blank" && url != "about:srcdoc"
        ) {
            view.stopLoading()
            updateUrl(url)
            tabCoroutineScope.launch {
                // Keep the complete URL visible in the popup tab entry even before approval.
                urlSharedFlow.emit(url)
                popupUrlSharedFlow.emit(url)
            }
            val urlView = android.widget.TextView(view.context).apply {
                text = url
                contentDescription = url
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 14f
                setPadding(24, 12, 24, 12)
            }
            AlertDialog.Builder(view.context).apply {
                setTitle(R.string.title_warning)
                setView(urlView)
                setCancelable(false)
                setPositiveButton(R.string.action_allow) { _, _ ->
                    popupNavigationApproved = true
                    view.loadUrl(url)
                }
                setNegativeButton(R.string.action_dont_allow) { _, _ ->
                    popupNavigationApproved = false
                    view.stopLoading()
                    // A rejected popup must not remain as an empty/new tab.
                    tabCoroutineScope.launch {
                        closeWindowSharedFlow.emit(Unit)
                    }
                }
                setOnCancelListener {
                    popupNavigationApproved = false
                    view.stopLoading()
                    // Treat dismissing the confirmation exactly like rejecting it.
                    tabCoroutineScope.launch {
                        closeWindowSharedFlow.emit(Unit)
                    }
                }
            }.resizeAndShow()
            return
        }
        updateUrl(url)
        tabCoroutineScope.launch {
            startedSharedFlow.emit(Unit)
            urlSharedFlow.emit(url)
            if (urlWithSslError != url) {
                urlWithSslError = null
                val sslState = if (URLUtil.isHttpsUrl(url)) {
                    SslState.Valid
                } else {
                    SslState.None
                }
                sslStateFlow.emit(sslState)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        applyHideMedia(view)
        tabCoroutineScope.launch {
            urlSharedFlow.emit(url)
            goBackSharedFlow.emit(view.canGoBack())
            goForwardSharedFlow.emit(view.canGoForward())
        }
        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                tabCoroutineScope.launch {
                    finishedSharedFlow.emit(Unit)
                }
            }
        })
    }


    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && tabSettings.textReflowEnabled) {
            if (isReflowRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isReflowRunning) {
                isReflowRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflow.provideJs()) { isReflowRunning = false }
                }, 100)
            }

        }
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            val dialogView = DialogAuthRequestBinding.inflate(LayoutInflater.from(context))

            val realmLabel = dialogView.authRequestRealmTextview
            val name = dialogView.authRequestUsernameEdittext
            val password = dialogView.authRequestPasswordEdittext

            realmLabel.text = context.getString(R.string.label_realm, realm)

            setView(dialogView.root)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        val context = view.context
        AlertDialog.Builder(context).apply {
            setTitle(context.getString(R.string.title_form_resubmission))
            setMessage(context.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        val context = webView.context
        val warningUrl = error.url ?: webView.url
        urlWithSslError = warningUrl

        // A previous explicit approval applies only to this host and this app session.
        if (sslWarningPreferences.recallBehaviorForDomain(warningUrl) ==
            SslWarningPreferences.Behavior.PROCEED
        ) {
            handler.proceed()
            return
        }

        tabCoroutineScope.launch {
            val sslState = SslState.Invalid(error)
            sslStateFlow.emit(sslState)
        }

        val errorCodeMessageCodes = error.getAllSslErrorMessageCodes()
        val stringBuilder = StringBuilder()
        for (messageCode in errorCodeMessageCodes) {
            stringBuilder.append(" - ").append(context.getString(messageCode)).append('\n')
        }
        val alertMessage =
            context.getString(R.string.message_insecure_connection, stringBuilder.toString())

        AlertDialog.Builder(context).apply {
            val dialogView = DialogSslWarningBinding.inflate(LayoutInflater.from(context))
            dialogView.checkBoxDontAskAgain.visibility = android.view.View.VISIBLE
            dialogView.checkBoxDontAskAgain.isChecked = false
            setTitle(R.string.title_warning)
            setMessage(alertMessage)
            setCancelable(false)
            setView(dialogView.root)
            setOnCancelListener { handler.cancel() }
            setPositiveButton(context.getString(R.string.action_yes)) { _, _ ->
                if (dialogView.checkBoxDontAskAgain.isChecked && !warningUrl.isNullOrBlank()) {
                    sslWarningPreferences.rememberBehaviorForDomain(
                        warningUrl,
                        SslWarningPreferences.Behavior.PROCEED
                    )
                }
                handler.proceed()
            }
            setNegativeButton(context.getString(R.string.action_no)) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val targetUrl = request.url.toString()
        val handledByExternalApp = urlHandler.shouldOverrideLoading(
            tabSettings.openAvailableAppsEnabled,
            view,
            targetUrl,
            headers
        )
        if (handledByExternalApp) return true

        if (request.isForMainFrame && request.url.scheme.equals("http", ignoreCase = true)) {
            AlertDialog.Builder(view.context).apply {
                setTitle("تحذير: اتصال غير مشفّر")
                setMessage(
                    "هذا الموقع يستخدم HTTP ولا يشفّر الاتصال. لا تدخل كلمات مرور أو بيانات شخصية.\n\n$targetUrl\n\nهل تريد المتابعة رغم المخاطر؟"
                )
                setCancelable(false)
                setPositiveButton("متابعة") { _, _ ->
                    view.loadUrl(targetUrl, headers)
                }
                setNegativeButton("رفض") { _, _ ->
                    view.stopLoading()
                }
            }.resizeAndShow()
            return true
        }

        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Apply the page allowlist consistently to both the built-in hosts list and
        // independently subscribed lists. Independent lists have their own switches.
        val pageAllowsAds = allowListModel.isUrlAllowedAds(currentUrl)
        if (!pageAllowsAds &&
            (shouldBlockRequest(request.url) || independentBlockLists.isBlocked(request.url))
        ) {
            val empty = ByteArrayInputStream(emptyResponseByteArray)
            return WebResourceResponse(BLOCKED_RESPONSE_MIME_TYPE, BLOCKED_RESPONSE_ENCODING, empty)
        }
        return if (request.url.path?.startsWith(files.path) == true) {
            filesStoragePathHandler.handle(request.url.path!!.substring(files.path.length))
        } else if (request.url.path?.startsWith(cache.path) == true) {
            cacheStoragePathHandler.handle(request.url.path!!.substring(cache.path.length))
        } else {
            super.shouldInterceptRequest(view, request)
        }
    }


    private fun applyHideMedia(webView: WebView) {
        val hideImages = runBlocking { userPreferencesDataStore.blockImagesEnabled.get() }
        val hideVideos = runBlocking { userPreferencesDataStore.blockVideosEnabled.get() }
        if (!hideImages && !hideVideos) {
            webView.evaluateJavascript("document.getElementById('__lightning_hide_media__')?.remove();", null)
            return
        }
        val imageCss = if (hideImages) """
            img, picture, svg, canvas, image, [role="img"], input[type="image"],
            video[poster], [style*="background-image"], [style*="background:"] {
                visibility:hidden !important; background-image:none !important;
                -webkit-mask-image:none !important; mask-image:none !important;
            }
            * { background-image:none !important; }
        """ else ""
        val videoCss = if (hideVideos) """
            video, video *, video[poster],
            iframe.__lightning_video_iframe, object.__lightning_video_object,
            embed.__lightning_video_embed, .__lightning_video_ad_container {
                visibility:hidden !important; opacity:0 !important;
            }
        """ else ""
        val css = org.json.JSONObject.quote(imageCss + "\n" + videoCss)
        val js = """
            (function(){
              var id='__lightning_hide_media__', css=$css;
              function isVideo(u,t){
                u=String(u||'').toLowerCase(); t=String(t||'').toLowerCase();
                return t.indexOf('video/')===0 ||
                  /\.(mp4|m4v|webm|ogv|ogg|mov|avi|mkv|flv|wmv|mpg|mpeg|3gp|m3u8|mpd)(?:$|[?#&])/.test(u) ||
                  /(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com|twitch\.tv|fb\.watch|bilibili|streamable|wistia|jwplayer)/.test(u);
              }
              function apply(root){
                var head=document.head||document.documentElement; if(!head)return;
                var style=document.getElementById(id);
                if(!style){style=document.createElement('style');style.id=id;head.appendChild(style);}
                style.textContent=css;
                var scope=root&&root.querySelectorAll?root:document;
                scope.querySelectorAll('iframe,object,embed').forEach(function(el){
                  var src=el.getAttribute('src')||el.getAttribute('data')||el.getAttribute('data-src')||'';
                  if(isVideo(src,el.getAttribute('type')) ||
                    (el.tagName==='IFRAME' && /video|player|مشغل/i.test(el.getAttribute('title')||''))){
                    el.classList.add('__lightning_video_'+el.tagName.toLowerCase());
                  }
                });
              }
              apply(document);
              if(!window.__lightningMediaObserver && document.documentElement){
                window.__lightningMediaObserver=new MutationObserver(function(records){
                  records.forEach(function(r){(r.addedNodes||[]).forEach(function(n){if(n.nodeType===1)apply(n);});});
                });
                window.__lightningMediaObserver.observe(document.documentElement,{childList:true,subtree:true});
              }
            })();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun SslError.getAllSslErrorMessageCodes(): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)

        if (hasError(SslError.SSL_DATE_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        }
        if (hasError(SslError.SSL_EXPIRED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_expired)
        }
        if (hasError(SslError.SSL_IDMISMATCH)) {
            errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        }
        if (hasError(SslError.SSL_NOTYETVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        }
        if (hasError(SslError.SSL_UNTRUSTED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        }
        if (hasError(SslError.SSL_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        return errorCodeMessageCodes
    }

    companion object {
        private const val TAG = "TabWebViewClient"

        private val emptyResponseByteArray: ByteArray = byteArrayOf()

        private const val BLOCKED_RESPONSE_MIME_TYPE = "text/plain"
        private const val BLOCKED_RESPONSE_ENCODING = "utf-8"
    }
}
