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
class WebSearchService(
    private val transport: Transport
) {

    /** 一次搜索的结果条目 */
    data class Result(
        val title: String,
        val snippet: String,
        val url: String
    )

    /**
     * 执行搜索，返回前 [maxResults] 条结果。
     *
     * 任何异常都返回空列表：搜索是增强能力，失败不该让整条消息
     * 发送失败——模型会按无搜索结果继续回答（通常会说明信息不足）。
     */
    suspend fun search(query: String, maxResults: Int = 5): List<Result> {
        if (query.isBlank()) return emptyList()
        return try {
            val html = transport.getForText(
                baseUrl = "https://html.duckduckgo.com",
                path = "/html/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
                ),
                query = mapOf("q" to query),
                logContext = RequestLogContext(
                    provider = "web-search",
                    model = "duckduckgo"
                )
            )
            val first = parseResults(html, maxResults)
            // DuckDuckGo 被反爬或网络受限时结果为空, 换 Bing 再试。
            // 两家都失败才返回空, 让模型走无结果兜底路径
            if (first.isNotEmpty()) return first
            val bingHtml = transport.getForText(
                baseUrl = "https://www.bing.com",
                path = "/search",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36"
                ),
                query = mapOf("q" to query),
                logContext = RequestLogContext(
                    provider = "web-search",
                    model = "bing"
                )
            )
            return parseBing(bingHtml, maxResults)
        } catch (_: Exception) {
            emptyList()
        }
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
                    url = cleanUrl(m.groupValues[1])
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
     * 解析 Bing 移动版结果页。
     *
     * 每条结果在 li.b_algo 块内: 标题与链接在第一个 a 标签,
     * 摘要在第一个 p 标签。按块切分加通用标签匹配,
     * 对页面局部结构调整不敏感。
     */
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
            out += Result(stripTags(title), stripTags(snippet), url)
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
            |先输出一行 `<search>搜索词</search>`（搜索词为你会输入搜索引擎的
            |关键词），然后停止输出。系统会执行搜索并把结果提供给你，你再基于
            |结果继续回答。如果已有足够信息，直接回答，不要使用该标签。
        """.trimMargin()

        /** 从模型输出中提取搜索查询词 */
        fun extractQuery(reply: String): String? {
            val m = Regex("""<search>(.*?)</search>""", RegexOption.DOT_MATCHES_ALL)
                .find(reply)
                ?: Regex("""<search>(.*)""", RegexOption.DOT_MATCHES_ALL)
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
