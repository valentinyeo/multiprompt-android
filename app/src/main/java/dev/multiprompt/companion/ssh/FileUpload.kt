package dev.multiprompt.companion.ssh

object FileUpload {
    const val MAX_BYTES = 200L * 1024 * 1024
    const val CHUNK_BYTES = 32 * 1024
    private const val MAX_NAME = 80

    private val SCRIPT = ("sh -c 'set -ef; IFS=; umask 077; d=@HOME/.multiprompt/uploads/<dir>; mkdir -p @d; " +
        "find @d -maxdepth 1 -type d -name .staging.* -mmin +1440 -exec rm -rf -- {} +; " +
        "t=@(mktemp -d @d/.staging.XXXXXXXXXX); p=; " +
        """trap '\''[ -z @p ] || kill @p 2>/dev/null || :; rm -rf -- @t'\'' 0; trap '\''exit 1'\'' HUP INT TERM; """ +
        "exec 3<&0; if dd if=/dev/null of=/dev/null status=progress 2>/dev/null; " +
        "then dd bs=65536 status=progress <&3 > @t/data & else cat <&3 > @t/data & fi; " +
        "p=@!; exec 3<&-; wait @p; p=; [ @(wc -c < @t/data) -eq <size> ] || exit 1; " +
        "f=@d/<file>; n=0; until ln -T -- @t/data @f 2>/dev/null; do [ -e @f ] || [ -L @f ] || exit 1; " +
        "n=@((n+1)); f=@d/@n-<file>; done; rm -rf -- @t; trap - 0 HUP INT TERM; " +
        """printf %s\\n @f'""").replace('@', '$')

    fun remoteName(displayName: String): String {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        val points = name.codePoints().toArray()
        val dot = points.lastIndexOf('.'.code)
        val extStart = if (dot > 0 && points.size - dot < MAX_NAME) dot else points.size
        val stemCap = MAX_NAME - (points.size - extStart)
        val out = StringBuilder()
        var leading = true
        for (index in points.indices) {
            if (index < extStart && out.length >= stemCap) continue
            if (out.length == MAX_NAME) break
            var c = safeChar(points[index])
            if (leading && c == '.') c = '_'
            if (points[index] != '.'.code) leading = false
            out.append(c)
        }
        return if (out.isEmpty()) "file" else out.toString()
    }

    fun remoteSessionDir(session: String): String {
        val out = StringBuilder()
        var leading = true
        for (point in session.codePoints().toArray()) {
            if (out.length == MAX_NAME) break
            val c = safeChar(point)
            out.append(if (leading && c == '.') '_' else c)
            if (point != '.'.code) leading = false
        }
        return if (out.isEmpty()) "session" else out.toString()
    }

    fun command(sessionName: String, remoteName: String, size: Long): String {
        require(size in 0..MAX_BYTES) { "File size is out of range" }
        return SCRIPT
            .replace("<dir>", remoteSessionDir(sessionName))
            .replace("<size>", size.toString())
            .replace("<file>", remoteSessionDir(remoteName))
    }

    fun parsePath(output: String, remoteName: String): String? {
        val path = output.lineSequence().map { it.trim('\r') }.lastOrNull { it.isNotEmpty() } ?: return null
        val name = remoteSessionDir(remoteName)
        if (path.length <= name.length || path[0] != '/' || !path.endsWith(name)) return null
        val separator = path[path.length - name.length - 1]
        return if (separator == '/' || separator == '-') path else null
    }

    private fun safeChar(point: Int): Char =
        if (point in 'A'.code..'Z'.code || point in 'a'.code..'z'.code || point in '0'.code..'9'.code ||
            point == '.'.code || point == '_'.code || point == '-'.code
        ) point.toChar() else '_'
}
