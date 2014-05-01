package at.archistar.bft.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugInformationGatherer {

	private int replicaId;
	
	private double lifetime = 0;
	
	private double count = 0;
	
	private Logger logger = LoggerFactory.getLogger(DebugInformationGatherer.class);

	public DebugInformationGatherer(int replicaId) {
		this.replicaId = replicaId;
	}
	
	public void outputDebugInformation() {
		logger.info("successful transactions: {}", count);
		logger.info("server: {} transaction length: {}ms", this.replicaId, Math.round(lifetime/count));
	}
	
	public void addFinishedTransactionStats(double lifetime) {
		count++;
		lifetime += lifetime;
	}
}
