package net.xinkong.furagent.shared

/**
 * 思考强度。
 *
 * 取代旧的 `enableThinking: Boolean`。四档：
 *  - [OFF]  关闭思考
 *  - [LOW]  快速（reasoning_effort = low）
 *  - [HIGH] 标准（reasoning_effort = high，DeepSeek 官方默认）
 *  - [MAX]  深度（reasoning_effort = max）
 *
 * 【为什么用枚举而不是 Int】
 *  - 序列化到 SharedPreferences / JSON 时用 [id] 字符串，可读、可迁移
 *  - 旧数据 `enable_thinking=true` 迁移成 [HIGH]，`false` 迁移成 [OFF]
 *
 * 【厂商差异】
 * 各厂商支持程度不同，具体映射见 `applyEnableThinking`。
 * 不支持的档位会降级到最接近的可用值，不会报错。
 */
enum class ThinkingLevel(val id: String, val displayName: String) {
    OFF("off", "关闭"),
    LOW("low", "快速"),
    HIGH("high", "标准"),
    MAX("max", "深度");

    /** 是否开启思考 */
    val enabled: Boolean get() = this != OFF

    companion object {
        /** 默认关闭（与旧行为一致） */
        val DEFAULT: ThinkingLevel = OFF

        /** 按 id 解析，未知值返回 [DEFAULT]。 */
        fun fromId(id: String?): ThinkingLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT

        /**
         * 从旧布尔值迁移。
         * true → [HIGH]（旧代码硬编码 high），false → [OFF]
         */
        fun fromLegacyBoolean(enabled: Boolean): ThinkingLevel =
            if (enabled) HIGH else OFF

        /** UI 顺序：关闭 / 快速 / 标准 / 深度 */
        fun uiOrder(): List<ThinkingLevel> = listOf(OFF, LOW, HIGH, MAX)
    }
}
