package at.archistar.bft.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.archistar.bft.messages.AbstractCommand;
import at.archistar.bft.messages.AdvanceEraCommand;
import at.archistar.bft.messages.CheckpointMessage;
import at.archistar.bft.messages.ClientCommand;
import at.archistar.bft.messages.IntraReplicaCommand;
import java.util.HashSet;
import java.util.Set;

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
    
    private TransactionManager currentEra;
    
    private final Set<TransactionManager> oldEras = new HashSet<>();

    public BftEngine(int replicaId, int viewNr, int f, BftEngineCallbacks callbacks) {
        this.callbacks = callbacks;
        this.f = f;
        this.replicaId = replicaId;
        this.checkpoints = new CheckpointManager(replicaId, callbacks, f);
        this.currentEra = new TransactionManager(replicaId, viewNr,  f, callbacks, checkpoints);
    }

    
    public BftEngine(int replicaId, int f, BftEngineCallbacks callbacks) {
        this(replicaId, 0, f, callbacks);
    }

    public void processClientCommand(ClientCommand cmd) {
        Transaction t = this.currentEra.getTransaction(cmd);
        handleMessage(t, cmd);
        t.unlock();
        this.currentEra.cleanupTransactions(t);
    }

    public void processIntraReplicaCommand(IntraReplicaCommand cmd) {
        if (!checkEraOfMessage((IntraReplicaCommand) cmd)) {
            logger.warn("message from old era detected");
        } else {
            if (cmd instanceof CheckpointMessage) {
                addCheckpointMessage((CheckpointMessage) cmd);
            } else if (cmd instanceof AdvanceEraCommand) {
                newEra(((AdvanceEraCommand) cmd).getNewEra());
            } else {
                /* this locks t */
                Transaction t = this.currentEra.getTransaction(cmd);
                handleMessage(t, cmd);
                t.unlock();
                this.currentEra.cleanupTransactions(t);
            }
        }
    }

    private boolean checkEraOfMessage(IntraReplicaCommand cmd) {
        return cmd.getViewNr() >= this.currentEra.getViewNr();
    }

    public boolean isPrimary() {
        return this.replicaId == (this.currentEra.getViewNr() % (3*f + 1));
    }
    
    private void newEra(int newEra) {
        /* backup old era */
        this.oldEras.add(currentEra);
        
        /* create a new era */
        currentEra = currentEra.createNewEra(newEra);
    }
    
    /** mostly to allow for stubbing */
    TransactionManager getCurrentEra() {
        return this.currentEra;
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
        this.currentEra.checkCollections();
    }

    public void tryAdvanceEra() {
        int viewNr = this.currentEra.getViewNr();
        AdvanceEraCommand cmd = new AdvanceEraCommand(replicaId, -1, viewNr, viewNr + 1);
        this.callbacks.sendToReplicas(cmd);
        newEra(viewNr + 1);
    }

    private void handleMessage(Transaction t, AbstractCommand msg) {

        t.tryAdvanceToPreprepared(isPrimary());
        t.tryAdvanceToPrepared(this.currentEra.getLastCommited());
        if (t.tryAdvanceToCommited()) {
            /* check if we should send a CHECKPOINT message */
            checkpoints.addTransaction(t, t.getResult(), this.currentEra.getViewNr());
            this.currentEra.newCommited(t.getSequenceNr());
        }
        t.tryMarkDelete();
    }
}
