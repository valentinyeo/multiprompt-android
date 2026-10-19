package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

const val FAVORITES_SPLIT_ID = "\u0000favorites"

fun isFavoriteSession(session: TmuxSession, layout: AccountLayout?, mapping: Map<Int, HostProfile>): Boolean =
    layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.favorite == true

fun inboxSplitIds(
    workspaceSplitIds: List<String?>,
    allOnRight: Boolean,
    frequent: Boolean = true,
    favorites: Boolean = true,
    all: Boolean = true,
    frequentFirst: Boolean = true,
): List<String?> = buildList {
    if (frequent && frequentFirst) add(FREQUENT_SPLIT_ID)
    (if (allOnRight) workspaceSplitIds.asReversed() else workspaceSplitIds).forEach {
        if (it == null) {
            if (all) add(null)
            if (favorites) add(FAVORITES_SPLIT_ID)
            if (frequent && !frequentFirst) add(FREQUENT_SPLIT_ID)
        } else add(it)
    }
    if (isEmpty()) add(null)
}
