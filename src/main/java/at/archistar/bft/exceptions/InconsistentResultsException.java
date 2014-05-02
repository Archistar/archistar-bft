package at.archistar.bft.exceptions;

/**
 * this exception is thrown if the resutl of one replica does not match the
 * expected results (determined through the other replicas)
 *
 * @author andy
 */
public class InconsistentResultsException extends Exception {

    private static final long serialVersionUID = -259378201508105072L;

}
