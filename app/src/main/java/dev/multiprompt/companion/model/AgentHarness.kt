package dev.multiprompt.companion.model

/**
 * The agent command a new tmux session can start. The binary and its flags are constants, so
 * nothing the user types ever reaches a remote command line: the chosen directory is the only
 * value interpolated into the launch, and it goes through [dev.multiprompt.companion.ssh.TmuxParser.shellQuote].
 *
 * [namePrefix] keeps the created session self-describing, so [AgentKind.detect] classifies it
 * from the tmux name alone on the next refresh.
 */
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