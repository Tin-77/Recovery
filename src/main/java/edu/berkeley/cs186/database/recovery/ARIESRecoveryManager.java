package edu.berkeley.cs186.database.recovery;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     * @param diskSpaceManager disk space manager
     * @param bufferManager buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     *
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     *
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum transaction being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry transEntry = transactionTable.get(transNum);
        LogRecord record = new CommitTransactionLogRecord(transNum, transEntry.lastLSN);
        long new_lsn = logManager.appendToLog(record);
        logManager.flushToLSN(new_lsn);
        transEntry.lastLSN = new_lsn;
        transEntry.transaction.setStatus(Transaction.Status.COMMITTING);
        return new_lsn;
    }

    /**
     * Called when a transaction is set to be aborted.
     *
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry transEntry = transactionTable.get(transNum);
        LogRecord record = new AbortTransactionLogRecord(transNum, transEntry.lastLSN);
        long new_lsn = logManager.appendToLog(record);
        transEntry.lastLSN = new_lsn;
        transEntry.transaction.setStatus(Transaction.Status.ABORTING);
        return new_lsn;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     *
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        TransactionTableEntry transEntry = transactionTable.get(transNum);
        if (transEntry.transaction.getStatus() == Transaction.Status.ABORTING) {
            rollbackToLSN(transNum, 0L);
        }
        LogRecord record = new EndTransactionLogRecord(transNum, transEntry.lastLSN);
        transactionTable.remove(transNum);
        long new_lsn = logManager.appendToLog(record);
        transEntry.transaction.setStatus(Transaction.Status.COMPLETE);
        return new_lsn;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     *    - if the record at the current LSN is undoable:
     *       - Get a compensation log record (CLR) by calling undo on the record
     *       - Append the CLR
     *       - Call redo on the CLR to perform the undo
     *    - update the current LSN to that of the next record to undo
     *
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        // TODO(proj5) implement the rollback logic described above
        while (currentLSN > LSN) {
            LogRecord record = logManager.fetchLogRecord(currentLSN);
            if (record.isUndoable()) {
                LogRecord clrRecord = record.undo(transactionEntry.lastLSN);
                transactionEntry.lastLSN = logManager.appendToLog(clrRecord);
                clrRecord.redo(this, diskSpaceManager, bufferManager);
            }
            currentLSN = record.getPrevLSN().get();
        }
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     *
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     *
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     *
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     *
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum transaction performing the write
     * @param pageNum page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before bytes starting at pageOffset before the write
     * @param after bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        TransactionTableEntry transEntry = transactionTable.get(transNum);
        LogRecord record = new UpdatePageLogRecord(transNum, pageNum, transEntry.lastLSN, pageOffset, before, after);
        long new_lsn = logManager.appendToLog(record);
        transEntry.lastLSN = new_lsn;
        dirtyPage(pageNum, new_lsn);
        return new_lsn;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the partition is the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     *
     * This method should return -1 if the page is in the log partition.
     *
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     *
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     * @param transNum transaction to delete savepoint for
     * @param name name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     *
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);

        // TODO(proj5): implement
        rollbackToLSN(transNum, savepointLSN);
        return;
    }

    /**
     * Create a checkpoint.
     *
     * First, a begin checkpoint record should be written.
     *
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     *
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        for (long pageNum : dirtyPageTable.keySet()) {
            if (!EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size() + 1, chkptTxnTable.size())) {
                LogRecord chkRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(chkRecord);
                chkptDPT.clear();
            }
            chkptDPT.put(pageNum, dirtyPageTable.get(pageNum));
        }
        for (long transNum : transactionTable.keySet()) {
            if (!EndCheckpointLogRecord.fitsInOneRecord(chkptDPT.size(), chkptTxnTable.size() + 1)) {
                LogRecord chkRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(chkRecord);
                chkptDPT.clear();
                chkptTxnTable.clear();
            }
            TransactionTableEntry transEntry = transactionTable.get(transNum);
            chkptTxnTable.put(transNum, new Pair<>(transEntry.transaction.getStatus(), transEntry.lastLSN));
        }

        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN,v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     *
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     *
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     *
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     *
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     *
     * If the log record is page-related (getPageNum is present), update the dpt
     *   - update/undoupdate page will dirty pages
     *   - free/undoalloc page always flush changes to disk
     *   - no action needed for alloc/undofree page
     *
     * If the log record is for a change in transaction status:
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     *   from txn table, and add to endedTransactions
     *
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     *   the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     *   to transition from the status in the table to the status in the
     *   checkpoint. For example, running -> aborting is a possible transition,
     *   but aborting -> running is not.
     *
     * After all records in the log are processed, for each ttable entry:
     *  - if COMMITTING: clean up the transaction, change status to COMPLETE,
     *    remove from the ttable, and append an end record
     *  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
     *    record
     *  - if RECOVERY_ABORTING: no action needed
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        // TODO(proj5): implement
        Iterator<LogRecord> logs = logManager.scanFrom(LSN);
        while (logs.hasNext()) {
            // If the log record is for a transaction operation (getTransNum is present)
            LogRecord logRecord = logs.next();
            if (logRecord.getTransNum().isPresent()) {
                // update the transaction table
                if (!transactionTable.containsKey(logRecord.getTransNum().get())) {
                    startTransaction(newTransaction.apply(logRecord.getTransNum().get()));
                }
                transactionTable.get(logRecord.getTransNum().get()).lastLSN = logRecord.LSN;

                // If the log record is page-related (getPageNum is present), update the dpt
                // - update/undoupdate page will dirty pages
                // - free/undoalloc page always flush changes to disk
                // - no action needed for alloc/undofree page
                if (logRecord.getPageNum().isPresent()) {
                    if (logRecord.getType() == LogType.UPDATE_PAGE || logRecord.getType() == LogType.UNDO_UPDATE_PAGE) {
                        dirtyPageTable.putIfAbsent(logRecord.getPageNum().get(), logRecord.getLSN());
                    }
                    if (logRecord.getType() == LogType.FREE_PAGE || logRecord.getType() == LogType.UNDO_ALLOC_PAGE) {
                        dirtyPageTable.remove(logRecord.getPageNum().get());
                    }
                }

                // If the log record is for a change in transaction status:
                // - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
                // - update the transaction table
                // - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
                //   from txn table, and add to endedTransactions
                TransactionTableEntry transEntry = transactionTable.get(logRecord.getTransNum().get());
                if (logRecord.getType() == LogType.COMMIT_TRANSACTION) {
                    transEntry.transaction.setStatus(Transaction.Status.COMMITTING);
                } else if (logRecord.getType() == LogType.ABORT_TRANSACTION) {
                    transEntry.transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                } else if (logRecord.getType() == LogType.END_TRANSACTION) {
                    transEntry.transaction.cleanup();
                    transEntry.transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.remove((logRecord.getTransNum().get()));
                    endedTransactions.add(logRecord.getTransNum().get());
                }
            }

            // If the log record is an end_checkpoint record:
            // - Copy all entries of checkpoint DPT (replace existing entries if any)
            // - Skip txn table entries for transactions that have already ended
            // - Add to transaction table if not already present
            // - Update lastLSN to be the larger of the existing entry's (if any) and
            //   the checkpoint's
            // - The status's in the transaction table should be updated if it is possible
            //   to transition from the status in the table to the status in the
            //   checkpoint. For example, running -> aborting is a possible transition,
            //   but aborting -> running is not.
            if (logRecord.getType() == LogType.END_CHECKPOINT) {
                for (long key : logRecord.getDirtyPageTable().keySet()) {
                    if (dirtyPageTable.containsKey(key)) {
                        dirtyPageTable.replace(key, logRecord.getDirtyPageTable().get(key));
                    } else {
                        dirtyPageTable.putIfAbsent(key, logRecord.getDirtyPageTable().get(key));
                    }
                }
                for (long key : logRecord.getTransactionTable().keySet()) {
                    if (!endedTransactions.contains(key)) {
                        if (!transactionTable.containsKey(key)) {
                            startTransaction(newTransaction.apply(key));
                            if (logRecord.getTransactionTable().get(key).getSecond() >= transactionTable.get(key).lastLSN)
                                transactionTable.get(key).lastLSN = logRecord.getTransactionTable().get(key).getSecond();
                        }
                        if (logRecord.getTransactionTable().get(key).getFirst() == Transaction.Status.COMMITTING)
                            transactionTable.get(key).transaction.setStatus(Transaction.Status.COMMITTING);
                        if (logRecord.getTransactionTable().get(key).getFirst() == Transaction.Status.ABORTING)
                            transactionTable.get(key).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                    }
                }
            }
        }
        // After all records in the log are processed, for each ttable entry:
        //  - if COMMITTING: clean up the transaction, change status to COMPLETE,
        //    remove from the ttable, and append an end record
        //  - if RUNNING: change status to RECOVERY_ABORTING, and append an abort
        //    record
        //  - if RECOVERY_ABORTING: no action needed
        for (long key : transactionTable.keySet()) {
            long end_lsn;
            if (transactionTable.get(key).transaction.getStatus() == Transaction.Status.COMMITTING) {
                transactionTable.get(key).transaction.cleanup();
                transactionTable.get(key).transaction.setStatus(Transaction.Status.COMPLETE);
                TransactionTableEntry temp = transactionTable.get(key);
                transactionTable.remove(key);
                LogRecord endRecord = new EndTransactionLogRecord(key, temp.lastLSN);
                end_lsn = logManager.appendToLog(endRecord);
                temp.lastLSN = end_lsn;
            } else if (transactionTable.get(key).transaction.getStatus() == Transaction.Status.RUNNING) {
                transactionTable.get(key).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                LogRecord endRecord = new AbortTransactionLogRecord(key, transactionTable.get(key).lastLSN);
                end_lsn = logManager.appendToLog(endRecord);
                transactionTable.get(key).lastLSN = end_lsn;
            }
        }

    }

    /**
     * This method performs the redo pass of restart recovery.
     *
     * First, determine the starting point for REDO from the dirty page table.
     *
     * Then, scanning from the starting point, if the record is redoable and
     * - partition-related (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     *   the dirty page table with LSN >= recLSN, the page is fetched from disk,
     *   the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        long startLSN = Collections.min(dirtyPageTable.values());
        Iterator<LogRecord> redos = logManager.scanFrom(startLSN);
        while (redos.hasNext()) {
            LogRecord redo = redos.next();
            if (redo.isRedoable()) {
                if (redo.getType() == LogType.ALLOC_PART || redo.getType() == LogType.FREE_PART
                   || redo.getType() == LogType.UNDO_ALLOC_PART || redo.getType() == LogType.UNDO_FREE_PART) {
                    redo.redo(this, diskSpaceManager, bufferManager);
                } else if (redo.getType() == LogType.ALLOC_PAGE || redo.getType() == LogType.UNDO_FREE_PAGE) {
                    redo.redo(this, diskSpaceManager, bufferManager);
                } else if (redo.getType() == LogType.UPDATE_PAGE || redo.getType() == LogType.UNDO_UPDATE_PAGE
                   || redo.getType() == LogType.FREE_PAGE || redo.getType() == LogType.UNDO_ALLOC_PAGE) {
                    if (redo.getPageNum().isPresent()) {
                        if (dirtyPageTable.containsKey(redo.getPageNum().get())) {
                            Page page = bufferManager.fetchPage(new DummyLockContext(), redo.getPageNum().get());
                            try {
                                // Do anything that requires the page here
                                long pageLSN = page.getPageLSN();
                                if (redo.getLSN() >= dirtyPageTable.get(redo.getPageNum().get())) {
                                    if (pageLSN < redo.getLSN()) {
                                        redo.redo(this, diskSpaceManager, bufferManager);
                                    }
                                }
                            } finally {
                                page.unpin();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This method performs the undo pass of restart recovery.

     * First, a priority queue is created sorted on lastLSN of all aborting
     * transactions.
     *
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and append the appropriate CLR
     * - replace the entry with a new one, using the undoNextLSN if available,
     *   if the prevLSN otherwise.
     * - if the new LSN is 0, clean up the transaction, set the status to complete,
     *   and remove from transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        PriorityQueue<Pair<Long, Transaction>> pQueue = new PriorityQueue<>(new PairFirstReverseComparator<>());
        for (long transNum : transactionTable.keySet()) {
            TransactionTableEntry transEntry = transactionTable.get(transNum);
            if (transEntry.transaction.getStatus() == Transaction.Status.RECOVERY_ABORTING) {
                pQueue.add(new Pair<>(transEntry.lastLSN, transEntry.transaction));
            }
        }
        while (!pQueue.isEmpty()) {
            Pair<Long, Transaction> undo = pQueue.poll();
            long lsn = undo.getFirst();
            Transaction trans = undo.getSecond();
            LogRecord undoRecord = logManager.fetchLogRecord(lsn);
            if (undoRecord.isUndoable()) {
                LogRecord clrRecord = undoRecord.undo(transactionTable.get(trans.getTransNum()).lastLSN);
                transactionTable.get(trans.getTransNum()).lastLSN = logManager.appendToLog(clrRecord);
                clrRecord.redo(this, diskSpaceManager, bufferManager);
            }
            long nextLSN;
            if (undoRecord.getUndoNextLSN().isPresent()) {
                nextLSN = undoRecord.getUndoNextLSN().get();
            } else {
                nextLSN = undoRecord.getPrevLSN().get();
            }
            if (nextLSN == 0L) {
                transactionTable.get(trans.getTransNum()).transaction.cleanup();
                transactionTable.get(trans.getTransNum()).transaction.setStatus(Transaction.Status.COMPLETE);
                LogRecord endRecord = new EndTransactionLogRecord(trans.getTransNum(), transactionTable.get(trans.getTransNum()).lastLSN);
                logManager.appendToLog(endRecord);
                transactionTable.remove(trans.getTransNum());
            } else {
                pQueue.offer(new Pair<>(nextLSN, trans));
            }
        }
    }
    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////
    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }
}
