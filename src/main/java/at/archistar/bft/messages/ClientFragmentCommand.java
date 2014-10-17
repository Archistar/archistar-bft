package at.archistar.bft.messages;

/**
 * This command extends a normal ClientCommand with a client fragment id. This
 * id is used within algorithms to allow for finer versioning and/or concurrency
 * 
 * @author andy
 */
public abstract class ClientFragmentCommand extends ClientCommand {

    private static final long serialVersionUID = -8569203487109177534L;

    private final String fragmentId;

    public ClientFragmentCommand(int clientId, int clientSequence, String fragmentid) {
        super(clientId, clientSequence);
        this.fragmentId = fragmentid;
    }

    public String getFragmentId() {
        return this.fragmentId;
    }
}
