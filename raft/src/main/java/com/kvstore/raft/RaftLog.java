package com.kvstore.raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The Raft replicated log — an append-only, ordered list of {@link RaftLogEntry} records.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>Index 0 holds a permanent no-op sentinel entry (term=0, command=empty).
 *       This simplifies boundary checks throughout the algorithm.</li>
 *   <li>Entries are 1-indexed: {@code entries.get(i)} has {@code index = i}.</li>
 *   <li>Once committed, entries are never removed or overwritten.</li>
 *   <li>Uncommitted entries from failed elections may be truncated when
 *       a new leader sends a conflicting {@code AppendEntries}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * A {@link ReentrantReadWriteLock} guards all mutations. Multiple concurrent
 * readers are allowed; writers hold an exclusive lock.
 */
public class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    private final List<RaftLogEntry> entries = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public RaftLog() {
        // Sentinel entry at index 0 (no-op, term 0)
        entries.add(RaftLogEntry.noop(0));
    }

    // ─── Append ───────────────────────────────────────────────────────────────

    /**
     * Appends a new entry to the log. Called by the leader when a client command arrives.
     *
     * @return The index assigned to the new entry.
     */
    public long append(long term, byte[] command, String commandId) {
        writeLock.lock();
        try {
            long index = entries.size(); // next 1-based index
            entries.add(new RaftLogEntry(term, index, command, commandId));
            log.debug("Log append: index={} term={} commandId={}", index, term, commandId);
            return index;
        } finally {
            writeLock.unlock();
        }
    }

    // ─── AppendEntries reconciliation ─────────────────────────────────────────

    /**
     * Applies entries received from the leader during an {@code AppendEntries} RPC.
     *
     * <p>Algorithm (from §5.3 of the Raft paper):
     * <ol>
     *   <li>Verify the log contains an entry at {@code prevLogIndex} with term
     *       {@code prevLogTerm}. If not, return {@code false} (consistency check failed).</li>
     *   <li>If a new entry conflicts with an existing one (same index, different term),
     *       delete the existing entry and all that follow it.</li>
     *   <li>Append any new entries not already in the log.</li>
     * </ol>
     *
     * @return {@code true} if the consistency check passed and entries were applied.
     */
    public boolean appendFromLeader(long prevLogIndex, long prevLogTerm, List<RaftLogEntry> newEntries) {
        writeLock.lock();
        try {
            // 1. Consistency check
            if (prevLogIndex > 0) {
                if (prevLogIndex >= entries.size()) {
                    log.debug("AppendEntries rejected: prevLogIndex={} beyond log size={}", prevLogIndex, entries.size());
                    return false;
                }
                if (entries.get((int) prevLogIndex).term() != prevLogTerm) {
                    log.debug("AppendEntries rejected: prevLogIndex={} term mismatch (have={} want={})",
                            prevLogIndex, entries.get((int) prevLogIndex).term(), prevLogTerm);
                    return false;
                }
            }

            // 2. Reconcile incoming entries
            for (RaftLogEntry incoming : newEntries) {
                int idx = (int) incoming.index();
                if (idx < entries.size()) {
                    // Conflict: same index, different term → truncate from here
                    if (entries.get(idx).term() != incoming.term()) {
                        log.warn("Log conflict at index={}: truncating from here", idx);
                        entries.subList(idx, entries.size()).clear();
                        entries.add(incoming);
                    }
                    // If term matches, entry is already present — skip
                } else {
                    entries.add(incoming);
                }
            }
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /** The index of the last entry in the log (0 if only the sentinel exists). */
    public long lastIndex() {
        readLock.lock();
        try { return entries.size() - 1L; }
        finally { readLock.unlock(); }
    }

    /** The term of the last entry in the log (0 if only the sentinel exists). */
    public long lastTerm() {
        readLock.lock();
        try { return entries.get(entries.size() - 1).term(); }
        finally { readLock.unlock(); }
    }

    /**
     * Returns the entry at {@code index}, or {@code null} if out of range.
     */
    public RaftLogEntry get(long index) {
        readLock.lock();
        try {
            if (index < 0 || index >= entries.size()) return null;
            return entries.get((int) index);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Returns all entries with index strictly greater than {@code fromIndex}.
     * Used by the leader to determine which entries to send in AppendEntries.
     */
    public List<RaftLogEntry> entriesAfter(long fromIndex) {
        readLock.lock();
        try {
            int start = (int) fromIndex + 1;
            if (start >= entries.size()) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(entries.subList(start, entries.size())));
        } finally {
            readLock.unlock();
        }
    }

    /** Total number of entries including the sentinel (so actual entries = size - 1). */
    public int size() {
        readLock.lock();
        try { return entries.size(); }
        finally { readLock.unlock(); }
    }
}
