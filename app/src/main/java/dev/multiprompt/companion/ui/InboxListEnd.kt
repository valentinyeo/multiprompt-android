package dev.multiprompt.companion.ui

class InboxListEnd {
    private var openSplit: String? = null
    private var opened = false
    private var rows = 0
    private var pending = true

    fun revealsNewest(splitId: String?, rowCount: Int, firstVisibleIndex: Int): Boolean {
        val switched = !opened || splitId != openSplit
        val atNewest = firstVisibleIndex == 0
        if (switched) {
            openSplit = splitId
            opened = true
            pending = true
        }
        val reveal = rowCount > 0 && (pending || (rowCount > rows && atNewest))
        rows = rowCount
        if (reveal) pending = false
        return reveal
    }
}
