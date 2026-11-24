package com.maekom.codetracker.analysis

import com.maekom.codetracker.model.CommitSummary
import com.maekom.codetracker.model.MarkdownFileRef
import com.maekom.codetracker.model.WorkEpisode
import java.time.Duration
import kotlin.math.abs

/**
 * Groups consecutive commits by the same author into Work Episodes when:
 * - The interval between consecutive commits is <= 4 hours, OR
 * - They occurred on the same calendar date.
 */
object EpisodeGrouper {

    private val MAX_GAP: Duration = Duration.ofHours(4)

    fun groupIntoEpisodes(commits: List<CommitSummary>): List<WorkEpisode> {
        if (commits.isEmpty()) return emptyList()

        // Sort ascending chronologically to properly chain consecutive same-author commits
        val ascending = commits.sortedBy { it.epochSeconds }
        val grouped = mutableListOf<MutableList<CommitSummary>>()

        for (commit in ascending) {
            val currentGroup = grouped.lastOrNull()
            if (currentGroup == null) {
                grouped.add(mutableListOf(commit))
                continue
            }

            val prev = currentGroup.last()
            val sameAuthor = prev.authorName.equals(commit.authorName, ignoreCase = true)
            val sameDate = prev.timestamp.toLocalDate() == commit.timestamp.toLocalDate()
            val withinFourHours = abs(Duration.between(prev.timestamp, commit.timestamp).seconds) <= MAX_GAP.seconds

            if (sameAuthor && (sameDate || withinFourHours)) {
                currentGroup.add(commit)
            } else {
                grouped.add(mutableListOf(commit))
            }
        }

        // Convert to WorkEpisode objects (newest episodes first; commits inside each episode ordered chronologically)
        return grouped.mapIndexed { idx, groupCommits ->
            val first = groupCommits.first()
            val last = groupCommits.last()
            val mdRefs = groupCommits.flatMap { c ->
                c.files
                    .filter { it.path.endsWith(".md", ignoreCase = true) }
                    .map { f ->
                        MarkdownFileRef(
                            commitHash = c.hash,
                            filePath = f.path,
                            commitSubject = c.subject,
                            timestamp = c.timestamp
                        )
                    }
            }.distinctBy { "${it.commitHash}:${it.filePath}" }

            WorkEpisode(
                id = "ep-${idx + 1}-${first.shortHash}",
                date = last.timestamp.toLocalDate(),
                authorName = first.authorName,
                startTime = first.timestamp,
                endTime = last.timestamp,
                commits = groupCommits.toList(),
                markdownFiles = mdRefs
            )
        }.reversed()
    }

    /**
     * Find the episode containing [commitHash] and its immediately preceding chronological episode.
     * Note: [episodes] is ordered newest-first, so index + 1 is the immediately preceding chronological episode.
     */
    fun findEpisodeAndPrevious(
        episodes: List<WorkEpisode>,
        commitHash: String
    ): Pair<WorkEpisode?, WorkEpisode?> {
        val idx = episodes.indexOfFirst { ep -> ep.commits.any { it.hash == commitHash } }
        if (idx < 0) return null to null
        val current = episodes[idx]
        val previous = episodes.getOrNull(idx + 1)
        return current to previous
    }
}
