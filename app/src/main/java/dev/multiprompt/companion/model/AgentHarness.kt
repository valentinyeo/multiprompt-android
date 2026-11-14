package dev.multiprompt.companion.model

enum class AgentHarness(
    val label: String,
    val binary: String,
    val arguments: String,
    val namePrefix: String,
    val agent: AgentKind? = null,
) {
    CLAUDE("Claude Code", "claude", "--dangerously-skip-permissions", "claude", AgentKind.CLAUDE),
    CODEX("Codex", "codex", "--dangerously-bypass-approvals-and-sandbox", "codex", AgentKind.CODEX),
    PI("pi", "pi", "", "pi", AgentKind.PI),
    HAX("Hax", "hax", "", "hax", AgentKind.HAX),
    CURSOR("Cursor", "agent", "", "cursor", AgentKind.CURSOR),
    SHELL("Shell", "", "", "shell"),
    ;

    val startsAnAgent: Boolean get() = binary.isNotBlank()
}
