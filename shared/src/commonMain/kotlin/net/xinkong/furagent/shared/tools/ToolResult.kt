package net.xinkong.furagent.shared.tools

data class ToolResult(
    val success: Boolean,
    val output: String
) {
    companion object {
        fun ok(output: String): ToolResult = ToolResult(true, output)
        fun fail(output: String): ToolResult = ToolResult(false, output)
    }
}