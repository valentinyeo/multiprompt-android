package dev.multiprompt.companion.model

enum class AgentHarness(
    val label: String,
    val binary: String,
    val arguments: String,
    val namePrefix: String,
) {
    CLAUDE("Claude Code", "claude", "--dangerously-skip-permissions", "claude"),
    CODEX("Codex", "codex", "--dangerously-bypass-approvals-and-sandbox", "codex"),
    PI("pi", "pi", "", "pi"),
    HAX("Hax", "hax", "", "hax"),
    SHELL("Shell", "", "", "shell"),
    ;

    val startsAnAgent: Boolean get() = binary.isNotBlank()
}