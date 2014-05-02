package at.archistar.bft.messages;

public class CommitCommand extends IntraReplicaCommand {

    private static final long serialVersionUID = 5922218111327104543L;

    public CommitCommand(int viewNr, int sequence, int replicaId) {
        super(replicaId, sequence, viewNr);
    }

    @Override
    public String toString() {
        return getSequence() + ": commit";
    }
}
