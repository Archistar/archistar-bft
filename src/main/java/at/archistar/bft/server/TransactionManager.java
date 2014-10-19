package at.archistar.bft.server;

import at.archistar.bft.exceptions.InconsistentResultsException;
import at.archistar.bft.messages.AbstractCommand;
import at.archistar.bft.messages.ClientFragmentCommand;
import at.archistar.bft.messages.CommitCommand;
import at.archistar.bft.messages.PrepareCommand;
import at.archistar.bft.messages.PreprepareCommand;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * TODO: can't we move some of the area stuff in here? A TransactionManager copy
 *       should be the same as an era?
 * 
 * @author andy
 */
public class TransactionManager {
    
    /**
     * (client-side operation id) -> transaction mapping
     */
    private final SortedMap<String, Transaction> collClientId;

    /**
     * (internal id aka. sequence) -> transaction mapping
     */
    private final SortedMap<Integer, Transaction> collSequence;

    private final ReentrantLock lockCollections = new ReentrantLock();
    
    private final int replicaId;
    
    private int maxSequence = 0;
    
    private int lastCommited = -1;
    
    private int viewNr = 0;
    
    private final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    private final int f;
    
    private final BftEngineCallbacks callbacks;
    
    private final CheckpointManager checkpoints;
    
    private boolean isPrimary;

    public TransactionManager(int replicaId, int f, BftEngineCallbacks callbacks, CheckpointManager checkpoints) {
        this.collClientId = new TreeMap<>();
        this.collSequence = new TreeMap<>();
        this.replicaId = replicaId;
        this.f = f;
        this.callbacks = callbacks;
        this.checkpoints = checkpoints;
    }
    
    private Transaction handleClientFragmentCommand(ClientFragmentCommand c) {
        String clientOperationId = c.getClientOperationId();
        Transaction result;

        if (collClientId.containsKey(clientOperationId)) {
            /* there was already a preprepare request */
            result = collClientId.get(clientOperationId);
        } else {
            /* first request */
            result = new Transaction(c, replicaId, f, this.callbacks);
            collClientId.put(c.getClientOperationId(), result);
        }

        result.addClientCommand(c);

        if (isPrimary()) {
            result.setDataFromPreprepareCommand(maxSequence++, getPriorSequenceNumber(c.getFragmentId()));
            collSequence.put(result.getSequenceNr(), result);
            PreprepareCommand seq = result.createPreprepareCommand();
            callbacks.sendToReplicas(seq);
        }
        return result;
    }
    
    private Transaction handlePreprepareCommand(PreprepareCommand c) {
        String clientOperationId = c.getClientOperationId();
        Transaction result;
        int sequence = c.getSequence();

        boolean knownFromClientOpId = collClientId.containsKey(clientOperationId);
        boolean knownFromSequence = collSequence.containsKey(sequence);

        if (knownFromClientOpId && knownFromSequence) {
            result = collClientId.get(clientOperationId);
            result.merge(collSequence.get(sequence));
        } else if (knownFromClientOpId && !knownFromSequence) {
            result = collClientId.get(clientOperationId);
            result.setDataFromPreprepareCommand(sequence, c.getPriorSequence());
        } else if (!knownFromClientOpId && knownFromSequence) {
            result = collSequence.get(sequence);
            result.setClientOperationId(clientOperationId);
        } else {
            /* initial network package */
            result = new Transaction(c, replicaId, f, this.callbacks);
        }

        if (!isPrimary()) {
            result.setDataFromPreprepareCommand(sequence, c.getPriorSequence());
        }

        /* after the prepare command the transaction should be known by both client-operation-id
         * as well as by the bft-internal sequence number */
        result.setPrepreparedReceived();
        collSequence.put(sequence, result);
        collClientId.put(clientOperationId, result);
        return result;
    }
    
    private Transaction handlePrepareCommand(PrepareCommand c) {
        Transaction result;
        int sequence = c.getSequence();

        if (collSequence.containsKey(sequence)) {
            result = collSequence.get(sequence);
        } else {
            result = new Transaction(c, replicaId, f, this.callbacks);
            collSequence.put(sequence, result);
        }

        try {
            result.addPrepareCommand(c);
        } catch (InconsistentResultsException e) {
            callbacks.replicasMightBeMalicous();
        }
        return result;
    }
    
    private Transaction handleCommitCommand(CommitCommand c) {
        Transaction result = collSequence.get(c.getSequence());
        result.addCommitCommand(c);
        return result;
    }

    public Transaction getTransaction(AbstractCommand msg) {

        Transaction result = null;
        lockCollections.lock();
        try {
            
            if (msg instanceof ClientFragmentCommand) {
                result = handleClientFragmentCommand((ClientFragmentCommand)msg);
            } else if (msg instanceof PreprepareCommand) {
                result = handlePreprepareCommand((PreprepareCommand)msg);
            } else if (msg instanceof PrepareCommand) {
                result = handlePrepareCommand((PrepareCommand)msg);
            } else if (msg instanceof CommitCommand) {
                result = handleCommitCommand((CommitCommand)msg);
            } else {
                callbacks.invalidMessageReceived(msg);
            }
        
            if (result != null) {
                result.lock();
            }
        } finally {
            lockCollections.unlock();
        }
        return result;
    }

    public void cleanupTransactions(Transaction mightDelete) {

        this.lockCollections.lock();
        try {
            if (mightDelete.tryMarkDelete()) {
                mightDelete.lock();
                collClientId.remove(mightDelete.getClientOperationId());
                collSequence.remove(mightDelete.getSequenceNr());
                /* free transaction */
                mightDelete.unlock();
            }
            
            /* search for preparable and commitable transactions */
            Iterator<Transaction> it = collSequence.values().iterator();
            while (it.hasNext()) {
                Transaction x = it.next();
                
                x.lock();
                
                if (x.tryAdvanceToPrepared(lastCommited)) {
                    lastCommited = Math.max(lastCommited, x.getPriorSequenceNr());
                    
                    if (x.tryAdvanceToCommited()) {
                        /* check if we should send a CHECKPOINT message */
                        checkpoints.addTransaction(x, x.getResult(), viewNr);
                        
                        newCommited(x.getSequenceNr());
                    }
                    
                    if (x.tryMarkDelete()) {
                        collClientId.remove(x.getClientOperationId());
                        it.remove();
                    }
                }
                x.unlock();
            }
        } finally {
            this.lockCollections.unlock();
        }
    }

    private int getPriorSequenceNumber(String fragmentId) {
        int priorSequence = -2;

        /* TODO: there could be sequence commands without fragment (bad timing...) */
        for (Transaction x : this.collSequence.values()) {
            if (fragmentId.equals(x.getFragmentId()) || x.getFragmentId() == null) {
                priorSequence = Math.max(priorSequence, x.getSequenceNr());
            }
        }

        return priorSequence;
    }
    
    public void checkCollections() {
        lockCollections.lock();
        try {
            if (collClientId.size() >= 100 || collSequence.size() >= 100) {
                logger.info("server: {} collClient: {} collSequence: {}", this.replicaId, collClientId.size(), collSequence.size());
            }
        } finally {
            lockCollections.unlock();
        }
    }
    
    public void advanceToEra(int era) {
        lockCollections.lock();
        try {
            if (this.viewNr <= era) {
                logger.warn("already in era {}", era);
            } else {
                this.viewNr = era;
                
                /* remove all non-client transactions and reset all client-ones */
                Iterator<Transaction> it = collSequence.values().iterator();
                while (it.hasNext()) {
                    Transaction t = it.next();
                    
                    t.lock();
                    if (t.hasClientInteraction()) {
                        /* sets state to INCOMING, clears collections */
                        t.reset();
                        
                        if (isPrimary()) {
                            this.callbacks.sendToReplicas(t.createPreprepareCommand());
                            
                            /* generates pre-prepare commands and sets state to prepared */
                            t.tryAdvanceToPreprepared(isPrimary());
                        }
                    } else {
                        /* delete transaction */
                        collClientId.remove(t.getClientOperationId());
                        it.remove();
                    }
                    
                    t.unlock();
                }
            }
        } finally {
            lockCollections.unlock();
        }
    }
    
    public int getLastCommited() {
        return this.lastCommited;
    }
    
    public int getViewNr() {
        return this.viewNr;
    }
    
    private boolean isPrimary() {
        return this.replicaId == (getViewNr() % (3*f + 1));
    }

    void newCommited(int sequenceNr) {
        this.lastCommited = Math.max(sequenceNr, this.lastCommited);
    }
}
