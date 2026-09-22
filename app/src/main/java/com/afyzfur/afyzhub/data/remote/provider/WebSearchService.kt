package com.afyzfur.afyzhub.data.remote.provider

import com.afyzfur.afyzhub.data.log.RequestLogContext
import com.afyzfur.afyzhub.data.settings.AppSettings

/**
 * 应用层网络搜索。
 *
 * 目标：任何模型（无论提供商是否支持服务端联网）都能获取网络信息。
 * 原理：提示词要求模型在需要信息时输出 `<search>查询词</search>`，
 * 发送流程检测到该标签后调用本服务抓取搜索结果，把结果作为
 * 上下文再次请求，模型基于结果作答。
 *
 * 搜索源用 DuckDuckGo 的 HTML 版（html.duckduckgo.com）：
 * 无需 API Key、无请求频次的注册流程，返回的简化页面用正则即可
 * 提取标题、摘要与链接。稳定性依赖第三方页面结构，但作为
 * "聊天下文的补充信息"够用——失败时静默降级为无搜索继续回答。
 */
/**
 * 联网搜索引擎。
 *
 * 默认 Bing：对移动 UA 最宽容且无地域墙。百度作为国内网络
 * 环境的可靠备选，Google 需要设备本身可达。选择存于设置，
 * 搜索时若所选引擎失败会自动按 BING → BAIDU → GOOGLE 降级。
 */
enum class SearchEngine(val id: String, val label: String) {
    BING("bing", "Bing"),
    BAIDU("baidu", "百度"),
    GOOGLE("google", "Google");
    companion object {
        val DEFAULT = BING
        fun fromId(id: String?): SearchEngine =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

class WebSearchService(
    private val transport: Transport
) {

    /** 一次搜索的结果条目 */
    data class Result(
        val title: String,
        val snippet: String,
        val url: String,
        /** 来源站点域名，如 "zhihu.com"，用于 favicon 与署名 */
        val site: String = ""
    )

    /**
     * 执行搜索，返回前 [maxResults] 条结果。
     *
     * 任何异常都返回空列表：搜索是增强能力，失败不该让整条消息
     * 发送失败——模型会按无搜索结果继续回答（通常会说明信息不足）。
     */
    /**
     * 执行搜索，返回前 [maxResults] 条结果。
     *
     * 先按用户设置的引擎查，空结果或异常时按固定顺序降级到
     * 其余引擎——各家反爬策略不同，单一引擎可靠性不够。
     * 全部失败才返回空列表：搜索是增强能力，失败不该让整条
     * 消息发送失败，模型会按无结果路径兜底回答。
     */
    suspend fun search(query: String, maxResults: Int = 5, engineId: String? = null): List<Result> {
        if (query.isBlank()) return emptyList()
        val preferred = SearchEngine.fromId(engineId)
        val order = listOf(preferred) + SearchEngine.entries.filter { it != preferred }
        for (engine in order) {
            val results = try {
                when (engine) {
                    SearchEngine.BING -> searchBing(query, maxResults)
                    SearchEngine.BAIDU -> searchBaidu(query, maxResults)
                    SearchEngine.GOOGLE -> searchGoogle(query, maxResults)
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }
    private suspend fun searchBing(query: String, maxResults: Int): List<Result> {
        // RSS 输出而非 HTML：结构稳定多年、无广告与 SEO 垃圾，
        // 每条 item 固定为 title/link/description 三件套
        val xml = transport.getForText(
            baseUrl = "https://www.bing.com",
            path = "/search",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9"
            ),
            query = mapOf("q" to query, "format" to "rss", "count" to maxResults.toString()),
            logContext = RequestLogContext(provider = "web-search", model = "bing-rss")
        )
        return parseBingRss(xml, maxResults)
    }
    private suspend fun searchBaidu(query: String, maxResults: Int): List<Result> {
        val html = transport.getForText(
            baseUrl = "https://www.baidu.com",
            path = "/s",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml"
            ),
            query = mapOf("wd" to query, "rn" to maxResults.toString()),
            logContext = RequestLogContext(provider = "web-search", model = "baidu")
        )
        return parseBaidu(html, maxResults)
    }
    private suspend fun searchGoogle(query: String, maxResults: Int): List<Result> {
        val html = transport.getForText(
            baseUrl = "https://www.google.com",
            path = "/search",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9"
            ),
            query = mapOf("q" to query, "num" to maxResults.toString()),
            logContext = RequestLogContext(provider = "web-search", model = "google")
        )
        return parseGoogle(html, maxResults)
    }

    /**
     * 解析 DuckDuckGo HTML 版结果页。
     *
     * 结果条目的结构稳定多年：
     * - 标题与链接在 `<a class="result__a" href="...">标题</a>`
     * - 摘要在 `<a class="result__snippet">摘要</a>`
     * 两者按出现顺序一一对应，各自正则提取后 zip。
     */
    private fun parseResults(html: String, maxResults: Int): List<Result> {
        val linkPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetPattern = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val links = linkPattern.findAll(html)
            .map { m ->
                Result(
                    title = stripTags(m.groupValues[2]),
                    snippet = "",
                    url = cleanUrl(m.groupValues[1]),
                    site = siteOf(cleanUrl(m.groupValues[1]))
                )
            }
            .toList()

        val snippets = snippetPattern.findAll(html)
            .map { stripTags(it.groupValues[1]) }
            .toList()

        return links.zip(snippets) { link, snippet ->
            link.copy(snippet = snippet)
        }.take(maxResults)
    }

    /**
     * 解析百度 PC 版结果页。
     *
     * 结果标题与链接在 h3 > a 内，链接多为百度跳转格式
     * (/link?url=)，点击后由百度 302 到真址——直接存跳转
     * 链接即可，内置浏览器会跟随重定向。
     */
    private fun parseBaidu(html: String, maxResults: Int): List<Result> {
        val out = mutableListOf<Result>()
        val blockPattern = Regex(
            "<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in blockPattern.findAll(html)) {
            val url = m.groupValues[1]
            if (url.startsWith("http") || url.startsWith("/link")) {
                val full = if (url.startsWith("/")) "https://www.baidu.com" + url else url
                out += Result(stripTags(m.groupValues[2]), "", full, siteOf(full))
                if (out.size >= maxResults) break
            }
        }
        return out
    }

    /**
     * 解析 Google 结果页。
     *
     * 结果块以 div.g 开始，标题在第一个 h3，链接取块内第一个
     * 非 google 域的 http 链接。
     */
    private fun parseGoogle(html: String, maxResults: Int): List<Result> {
        val blocks = html.split("<div class=\"g\"").drop(1)
        val out = mutableListOf<Result>()
        for (b in blocks) {
            val title = Regex("<h3[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
                .find(b)?.groupValues?.get(1) ?: continue
            val url = Regex("href=\"(https?://[^\"]+)\"").findAll(b)
                .map { it.groupValues[1] }
                .firstOrNull { !it.contains("google.") && !it.contains("gstatic.") }
                ?: continue
            out += Result(stripTags(title), "", url, siteOf(url))
            if (out.size >= maxResults) break
        }
        return out
    }

    /**
     * 解析 Bing 移动版结果页。
     *
     * 每条结果在 li.b_algo 块内: 标题与链接在第一个 a 标签,
     * 摘要在第一个 p 标签。按块切分加通用标签匹配,
     * 对页面局部结构调整不敏感。
     */
    /**
     * 解析 Bing RSS 输出。
     *
     * 每条结果是一个 `<item>` 块：title / link / description 各一，
     * 结构由 Bing 官方保证，不受页面改版或广告投放影响。
     */
    private fun parseBingRss(xml: String, maxResults: Int): List<Result> {
        val items = xml.split("<item>").drop(1)
        val out = mutableListOf<Result>()
        for (item in items) {
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(item)?.groupValues?.get(1) ?: continue
            val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
                .find(item)?.groupValues?.get(1) ?: continue
            val desc = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
                .find(item)?.groupValues?.get(1) ?: ""
            val url = stripTags(link)
            out += Result(
                title = stripTags(title),
                snippet = stripTags(desc),
                url = url,
                site = siteOf(url)
            )
            if (out.size >= maxResults) break
        }
        return out
    }

    /** 从 url 提取站点域名（去 www.），favicon 与署名用 */
    private fun siteOf(url: String): String =
        Regex("https?://(?:www\\.)?([^/]+)").find(url)?.groupValues?.get(1) ?: ""

    private fun parseBing(html: String, maxResults: Int): List<Result> {
        val blocks = html.split("<li class=\"b_algo\"").drop(1)
        val out = mutableListOf<Result>()
        for (b in blocks) {
            val url = Regex("href=\"([^\"]+)\"").find(b)?.groupValues?.get(1)
                ?: continue
            val title = Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(b)?.groupValues?.get(1) ?: continue
            val snippet = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                .find(b)?.groupValues?.get(1) ?: ""
            out += Result(stripTags(title), stripTags(snippet), url, siteOf(url))
            if (out.size >= maxResults) break
        }
        return out
    }

    /** 去除 HTML 标签与实体，压缩空白 */
    private fun stripTags(raw: String): String = raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * DuckDuckGo 的链接是跳转格式 `//duckduckgo.com/l/?uddg=<encoded>`，
     * 解出真实地址；已是直链则原样返回。
     */
    private fun cleanUrl(raw: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(raw)?.groupValues?.get(1)
            ?: return if (raw.startsWith("//")) "https:$raw" else raw
        return java.net.URLDecoder.decode(uddg, "UTF-8")
    }

    companion object {
        /**
         * 搜索指令，注入到系统提示词末尾。
         *
         * 只在联网开启且提供商无原生搜索时注入（Gemini 的服务端
         * grounding 质量更高，走原生）。措辞要点：
         * - 明确"仅在需要时"——避免每个问题都触发一轮额外请求
         * - 要求查询词精炼——它就是搜索框里的输入
         */
        fun instruction(): String = """
            |你可以使用网络搜索。当且仅当回答需要你无法确定的实时或具体信息时，
            |先输出一行 <web_search>关键词</web_search>（标签内是你会输入搜索引擎的关键词，
            |例如 <web_search>2025 中秋</web_search>），然后立即停止输出，不要输出其他任何内容。
            |系统检测到该标签后会执行搜索并把结果提供给你，你再基于结果继续回答。
            |如果已有足够信息回答，直接回答，不要输出该标签。
        """.trimMargin()

        /** 从模型输出中提取搜索查询词 */
        fun extractQuery(reply: String): String? {
            val m = Regex("""<web_search>(.*?)</web_search>""", RegexOption.DOT_MATCHES_ALL)
                .find(reply)
                ?: Regex("""<web_search>(.*)""", RegexOption.DOT_MATCHES_ALL)
                    .find(reply)
            return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }

        /** 把搜索结果格式化为注入上下文的文本 */
        fun formatResults(results: List<Result>): String {
            if (results.isEmpty()) {
                return "（搜索没有返回结果。请基于已有知识回答，并向用户说明信息可能过时。）"
            }
            return results.joinToString("\n\n") { r ->
                "${r.title}\n${r.snippet}\n来源: ${r.url}"
            }
        }
    }
}
