package LLD.LldQuestions.AtmTransfer.models;

import java.time.Instant;

/*
 * Immutable record of a completed transfer. Returned from
 * TransferService.transfer(); also stored against the idempotency key so a
 * retry can return the EXACT same record without re-running the transfer.
 */
public final class Transfer {

    private final String id;
    private final String fromAccountId;
    private final String toAccountId;
    private final long amountCents;
    private final Instant createdAt;
    private final TransferStatus status;

    public Transfer(String id,
                    String fromAccountId,
                    String toAccountId,
                    long amountCents,
                    TransferStatus status) {
        this.id = id;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountCents = amountCents;
        this.createdAt = Instant.now();
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public String getToAccountId() {
        return toAccountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TransferStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "Transfer{" + id + " " + fromAccountId + " -> " + toAccountId
                + " $" + amountCents / 100.0 + " " + status + "}";
    }
}
