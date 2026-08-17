package LLD.LldQuestions.ConnectionPool.exceptions;

/*
 * Thrown by ConnectionPool.acquire() when no connection becomes available
 * within the configured timeout. Unchecked because the only correct caller
 * behavior is to surface "service unavailable" to the user -- recovery is
 * not something the caller can perform.
 */
public class AcquireTimeoutException extends RuntimeException {
    public AcquireTimeoutException(String message) {
        super(message);
    }
}
