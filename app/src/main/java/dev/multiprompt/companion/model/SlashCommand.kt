package dev.multiprompt.companion.model

object SlashCommand {
    enum class Route { SEND, PLAN, PERMISSIONS, MODEL, UNAVAILABLE }

    private val blanks = charArrayOf(' ', '\t', '\r', '\n')

    private val unsupported = setOf(
        "add-dir", "artifact-capabilities", "artifact-diagramming", "artifacts", "autofix-pr",
        "background", "branch", "btw", "bug", "cd", "chrome", "copy", "design-login", "diff",
        "exit", "export", "feedback", "fork", "help", "hooks", "ide", "install-github-app",
        "install-slack-app", "keybindings", "login", "logout", "memory", "mobile", "passes",
        "plugin", "powerup", "privacy-settings", "radio", "release-notes", "remote-control",
        "remote-env", "resume", "rewind", "sandbox", "scroll-speed", "skills", "slides", "status",
        "statusline", "stickers", "subtask", "tasks", "teleport", "terminal-setup", "theme", "tui",
        "upgrade", "voice", "web-setup", "workflows",
    )

    fun commandName(text: String): String? {
        val trimmed = text.trim { it in blanks }
        if (trimmed.length < 2 || trimmed[0] != '/') return null
        val end = trimmed.indexOfAny(blanks, 1).takeIf { it >= 0 } ?: trimmed.length
        return trimmed.substring(1, end)
    }

    fun route(live: Boolean, text: String): Route {
        if (!live) return Route.SEND
        val command = commandName(text) ?: return Route.SEND
        val hasArguments = text.trim { it in blanks }.substring(command.length + 1).trim { it in blanks }.isNotEmpty()
        return when {
            command == "plan" -> if (hasArguments) Route.UNAVAILABLE else Route.PLAN
            command == "permissions" -> if (hasArguments) Route.UNAVAILABLE else Route.PERMISSIONS
            command == "model" -> Route.MODEL
            command in unsupported -> Route.UNAVAILABLE
            else -> Route.SEND
        }
    }

    fun decide(live: Boolean, text: String, selectedSkill: Boolean): Route =
        if (selectedSkill) Route.SEND else route(live, text)
}
