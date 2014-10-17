package at.archistar.bft.helper;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;

/**
 * this is a simple singleton helper class for hash creation
 * 
 * @author andy
 */
public class DigestHelper {

    private static MessageDigest md = null;

    private static synchronized void createMd() {
        if (md == null) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                assert (false);
            }
        }
    }

    public static synchronized String createResultHash(int sequence, byte[] data) {

        createMd();

        md.update(ByteBuffer.allocate(4).putInt(sequence).array());
        if (data != null) {
            md.update(data);
        }

        /* store the hash */
        return (new HexBinaryAdapter()).marshal(md.digest());
    }

    public static synchronized String getClientOperationId(int clientId, int clientSequence) {

        createMd();

        md.update(ByteBuffer.allocate(4).putInt(clientId).array());
        md.update(ByteBuffer.allocate(4).putInt(clientSequence).array());

        return (new HexBinaryAdapter()).marshal(md.digest());
    }
}
