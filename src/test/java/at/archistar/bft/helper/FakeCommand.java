package at.archistar.bft.helper;

import at.archistar.bft.messages.ClientFragmentCommand;

/**
 * This command is used by a client to forward an update/write operation to the
 * replicas.
 *
 * @author andy
 */
public class FakeCommand extends ClientFragmentCommand {

    private static final long serialVersionUID = -6269916515655616482L;

    public FakeCommand(int clientId, int clientSequence, String fragmentId, byte[] data) {

        super(clientId, clientSequence, fragmentId);
        this.payload = data;
    }

    public byte[] getData() {
        return this.payload;
    }

    public String toString() {
        return getClientSequence() + ": write";
    }
}
