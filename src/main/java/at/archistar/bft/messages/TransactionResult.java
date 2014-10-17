package at.archistar.bft.messages;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;

import javax.xml.bind.DatatypeConverter;

/**
 * This is used by the replicas to signal back an operation's result to the
 * client.
 *
 * @author andy
 */
public class TransactionResult extends ClientCommand {

    private static final long serialVersionUID = 7045695496206304165L;

    private final int replicaId;

    public TransactionResult(int clientId, int replicaId, int sequenceId, byte[] payload) {
        super(clientId, sequenceId);
        if (payload != null) {
            this.payload = payload.clone();
        } else {
            this.payload = null;
        }
        this.replicaId = replicaId;
    }

    public TransactionResult(ClientCommand clientCmd, int serverid, byte[] payload) {
        this(clientCmd.getClientId(), serverid, clientCmd.getClientSequence(), payload);
    }

    @Override
    public String toString() {
        return getClientId() + "/" + getClientSequence();
    }

    public int getReplicaId() {
        return this.replicaId;
    }

    public String humanizeResult() {
        return DatatypeConverter.printHexBinary(this.payload);
    }

    @Override
    @SuppressFBWarnings("EI_EXPOSE_REP")
    public byte[] getPayload() {
        return payload;
    }

    public boolean verifyContent(TransactionResult tx) {
        return Arrays.equals(this.payload, tx.getPayload());
    }
}
