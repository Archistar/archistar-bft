package at.archistar.bft.client;

import java.util.HashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import at.archistar.bft.exceptions.InconsistentResultsException;
import at.archistar.bft.messages.TransactionResult;

/**
 * Helper class used to synchronously wait for client operation finish.
 *
 * @author andy
 */
public class ClientResult {

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    /**
     * the configured faulty replica amount
     */
    private final int f;

    /**
     * our client id, used for checks
     */
    private final int clientId;

    /**
     * our client (operation) sequence, used for checks
     */
    private final int clientSequence;

    /**
     * map with all retrieved results
     */
    private final HashMap<Integer, TransactionResult> results = new HashMap<>();

    /**
     * note: always returns a locked ClientResult
     */
    public ClientResult(int f, int clientId, int clientSequence) {
        this.f = f;
        this.clientId = clientId;
        this.clientSequence = clientSequence;

        lock.lock();
    }

    /**
     * adds a received replica anser
     *
     * @param clientId for which client?
     * @param clientSequence for which sequence (== operation)?
     * @param tx the result
     * @return true if enough results for output determination were received
     * @throws InconsistentResultsException is thrown if there seems to be a
     * faulty replica
     */
    public boolean addResult(int clientId, int clientSequence, TransactionResult tx) throws InconsistentResultsException {

        lock.lock();
        boolean result = false;
        
        try {
            /* consistency checks */
            if (this.clientId != clientId || this.clientSequence != clientSequence) {
                throw new InconsistentResultsException();
            }
            
            results.put(tx.getReplicaId(), tx);
            
            /* NOTE: this condition is highly dependent upon the used secret-sharing mechanism */
            if (results.size() >= (2 * f + 1)) {
                condition.signal();
                result = true;
            }
        } finally {
            lock.unlock();
        }
        return result;
    }

    /**
     * note: expects ClientResult to be locked
     */
    public void waitForEnoughAnswers() {
        while (results.size() < (f + 1)) {
            try {
                condition.await();
                lock.unlock();
            } catch (InterruptedException e) {
                assert (false);
                e.printStackTrace();
            }
        }
    }

    public boolean containsDataForServer(int bftId) {
        return this.results.containsKey(bftId);
    }

    public byte[] getDataForServer(int bftId) {
        return this.results.get(bftId).getPayload();
    }
}
