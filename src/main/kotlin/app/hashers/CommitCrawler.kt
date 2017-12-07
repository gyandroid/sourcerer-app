// Copyright 2017 Sourcerer Inc. All Rights Reserved.
// Author: Anatoly Kislov (anatoly@sourcerer.io)

package app.hashers

import app.Logger
import app.model.Commit
import app.model.DiffContent
import app.model.DiffFile
import app.model.DiffRange
import app.model.Repo
import app.utils.RepoHelper
import io.reactivex.Observable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

data class JgitPair(val commit: RevCommit, val list: List<JgitDiff>)
data class JgitDiff(val diffEntry: DiffEntry, val editList: EditList)

/**
* Iterates over the diffs between commits in the repo's history.
*/
object CommitCrawler {
    private val MASTER_BRANCH = "refs/heads/master"
    private val REMOTE_HEAD = "refs/remotes/origin/HEAD"

    fun getDefaultBranchHead(git: Git): ObjectId {
        val remoteHead = git.branchList()?.repository?.allRefs?.filter {
            it.key.contains(REMOTE_HEAD)
        }?.entries?.firstOrNull()?.value?.target?.objectId
        if (remoteHead != null) {
            Logger.debug { "Hashing from remote default branch" }
            return remoteHead
        }
        val masterBranch = git.repository.resolve(MASTER_BRANCH)
        if (masterBranch != null) {
            Logger.debug { "Hashing from local master branch" }
            return masterBranch
        }
        throw Exception("No remote default or local master branch found")
    }

    fun fetchRehashesAndEmails(git: Git):
        Pair<LinkedList<String>, HashSet<String>> {
        val head: RevCommit = RevWalk(git.repository)
            .parseCommit(getDefaultBranchHead(git))

        val revWalk = RevWalk(git.repository)
        revWalk.markStart(head)

        val commitsRehashes = LinkedList<String>()
        val emails = hashSetOf<String>()

        var commit: RevCommit? = revWalk.next()
        while (commit != null) {
            commitsRehashes.add(DigestUtils.sha256Hex(commit.name))
            emails.add(commit.authorIdent.emailAddress)
            commit.disposeBody()
            commit = revWalk.next()
        }
        revWalk.dispose()

        return Pair(commitsRehashes, emails)
    }

    fun getJGitObservable(git: Git,
                          totalCommitCount: Int = 0,
                          tail : RevCommit? = null) : Observable<JgitPair> =
        Observable.create { subscriber ->

        val repo: Repository = git.repository
        val revWalk = RevWalk(repo)
        val head: RevCommit =
            try { revWalk.parseCommit(repo.resolve(RepoHelper.MASTER_BRANCH)) }
            catch(e: Exception) { throw Exception("No master branch") }

        val df = DiffFormatter(DisabledOutputStream.INSTANCE)
        df.setRepository(repo)
        df.setDetectRenames(true)

        var commitCount = 0
        revWalk.markStart(head)
        var commit: RevCommit? = revWalk.next()  // Move the walker to the head.
        while (commit != null && commit != tail) {
            commitCount++
            val parentCommit: RevCommit? = revWalk.next()

            // Smart casts are not yet supported for a mutable variable captured
            // in an inline lambda, see
            // https://youtrack.jetbrains.com/issue/KT-7186.
            if (Logger.isDebug) {
                val commitName = commit.getName()
                val commitMsg = commit.getShortMessage()
                Logger.debug { "commit: $commitName; '$commitMsg'" }
                if (parentCommit != null) {
                    val parentCommitName = parentCommit.getName()
                    val parentCommitMsg = parentCommit.getShortMessage()
                    Logger.debug {
                        "parent commit: ${parentCommitName}; '${parentCommitMsg}'"
                    }
                }
                else {
                    Logger.debug { "parent commit: null" }
                }
            }

            val perc = if (totalCommitCount != 0) {
                (commitCount.toDouble() / totalCommitCount) * 100
            } else 0.0
            Logger.printCommit(commit.shortMessage, commit.name, perc)

            val diffEntries = df.scan(parentCommit, commit)
            val diffEdits = diffEntries.map { diff ->
                JgitDiff(diff, df.toFileHeader(diff).toEditList())
            }
            subscriber.onNext(JgitPair(commit, diffEdits))
            commit = parentCommit
        }

        subscriber.onComplete()
    }

    fun getObservable(git: Git,
                      jgitObservable: Observable<JgitPair>,
                      repo: Repo): Observable<Commit> {
        return Observable.create<Commit> { subscriber ->
            jgitObservable.subscribe( { (jgitCommit, jgitDiffs) ->
                // Mapping and stats extraction.
                val commit = Commit(jgitCommit)
                commit.diffs = getDiffFiles(git.repository, jgitDiffs)

                // Count lines on all non-binary files. This is additional
                // statistics to CommitStats because not all file extensions
                // may be supported.
                commit.numLinesAdded = commit.diffs.fold(0) { total, file ->
                    total + file.getAllAdded().size
                }
                commit.numLinesDeleted = commit.diffs.fold(0) { total, file ->
                    total + file.getAllDeleted().size
                }
                commit.repo = repo

                subscriber.onNext(commit)
            })
            subscriber.onComplete()
        }
    }

    fun getObservable(git: Git,
                      repo: Repo): Observable<Commit> {
        return getObservable(git, getJGitObservable(git), repo)
    }

    private fun getDiffFiles(jgitRepo: Repository,
                             jgitDiffs: List<JgitDiff>) : List<DiffFile> {
        return jgitDiffs
            // Skip binary files.
            .filter { (diff, _) ->
                val fileId =
                    if (diff.getNewPath() != DiffEntry.DEV_NULL) {
                        diff.getNewId().toObjectId()
                    } else {
                        diff.getOldId().toObjectId()
                    }
                val stream = try { jgitRepo.open(fileId).openStream() }
                catch (e: Exception) { null }
                stream != null && !RawText.isBinary(stream)
            }
            .map { (diff, edits) ->
                // TODO(anatoly): Can produce exception for large object.
                // Investigate for size.
                val new = try {
                    getContentByObjectId(jgitRepo, diff.newId.toObjectId())
                } catch (e: Exception) {
                    Logger.error(e)
                    null
                }
                val old = try {
                    getContentByObjectId(jgitRepo, diff.oldId.toObjectId())
                } catch (e: Exception) {
                    Logger.error(e)
                    null
                }

                val diffFiles = mutableListOf<DiffFile>()
                if (new != null && old != null) {
                    val path = when (diff.changeType) {
                        DiffEntry.ChangeType.DELETE -> diff.oldPath
                        else -> diff.newPath
                    }
                    diffFiles.add(DiffFile(path = path,
                        changeType = diff.changeType,
                        old = DiffContent(old, edits.map { edit ->
                            DiffRange(edit.beginA, edit.endA)
                        }),
                        new = DiffContent(new, edits.map { edit ->
                            DiffRange(edit.beginB, edit.endB)
                        })
                    ))
                }
                diffFiles
            }
            .flatten()
    }

    private fun getContentByObjectId(repo: Repository,
                                     objectId: ObjectId): List<String> {
        return try {
            val obj = repo.open(objectId)
            val rawText = RawText(obj.bytes)
            val content = ArrayList<String>(rawText.size())
            for (i in 0..(rawText.size() - 1)) {
                content.add(rawText.getString(i))
            }
            return content
        } catch (e: Exception) {
            listOf()
        }
    }
}
