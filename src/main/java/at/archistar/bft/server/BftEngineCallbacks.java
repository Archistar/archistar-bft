package at.archistar.bft.server;

import at.archistar.bft.messages.AbstractCommand;
import at.archistar.bft.messages.CheckpointMessage;
import at.archistar.bft.messages.ClientCommand;
import at.archistar.bft.messages.IntraReplicaCommand;
import at.archistar.bft.messages.TransactionResult;

/**
 * these are all callbacks that the server (that instantiates a BFT engine
 * should supply
 * 
 * @author andy
 */
public interface BftEngineCallbacks {

    /**
     * notify server that an invalid (or unknown) command was received. This
     * might signify an malicous attacker or network problems (or more likely
     * an implementation error)
     * 
     * @param msg the received message
     */
    void invalidMessageReceived(AbstractCommand msg);

    /**
     * this signals that the state machine believes that one or more replicas
     * have become corrupted
     */
    void replicasMightBeMalicous();

    /**
     * send a message to all replicas
     * 
     * @param cmd the to be sent message
     */
    void sendToReplicas(IntraReplicaCommand cmd);

    /**
     * a client command should be executed by the server
     * @param cmd the to be executed client command
     * @return the result of the client command
     */
    byte[] executeClientCommand(ClientCommand cmd);

    /**
     * the state machine received a checkpoint message that wasn't fitting
     * @param msg the weird checkpoint message
     */
    void invalidCheckpointMessage(CheckpointMessage msg);

    /**
     * send an result back to a client
     * 
     * @param transactionResult the to-be-sent result
     */
    void answerClient(TransactionResult transactionResult);
}
