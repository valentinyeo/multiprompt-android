package dev.multiprompt.companion.skills

import org.json.JSONArray

data class NativeSkill(val name: String, val description: String)

data class SlashToken(val start: Int, val end: Int, val text: String)

data class SkillSpan(val start: Int, val end: Int, val name: String)

data class SkillInsertion(val text: String, val cursor: Int)

object NativeSkills {
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_./:-]*")

    fun parse(json: String): List<NativeSkill> {
        val rows = JSONArray(json)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val name = row.optString("name")
                if (!NAME.matches(name)) continue
                add(NativeSkill(name, row.optString("description")))
            }
        }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    fun token(text: String, cursor: Int): SlashToken? {
        if (cursor !in 1..text.length) return null
        var start = cursor
        while (start > 0 && !wordBreak(text[start - 1])) start--
        if (start == cursor || text[start] != '/') return null
        var end = start + 1
        while (end < text.length && !wordBreak(text[end])) end++
        if (pathWord(text.substring(start + 1, end))) return null
        return SlashToken(start, cursor, text.substring(start + 1, cursor))
    }

    fun query(text: String, cursor: Int): String? = token(text, cursor)?.text

    fun spans(text: String, skills: List<NativeSkill>): List<SkillSpan> {
        if (text.isEmpty() || skills.isEmpty()) return emptyList()
        val names = skills.map { it.name }.filter { it.isNotEmpty() && !pathWord(it) }.distinct().sortedByDescending { it.length }
        if (names.isEmpty()) return emptyList()
        val found = ArrayList<SkillSpan>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '/' && (index == 0 || wordBreak(text[index - 1]))) {
                val name = names.firstOrNull { candidate ->
                    val end = index + 1 + candidate.length
                    end <= text.length && text.startsWith(candidate, index + 1) && (end == text.length || wordBreak(text[end]))
                }
                if (name != null) {
                    val end = index + 1 + name.length
                    found.add(SkillSpan(index, end, name))
                    index = end
                    continue
                }
            }
            index++
        }
        return found
    }

    fun matches(skills: List<NativeSkill>, query: String): List<NativeSkill> = skills.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }

    fun catalogue(skills: List<NativeSkill>): List<NativeSkill> =
        (skills + claudeCommands).distinctBy { it.name.lowercase() }.filter { !pathWord(it.name) }

    val claudeCommands: List<NativeSkill> = listOf(
        NativeSkill("add-dir", "Add a new working directory"),
        NativeSkill("advisor", "Let Claude consult a stronger model at key moments"),
        NativeSkill("artifact-capabilities", "Runtime capabilities a published Artifact"),
        NativeSkill("artifact-diagramming", "Diagramming know-how for Artifacts - when a"),
        NativeSkill("artifacts", "Browse your published and shared artifacts"),
        NativeSkill("auto-mode-setup", "Teach auto mode about your environment, plus optional rule tweaks"),
        NativeSkill("autocompact", "Configure the auto-compact window size"),
        NativeSkill("autofix-pr", "Monitor and autofix any issues with the"),
        NativeSkill("background", "Send this session to the background and"),
        NativeSkill("batch", "Research and plan a large-scale change, then execute it in parallel across 5–30 isolated worktree agents that each open a PR."),
        NativeSkill("branch", "Create a branch of the current conversation"),
        NativeSkill("btw", "Ask a quick side question without"),
        NativeSkill("bug", "Report a bug or share your conversation"),
        NativeSkill("cd", "Move this session to a new working"),
        NativeSkill("chrome", "Open Claude in Chrome settings"),
        NativeSkill("clear", "Start a new session with empty context; previous session stays on disk (resumable with /resume)"),
        NativeSkill("code-review", "Review the current diff, or a PR number/branch/path target, for correctness bugs (plus reuse/simplification/efficiency cleanups where the model's recipe covers them) at the effort level given first, b"),
        NativeSkill("color", "Set the prompt bar color for this session"),
        NativeSkill("compact", "Free up context by summarizing the conversation so far"),
        NativeSkill("config", "Set a setting by key"),
        NativeSkill("context", "Show current context usage"),
        NativeSkill("copy", "Copy Claude's last response to clipboard"),
        NativeSkill("debug", "Enable debug logging for this session and help diagnose issues"),
        NativeSkill("deep-research", "Deep research harness — fan-out web searches, fetch sources, adversarially verify claims, synthesize a cited report. (dynamic workflow)"),
        NativeSkill("design", "Grant or revoke Claude agent access to your Design projects"),
        NativeSkill("design-login", "Authorize design-system access for"),
        NativeSkill("design-sync", "Push a React design system to claude.ai/design. This runs a converter that bundles the real component code (from Storybook or a bare package) and uploads it. Use when the user runs /design-sync or say"),
        NativeSkill("diff", "Toggle the diff panel showing uncommitted"),
        NativeSkill("doctor", "Health-check the user's Claude Code setup and fix issues: diagnose installation health — what the `claude doctor` terminal diagnostics cover — from local data (duplicate or leftover installs, PATH"),
        NativeSkill("effort", "Set effort level for model usage"),
        NativeSkill("exit", "Exit the CLI"),
        NativeSkill("export", "Export the current conversation to a file"),
        NativeSkill("fast", "Toggle fast mode (Opus 5.5)"),
        NativeSkill("feedback", "Send feedback to Anthropic or report a bug"),
        NativeSkill("fewer-permission-prompts", "Scan your transcripts for common read-only Bash and MCP tool calls, then add a prioritized allowlist to project .claude/settings.json to reduce permission prompts."),
        NativeSkill("focus", "Toggle focus view: just your prompt, summary, and response"),
        NativeSkill("fork", "Copy this conversation into a new"),
        NativeSkill("goal", "Set a goal — keep working until the condition is met"),
        NativeSkill("help", "Show help and available commands"),
        NativeSkill("hooks", "View hook configurations for tool events"),
        NativeSkill("ide", "Manage IDE integrations and show status"),
        NativeSkill("import", "Import config from another AI coding agent"),
        NativeSkill("init", "Initialize a new CLAUDE.md file with codebase documentation"),
        NativeSkill("insights", "Generate a report analyzing your Claude Code sessions"),
        NativeSkill("install-github-app", "Set up Claude GitHub Actions for a"),
        NativeSkill("install-slack-app", "Install the Claude Slack app"),
        NativeSkill("keybindings", "Open your keyboard shortcuts file"),
        NativeSkill("list-agents", "List subagents, teammates, and other Claude sessions you can message"),
        NativeSkill("login", "Sign in with your Anthropic account"),
        NativeSkill("logout", "Sign out from your Anthropic account"),
        NativeSkill("loop", "Run a prompt or slash command on a recurring interval (e.g. /loop 5m /foo). Omit the interval to let the model self-pace."),
        NativeSkill("mcp", "Manage MCP servers"),
        NativeSkill("memory", "Edit CLAUDE.md files and memory settings"),
        NativeSkill("mobile", "Show QR code to download the Claude mobile"),
        NativeSkill("model", "Set the AI model for Claude Code"),
        NativeSkill("output-style", "List output styles or switch to one"),
        NativeSkill("passes", "Share a free week of Claude Code with"),
        NativeSkill("permissions", "Manage allow and deny tool permission rules"),
        NativeSkill("plan", "Enable plan mode or view the current"),
        NativeSkill("plugin", "Manage Claude Code plugins"),
        NativeSkill("plugin-authoring", "Make a mod: a change to Claude's own interface or behaviour, such as a theme, a panel or pane that stays beside the chat and updates, a band, status line, toast, slash command, or hook on tool calls. "),
        NativeSkill("powerup", "Discover Claude Code features through quick"),
        NativeSkill("privacy-settings", "View and update your privacy settings"),
        NativeSkill("radio", "Listen to Claude FM lo-fi radio"),
        NativeSkill("recap", "Generate a one-line session recap now"),
        NativeSkill("release-notes", "View release notes"),
        NativeSkill("reload-plugins", "Activate pending plugin changes in the current session"),
        NativeSkill("reload-skills", "Pick up skills added or changed on disk during this session"),
        NativeSkill("remote-control", "Disconnect Remote Control"),
        NativeSkill("remote-env", "Choose the default environment for cloud"),
        NativeSkill("rename", "Rename the current conversation"),
        NativeSkill("resume", "Resume a previous conversation"),
        NativeSkill("rewind", "Restore the code and/or conversation to a"),
        NativeSkill("run", "Launch and drive this project's app to see a change working. Use when asked to run, start, or screenshot the app, or to confirm a change works in the real app (not just tests). First looks for a proje"),
        NativeSkill("run-skill-generator", "Author or improve the run-<unit> skill - a per-project skill that tells agents how to build, launch, and drive this project's app. Use when the user asks to set up the project, get it running, write r"),
        NativeSkill("sandbox", "◯ sandbox disabled (⏎ to configure)"),
        NativeSkill("schedule", "Create, update, list, or run scheduled cloud agents (routines) that execute on a cron schedule."),
        NativeSkill("scroll-speed", "Adjust mouse wheel scroll speed"),
        NativeSkill("security-review", "Complete a security review of the pending changes on the current branch"),
        NativeSkill("simplify", "Review the changed code for reuse, simplification, efficiency, and altitude cleanups, then apply the fixes. Quality only — it does not hunt for bugs; use /code-review for that."),
        NativeSkill("skill-doctor", "Show which loaded skills are unused and costing context"),
        NativeSkill("skills", "List available skills"),
        NativeSkill("slides", "Make a new Slides deck artifact from a"),
        NativeSkill("status", "Show Claude Code status including version,"),
        NativeSkill("statusline", "Set up Claude Code's status line UI"),
        NativeSkill("stickers", "Order Claude Code stickers"),
        NativeSkill("subtask", "Send a subagent off with your full context;"),
        NativeSkill("tasks", "View and manage everything running in the"),
        NativeSkill("team-onboarding", "Help teammates ramp on Claude Code with a guide from your usage"),
        NativeSkill("teleport", "Send this session to the cloud, or resume"),
        NativeSkill("terminal-setup", "Install Shift+Enter key binding for"),
        NativeSkill("theme", "Change the theme"),
        NativeSkill("tui", "Set the terminal UI renderer (default |"),
        NativeSkill("ultrareview", "Start a cloud agent that finds and verifies bugs in your branch (~5-15 min, \$5-\$25 USD) · Runs in a cloud session. See https://code.claude.com/docs/en/claude-code-on-the-web"),
        NativeSkill("upgrade", "Upgrade to Max for higher rate limits and"),
        NativeSkill("usage", "Show session cost, plan usage, and what's contributing to your limits"),
        NativeSkill("usage-credits", "Configure usage credits or request them from your admin when you hit a limit"),
        NativeSkill("verify", "Verify that a code change actually does what it's supposed to by exercising it end-to-end and observing behavior — drive the affected flow, not just tests or typecheck. Run before committing nontriv"),
        NativeSkill("voice", "Toggle voice mode"),
        NativeSkill("web-setup", "Set up cloud sessions with your GitHub"),
        NativeSkill("workflows", "Browse running and completed workflows"),
    )

    fun menu(skills: List<NativeSkill>, query: String): List<NativeSkill> {
        val seen = mutableSetOf<String>()
        val rows = ArrayList<Pair<NativeSkill, Boolean>>(claudeCommands.size + skills.size)
        for (command in claudeCommands) {
            if (seen.add(command.name.lowercase())) rows.add(command to true)
        }
        for (skill in skills) {
            if (pathWord(skill.name)) continue
            if (seen.add(skill.name.lowercase())) rows.add(skill to false)
        }
        return rows.mapNotNull { (skill, command) ->
            val rank = score(skill.name, skill.description, query) ?: return@mapNotNull null
            val nameMatch = if (rank <= NAME_MATCH) 1 else 0
            val group = when {
                query.isEmpty() -> if (command) 1 else 0
                nameMatch == 1 && command -> 0
                else -> nameMatch
            }
            Scored(rank, group, skill)
        }.sortedWith { left, right ->
            val byRank = left.rank.compareTo(right.rank)
            if (byRank != 0) byRank else {
                val byGroup = left.group.compareTo(right.group)
                if (byGroup != 0) byGroup else left.skill.name.compareTo(right.skill.name, ignoreCase = true)
            }
        }.map { it.skill }
    }

    private const val NAME_MATCH = 3

    private data class Scored(val rank: Int, val group: Int, val skill: NativeSkill)

    private fun score(name: String, description: String, query: String): Int? {
        if (query.isEmpty()) return 6
        if (name.equals(query, ignoreCase = true)) return 0
        if (name.startsWith(query, ignoreCase = true)) return 1
        if (prefixAtWord(name, query, ":-_/")) return 2
        if (name.contains(query, ignoreCase = true)) return 3
        if (prefixAtWord(description, query, " \t.,:;()")) return 4
        var matched = 0
        for (character in name) {
            if (matched < query.length && character.lowercaseChar() == query[matched].lowercaseChar()) matched++
        }
        return if (matched == query.length) 5 else null
    }

    private fun prefixAtWord(haystack: String, needle: String, boundaries: String): Boolean {
        for (index in haystack.indices) {
            if (index != 0 && haystack[index - 1] !in boundaries) continue
            if (haystack.regionMatches(index, needle, 0, needle.length, ignoreCase = true)) return true
        }
        return false
    }

    fun open(text: String): String = if (text.startsWith('/')) text else "/ $text"

    fun insert(text: String, name: String): String {
        val end = text.indexOfFirst(Char::isWhitespace).takeIf { it >= 0 } ?: text.length
        val rest = text.substring(end).trimStart()
        return "/$name $rest"
    }

    fun insert(text: String, name: String, cursor: Int): SkillInsertion {
        val slash = token(text, cursor) ?: return SkillInsertion(text, cursor.coerceIn(0, text.length))
        var wordEnd = slash.start
        while (wordEnd < text.length && !wordBreak(text[wordEnd])) wordEnd++
        val rest = text.substring(wordEnd).trimStart()
        val prefix = text.substring(0, slash.start)
        val head = "/$name"
        val value = if (rest.isEmpty()) "$prefix$head " else "$prefix$head $rest"
        return SkillInsertion(value, prefix.length + head.length + 1)
    }

    private fun pathWord(word: String) = word.any { it == '/' || it == '\\' }

    private fun wordBreak(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
}
