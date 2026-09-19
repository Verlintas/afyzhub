package com.afyzfur.afyzhub.domain.model

/**
 * 思考程度。
 *
 * 各家的参数名与取值都不同：OpenAI 系用 `reasoning_effort`
 * 取 low/medium/high，Anthropic 用 `thinking.budget_tokens` 给
 * token 预算，Gemini 用 `thinkingConfig.thinkingBudget`。这里用一个
 * 与厂商无关的档位，由各 client 自行翻译成对应参数。
 *
 * [OFF] 不是"设为最低"而是完全不发这个参数：不支持思考的模型收到
 * 未知字段可能直接报错，而多数中转对未知参数的处理并不宽容。
 */
enum class ThinkingEffort(
    val id: String,
    val label: String,
    /** OpenAI 系的 reasoning_effort 取值，OFF 时为 null */
    val openAiEffort: String?,
    /** Anthropic 与 Gemini 的 token 预算，OFF 时为 null */
    val tokenBudget: Int?
) {
    OFF(id = "off", label = "关闭", openAiEffort = null, tokenBudget = null),
    LOW(id = "low", label = "低", openAiEffort = "low", tokenBudget = 1024),
    MEDIUM(id = "medium", label = "中", openAiEffort = "medium", tokenBudget = 4096),
    HIGH(id = "high", label = "高", openAiEffort = "high", tokenBudget = 16384);

    /** 是否需要在请求里带上思考参数 */
    val enabled: Boolean get() = this != OFF

    /**
     * 该档位是否对指定模型可用。
     *
     * 只关心"开启思考"的档位（低/中/高）：多数模型没有思考能力，
     * 对它们发思考参数轻则被忽略、重则直接 400。OFF 对任何模型
     * 都合法——不发参数或显式关闭，无兼容风险。
     *
     * 判定按提供商与模型名前缀做白名单，覆盖各家已知的思考模型：
     * - OpenAI 系: o 系列(o1/o3/o4)与 gpt-5 系
     * - Anthropic: claude-3-7 及之后
     * - Gemini: 2.5 系(flash/pro)
     * 白名单之外按不支持处理——不发思考参数最多"没生效"，发了被
     * 拒绝则整条消息失败，宁可保守。新模型上市后在此追加即可。
     */
    fun supportsModel(provider: AiProvider, model: String): Boolean {
        if (this == OFF) return true
        val m = model.trim().lowercase()
        return when (provider) {
            AiProvider.OPENAI -> m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
                m.startsWith("gpt-5") || m.contains("-o1") || m.contains("-o3") || m.contains("-o4")
            AiProvider.ANTHROPIC -> m.startsWith("claude-3-7") || m.startsWith("claude-4") ||
                m.startsWith("claude-sonnet-4") || m.startsWith("claude-opus-4") ||
                m.startsWith("claude-haiku-4")
            AiProvider.GEMINI -> m.contains("gemini-2.5")
        }
    }

    /**
     * Anthropic 在开启思考时所需的 max_tokens 下限。
     *
     * Claude 要求 `max_tokens` 严格大于 `thinking.budget_tokens`，
     * 否则直接返回 400。而 budget 是从 max_tokens 里扣的额度，
     * 不是额外配给——如果只把 max_tokens 抬到刚好超过 budget，
     * 留给正文的空间就只剩几个 token，回答会被立刻截断。
     * 因此在预算之外再留出一份正文空间。
     */
    fun anthropicMaxTokens(default: Int): Int {
        val budget = tokenBudget ?: return default
        return maxOf(default, budget + default)
    }

    companion object {
        /**
         * 默认不开。
         *
         * 思考会显著增加耗时与费用，且不是所有模型都支持——
         * 让用户主动开启比默认打开再让人困惑于"为什么变慢了"更好。
         */
        val DEFAULT = OFF

        fun fromId(id: String?): ThinkingEffort =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /** 按档位循环切换，用于输入栏那个一键轮换的按钮 */
        fun next(current: ThinkingEffort): ThinkingEffort =
            entries[(entries.indexOf(current) + 1) % entries.size]
    }
}
