package at.archistar.bft.client;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import at.archistar.bft.exceptions.InconsistentResultsException;
import at.archistar.bft.messages.TransactionResult;

public class ResultManager {

    private Map<Integer, ClientResult> results;

    private final Lock lock = new ReentrantLock();

    public ResultManager() {
        this.results = new HashMap<Integer, ClientResult>();
    }

    /**
     * Add a new client operation (for which we need to wait)
     *
     * @param f faulty replica count
     * @param clientId our client id
     * @param clientSequence our operations sequence id
     * @return a ClientResult object on which can be waited upon
     */
    public ClientResult addClientOperation(int f, int clientId, int clientSequence) {

        ClientResult result = new ClientResult(f, clientId, clientSequence);
        lock.lock();
        this.results.put(clientSequence, result);
        lock.unlock();
        return result;
    }

    public void addClientResponse(int clientId, int clientSequence, TransactionResult tx) throws InconsistentResultsException {

        lock.lock();
        ClientResult result = this.results.get(clientSequence);

        if (result != null) {
            result.addResult(clientId, clientSequence, tx);
        } else {
            assert (false);
        }
        lock.unlock();
    }
}
