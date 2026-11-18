package io.github.iaguito22.oto.playback

import io.github.iaguito22.oto.dj.DjTempoMatch

internal fun androidDjTempoProtected(queueIds: List<String>, currentIndex: Int, upcomingIds: List<String>): Set<String> =
    buildSet {
        addAll(DjTempoMatch.protectedUpcomingIds(upcomingIds))
        for (i in currentIndex..currentIndex + 1) queueIds.getOrNull(i)?.let(::add)
    }
