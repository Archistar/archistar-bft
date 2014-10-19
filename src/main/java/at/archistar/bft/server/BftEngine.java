package at.archistar.bft.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.archistar.bft.messages.AbstractCommand;
import at.archistar.bft.messages.AdvanceEraCommand;
import at.archistar.bft.messages.CheckpointMessage;
import at.archistar.bft.messages.ClientCommand;
import at.archistar.bft.messages.IntraReplicaCommand;

/**
 * this class encapsulates a whole BFT engine (as would be seen within one
 * replica). The whole BFT system consists of multiple distributed BFt engines
 * 
 * @author andy
 */
public class BftEngine {

    private final BftEngineCallbacks callbacks;

    private final int f;

    private final int replicaId;

    private final CheckpointManager checkpoints;

    private final Logger logger = LoggerFactory.getLogger(BftEngine.class);
    
    private final TransactionManager transactions;

    public BftEngine(int replicaId, int f, BftEngineCallbacks callbacks) {
        this.callbacks = callbacks;
        this.f = f;
        this.replicaId = replicaId;
        this.checkpoints = new CheckpointManager(replicaId, callbacks, f);
        this.transactions = new TransactionManager(replicaId, f, callbacks, checkpoints);
    }

    public void processClientCommand(ClientCommand cmd) {
        Transaction t = this.transactions.getTransaction(cmd);
        handleMessage(t, cmd);
        t.unlock();
        this.transactions.cleanupTransactions(t);
    }

    public void processIntraReplicaCommand(IntraReplicaCommand cmd) {
        if (!checkEraOfMessage((IntraReplicaCommand) cmd)) {
            logger.warn("message from old era detected");
        } else {
            if (cmd instanceof CheckpointMessage) {
                addCheckpointMessage((CheckpointMessage) cmd);
            } else if (cmd instanceof AdvanceEraCommand) {
                this.transactions.advanceToEra(((AdvanceEraCommand) cmd).getNewEra());
            } else {
                /* this locks t */
                Transaction t = this.transactions.getTransaction(cmd);
                handleMessage(t, cmd);
                t.unlock();
                this.transactions.cleanupTransactions(t);
            }
        }
    }

    private boolean checkEraOfMessage(IntraReplicaCommand cmd) {
        return cmd.getViewNr() >= this.transactions.getViewNr();
    }

    public boolean isPrimary() {
        return this.replicaId == (this.transactions.getViewNr() % (3*f + 1));
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
        this.transactions.checkCollections();
    }

    public void tryAdvanceEra() {
        int viewNr = this.transactions.getViewNr();
        AdvanceEraCommand cmd = new AdvanceEraCommand(replicaId, -1, viewNr, viewNr + 1);
        this.callbacks.sendToReplicas(cmd);
        this.transactions.advanceToEra(viewNr + 1);
    }

    private void handleMessage(Transaction t, AbstractCommand msg) {

        t.tryAdvanceToPreprepared(isPrimary());
        t.tryAdvanceToPrepared(this.transactions.getLastCommited());
        if (t.tryAdvanceToCommited()) {
            /* check if we should send a CHECKPOINT message */
            checkpoints.addTransaction(t, t.getResult(), this.transactions.getViewNr());
            this.transactions.newCommited(t.getSequenceNr());
        }
        t.tryMarkDelete();
    }
}
