package at.archistar.bft.server;

import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.archistar.bft.exceptions.InconsistentResultsException;
import at.archistar.bft.messages.AbstractCommand;
import at.archistar.bft.messages.AdvanceEraCommand;
import at.archistar.bft.messages.CheckpointMessage;
import at.archistar.bft.messages.ClientCommand;
import at.archistar.bft.messages.ClientFragmentCommand;
import at.archistar.bft.messages.CommitCommand;
import at.archistar.bft.messages.IntraReplicaCommand;
import at.archistar.bft.messages.PrepareCommand;
import at.archistar.bft.messages.PreprepareCommand;

/**
 * this class encapsulates a whole BFT engine (as would be seen within one
 * replica). The whole BFT system consists of multiple distributed BFt engines
 * 
 * @author andy
 */
public class BftEngine {

    private final BftEngineCallbacks callbacks;

    private int maxSequence = 0;

    private final int f;

    private int viewNr = 0;

    private final int replicaId;

    private int lastCommited = -1;

    /**
     * (client-side operation id) -> transaction mapping
     */
    private final SortedMap<String, Transaction> collClientId;

    /**
     * (internal id aka. sequence) -> transaction mapping
     */
    private final SortedMap<Integer, Transaction> collSequence;

    private final ReentrantLock lockCollections = new ReentrantLock();

    private final CheckpointManager checkpoints;

    private final Logger logger = LoggerFactory.getLogger(BftEngine.class);

    public BftEngine(int replicaId, int f, BftEngineCallbacks callbacks) {
        this.callbacks = callbacks;
        this.f = f;
        this.collClientId = new TreeMap<>();
        this.collSequence = new TreeMap<>();
        this.replicaId = replicaId;
        this.checkpoints = new CheckpointManager(replicaId, callbacks, f);
    }

    public void processClientCommand(ClientCommand cmd) {
        Transaction t = getTransaction(cmd);
        handleMessage(t, cmd);
        t.unlock();
        cleanupTransactions(t);
    }

    public void processIntraReplicaCommand(IntraReplicaCommand cmd) {
        if (!checkEraOfMessage((IntraReplicaCommand) cmd)) {
            logger.warn("message from old era detected");
        } else {
            if (cmd instanceof CheckpointMessage) {
                addCheckpointMessage((CheckpointMessage) cmd);
            } else if (cmd instanceof AdvanceEraCommand) {
                advanceToEra(((AdvanceEraCommand) cmd).getNewEra());
            } else {
                /* this locks t */
                Transaction t = getTransaction(cmd);
                handleMessage(t, cmd);
                t.unlock();
                cleanupTransactions(t);
            }
        }
    }

    private boolean checkEraOfMessage(IntraReplicaCommand cmd) {
        return cmd.getViewNr() >= viewNr;
    }

    public boolean isPrimary() {
        return this.replicaId == (viewNr % (3*f + 1));
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

    private Transaction getTransaction(AbstractCommand msg) {

        Transaction result = null;
        lockCollections.lock();
        try {
            
            if (msg instanceof ClientFragmentCommand) {
                ClientFragmentCommand c = (ClientFragmentCommand) msg;
                String clientOperationId = c.getClientOperationId();
                
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
            } else if (msg instanceof PreprepareCommand) {
                PreprepareCommand c = (PreprepareCommand) msg;
                
                String clientOperationId = c.getClientOperationId();
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
            } else if (msg instanceof PrepareCommand) {
                PrepareCommand c = (PrepareCommand) msg;
                
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
            } else if (msg instanceof CommitCommand) {
                CommitCommand c = (CommitCommand) msg;
                result = collSequence.get(c.getSequence());
                result.addCommitCommand(c);
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

    private void cleanupTransactions(Transaction mightDelete) {

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
                        
                        lastCommited = Math.max(x.getSequenceNr(), lastCommited);
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

    private void addCheckpointMessage(CheckpointMessage msg) {
        this.checkpoints.addCheckpointMessage(msg);
    }

    /**
     * this outputs the collection count if it is over a treshold. This
     * typically identifies problems with the locking (or thread scheduling)
     * code
     */
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

    private void advanceToEra(int era) {
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

    public void tryAdvanceEra() {
        AdvanceEraCommand cmd = new AdvanceEraCommand(replicaId, -1, viewNr, viewNr + 1);
        this.callbacks.sendToReplicas(cmd);
        advanceToEra(viewNr + 1);
    }

    private void handleMessage(Transaction t, AbstractCommand msg) {

        t.tryAdvanceToPreprepared(isPrimary());
        t.tryAdvanceToPrepared(lastCommited);
        if (t.tryAdvanceToCommited()) {
            /* check if we should send a CHECKPOINT message */
            checkpoints.addTransaction(t, t.getResult(), viewNr);

            lastCommited = Math.max(t.getSequenceNr(), lastCommited);
        }
        t.tryMarkDelete();
    }
}
