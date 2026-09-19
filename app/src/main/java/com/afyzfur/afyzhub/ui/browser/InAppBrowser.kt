package com.afyzfur.afyzhub.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 应用内浏览器。
 *
 * 用系统 WebView（Chromium 内核，由 Google Play 独立更新到最新版），
 * 而非打包独立内核：后者体积 70MB+ 起步，且更新必须随应用发版，
 * 跟不上安全补丁。系统 WebView 两头都占。
 *
 * 来源：聊天气泡里的 Markdown 链接点击后在此打开，替代直接丢给
 * 系统浏览器——读文档读到一半被扔到外部应用，回来要重新找位置。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(
    initialUrl: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // WebView 提前建好并配置：AndroidView 工厂每次重组都跑,
    // remember 防止重复创建与状态丢失
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoomControls(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 站内跳转（a 标签、重定向）留在本 WebView，不弹系统选择器
            webViewClient = WebViewClient()
            // 标题与进度由 Chrome 客户端汇报
            webChromeClient = WebChromeClient()
        }
    }

    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var inputText by rememberSaveable { mutableStateOf(initialUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }

    // WebView 的状态同步回调：URL 变化/导航能力变化时更新 Compose 状态
    fun syncState(view: WebView) {
        currentUrl = view.url ?: currentUrl
        inputText = currentUrl
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    fun load(url: String) {
        val target = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://$url"  // 裸域名默认 https
        }
        inputText = target
        webView.loadUrl(target)
    }

    fun goBackInPage() {
        if (webView.canGoBack()) webView.goBack() else onNavigateBack()
    }

    // 系统返回键优先走页面历史, 走完才退出浏览器
    BackHandler { goBackInPage() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 顶栏: 返回 + 地址输入 + 外部打开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(onClick = { onNavigateBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Close,
                        contentDescription = "关闭浏览器"
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { load(inputText.trim()) }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                )
                IconButton(
                    onClick = {
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl)).let {
                            context.startActivity(it)
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = "在系统浏览器打开"
                    )
                }
            }

            // 导航条: 网页内前进后退
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                IconButton(
                    onClick = { if (webView.canGoBack()) { webView.goBack(); syncState(webView) } },
                    enabled = canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退")
                }
                IconButton(
                    onClick = { if (webView.canGoForward()) { webView.goForward(); syncState(webView) } },
                    enabled = canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "前进")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (progress < 100) "$progress%" else pageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 12.dp, top = 12.dp)
                )
            }

            // 加载进度条
            if (progress < 100) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AndroidView(
                factory = {
                    webView.apply {
                        // 进度与标题经由回调更新状态
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                title?.let { pageTitle = it }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                syncState(view)
                                progress = 100
                            }
                        }
                        loadUrl(initialUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}
