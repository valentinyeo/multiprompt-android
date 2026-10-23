package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

fun isMutedSession(session: TmuxSession, layout: AccountLayout?, mapping: Map<Int, HostProfile>): Boolean =
    layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.muted == true

fun isHiddenAsMuted(session: TmuxSession, layout: AccountLayout?, mapping: Map<Int, HostProfile>, selectedWorkspaceId: String?): Boolean =
    selectedWorkspaceId != FAVORITES_SPLIT_ID && isMutedSession(session, layout, mapping)
