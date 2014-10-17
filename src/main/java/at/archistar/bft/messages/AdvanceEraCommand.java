package at.archistar.bft.messages;

/**
 * this command is used if the whole BFT system (all connected BFT state
 * machines) should perform an era-change, ie. compare all already retrieved
 * transactions
 * 
 * @author andy
 */
public class AdvanceEraCommand extends IntraReplicaCommand {

    private static final long serialVersionUID = 4254847418079542679L;

    private final int newEra;

    public AdvanceEraCommand(int sourceReplicaId, int sequenceId, int viewNr, int newEra) {
        super(sourceReplicaId, sequenceId, viewNr);
        this.newEra = newEra;
    }

    public int getNewEra() {
        return this.newEra;
    }
}
