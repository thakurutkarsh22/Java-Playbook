package LLD.LldQuestions.OrderMatchingEngine.models;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/*
 * The output of a successful match: BUY id `buyOrderId` matched with SELL
 * id `sellOrderId` at `priceCents` for `quantity` shares.
 *
 * Immutable. Acts as the audit record handed back to clients and pushed
 * onto the public tape.
 */
public final class Trade {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final String symbol;
    private final long buyOrderId;
    private final long sellOrderId;
    private final long priceCents;
    private final long quantity;
    private final Instant timestamp;

    public Trade(String symbol,
                 long buyOrderId,
                 long sellOrderId,
                 long priceCents,
                 long quantity) {
        this.id = NEXT_ID.incrementAndGet();
        this.symbol = symbol;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.priceCents = priceCents;
        this.quantity = quantity;
        this.timestamp = Instant.now();
    }

    public long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public long getBuyOrderId() {
        return buyOrderId;
    }

    public long getSellOrderId() {
        return sellOrderId;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public long getQuantity() {
        return quantity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Trade#" + id + " " + symbol + " " + quantity
                + " @ $" + priceCents / 100.0
                + " (BUY=" + buyOrderId + ", SELL=" + sellOrderId + ")";
    }
}
