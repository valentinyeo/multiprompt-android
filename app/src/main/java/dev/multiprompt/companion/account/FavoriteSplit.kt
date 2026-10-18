package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

const val FAVORITES_SPLIT_ID = "\u0000favorites"

fun isFavoriteSession(session: TmuxSession, layout: AccountLayout?, mapping: Map<Int, HostProfile>): Boolean =
    layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.favorite == true

fun inboxSplitIds(workspaceSplitIds: List<String?>, allOnRight: Boolean): List<String?> =
    (if (allOnRight) workspaceSplitIds.asReversed() else workspaceSplitIds).flatMap {
        if (it == null) listOf(null, FAVORITES_SPLIT_ID) else listOf(it)
    }
