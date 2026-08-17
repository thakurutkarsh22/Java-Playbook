package LLD.LldQuestions.OrderMatchingEngine.models;

import java.util.concurrent.atomic.AtomicLong;

/*
 * A limit order: "buy/sell up to `quantity` shares of `symbol` at price
 * `limitPriceCents` or better".
 *
 *   id                : monotonic; used as the secondary key for time-priority.
 *   symbol            : "AAPL", "MSFT", etc. Each symbol matches independently.
 *   side              : BUY or SELL
 *   limitPriceCents   : price in cents (always integer; never use double for money)
 *   quantity          : initial quantity (immutable)
 *   remainingQuantity : mutated as the order is partially filled; mutated ONLY
 *                       on the matcher thread, so plain long.
 *   submittedAtNanos  : timestamp; not used for matching priority directly
 *                       (id ordering gives time-priority for free) but useful
 *                       for telemetry.
 */
public class Order {

    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    private final long id;
    private final String symbol;
    private final Side side;
    private final long limitPriceCents;
    private final long quantity;
    private long remainingQuantity;
    private final long submittedAtNanos;

    public Order(String symbol, Side side, long limitPriceCents, long quantity) {
        if (limitPriceCents <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("price and quantity must be > 0");
        }
        this.id = NEXT_ID.incrementAndGet();
        this.symbol = symbol;
        this.side = side;
        this.limitPriceCents = limitPriceCents;
        this.quantity = quantity;
        this.remainingQuantity = quantity;
        this.submittedAtNanos = System.nanoTime();
    }

    public long getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public Side getSide() {
        return side;
    }

    public long getLimitPriceCents() {
        return limitPriceCents;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getSubmittedAtNanos() {
        return submittedAtNanos;
    }

    public boolean isFilled() {
        return remainingQuantity == 0;
    }

    /*
     * Apply a fill of `qty`. Caller (the matcher) is single-threaded per
     * symbol, so no synchronization needed.
     */
    public void fill(long qty) {
        if (qty <= 0 || qty > remainingQuantity) {
            throw new IllegalArgumentException("invalid fill qty=" + qty
                    + " remaining=" + remainingQuantity);
        }
        remainingQuantity -= qty;
    }

    @Override
    public String toString() {
        return "Order{#" + id + " " + side + " " + remainingQuantity + "/" + quantity
                + " " + symbol + " @ $" + limitPriceCents / 100.0 + "}";
    }
}
