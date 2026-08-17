package LLD.LldQuestions.ConnectionPool.exceptions;

/*
 * Thrown by ConnectionPool.acquire() after shutdown() has been called.
 * Distinct from AcquireTimeoutException because the failure mode is
 * permanent -- no retry will succeed.
 */
public class PoolClosedException extends RuntimeException {
    public PoolClosedException(String message) {
        super(message);
    }
}
