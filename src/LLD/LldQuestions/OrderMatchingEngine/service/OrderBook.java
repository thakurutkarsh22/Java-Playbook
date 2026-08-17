package LLD.LldQuestions.OrderMatchingEngine.service;

import LLD.LldQuestions.OrderMatchingEngine.models.Order;
import LLD.LldQuestions.OrderMatchingEngine.models.Side;
import LLD.LldQuestions.OrderMatchingEngine.models.Trade;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                            OrderBook                                   ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Per-symbol limit-order book + matching engine. Implements PRICE-TIME
 * priority matching:
 *
 *   PRICE  - higher BUY prices match first (most willing to pay).
 *            lower  SELL prices match first (most willing to accept).
 *   TIME   - within the same price, FIFO by order id (which is
 *            monotonically increasing on submission).
 *
 * **This class is single-threaded by contract.** It is mutated ONLY from
 * the SymbolMatcher's single-thread executor. There are no locks, no
 * volatiles, no atomics. The matcher serializes access; we trust it.
 *
 * Why is single-threading the RIGHT answer here?
 * ──────────────────────────────────────────────
 *   - The critical section is ~the entire match operation (~µs of work).
 *   - The data structure (price ladder + queue per level) has so much
 *     internal mutation per match that a lock would have to be held
 *     across the whole thing anyway.
 *   - At that point the lock-protected version IS single-threaded; the
 *     lock just adds overhead.
 *   - Read the OrderMatchingEngine `Interview-QA.md` Q1 for the full
 *     argument.
 *
 * Data structure
 * ──────────────
 *   bids : TreeMap<priceCents, Deque<Order>>, keys sorted DESCENDING
 *          (so firstKey() is the highest bid)
 *   asks : TreeMap<priceCents, Deque<Order>>, keys sorted ASCENDING
 *          (so firstKey() is the lowest ask)
 *
 *   At each price level, the Deque is FIFO: addLast on submission,
 *   pollFirst on match. This gives time-priority within a price level.
 *
 * Match algorithm
 * ───────────────
 *   on incoming BUY at limit price P, quantity Q:
 *      while Q > 0 and asks.firstKey() <= P:
 *          take the oldest ask at firstKey
 *          trade min(Q, ask.remaining) at ask price
 *          remove fully-filled order from book
 *      if Q > 0:
 *          rest the remainder into bids
 *
 *   symmetric for SELL.
 *
 * Key correctness invariants
 * ──────────────────────────
 *   1. No order is "in the book" while it still has remainingQuantity > 0
 *      AND the other side has a matchable counterpart. The match loop runs
 *      until either Q=0 or no match is possible.
 *   2. Trades execute at the RESTING order's price (the maker's price).
 *      This is exchange convention -- the taker takes the maker's quote.
 *   3. Time-priority preserved: a Deque at a level is FIFO.
 */
public class OrderBook {

    private final String symbol;

    /*
     * BUY ladder: highest price first. We use a comparator-reversed
     * Long key so firstKey() returns the highest bid.
     */
    private final NavigableMap<Long, Deque<Order>> bids
            = new TreeMap<>(Comparator.reverseOrder());

    /*
     * SELL ladder: lowest price first. Default natural order.
     */
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /*
     * Submit an order and immediately match against the opposing side.
     * Trade results are reported to `tradeSink` as they happen.
     *
     * Single-threaded: caller must be the matcher thread.
     */
    public void submit(Order incoming, Consumer<Trade> tradeSink) {
        if (incoming.getSide() == Side.BUY) {
            match(incoming, asks, /*priceCheck=*/ ask -> ask <= incoming.getLimitPriceCents(),
                    tradeSink);
            if (!incoming.isFilled()) {
                rest(bids, incoming);
            }
        } else {
            match(incoming, bids, /*priceCheck=*/ bid -> bid >= incoming.getLimitPriceCents(),
                    tradeSink);
            if (!incoming.isFilled()) {
                rest(asks, incoming);
            }
        }
    }

    /*
     * Match `incoming` against the opposite ladder until either:
     *   - incoming is fully filled, OR
     *   - no level in `opposing` satisfies the priceCheck.
     */
    private void match(Order incoming,
                       NavigableMap<Long, Deque<Order>> opposing,
                       java.util.function.LongPredicate priceCheck,
                       Consumer<Trade> tradeSink) {
        while (!incoming.isFilled() && !opposing.isEmpty()) {
            long topPrice = opposing.firstKey();
            if (!priceCheck.test(topPrice)) {
                break;
            }
            Deque<Order> level = opposing.get(topPrice);
            // resting orders at this level, FIFO
            while (!incoming.isFilled() && !level.isEmpty()) {
                Order resting = level.peekFirst();
                long qty = Math.min(incoming.getRemainingQuantity(),
                        resting.getRemainingQuantity());
                // Maker price (resting.limit) is the execution price.
                long execPrice = resting.getLimitPriceCents();
                incoming.fill(qty);
                resting.fill(qty);
                long buyId = incoming.getSide() == Side.BUY ? incoming.getId() : resting.getId();
                long sellId = incoming.getSide() == Side.SELL ? incoming.getId() : resting.getId();
                tradeSink.accept(new Trade(symbol, buyId, sellId, execPrice, qty));
                if (resting.isFilled()) {
                    level.pollFirst();
                }
            }
            if (level.isEmpty()) {
                opposing.remove(topPrice);
            }
        }
    }

    private void rest(NavigableMap<Long, Deque<Order>> ladder, Order order) {
        ladder.computeIfAbsent(order.getLimitPriceCents(), k -> new ArrayDeque<>())
              .addLast(order);
    }

    // ──── diagnostics ───────────────────────────────────────────────────────

    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    public int depth(Side side) {
        NavigableMap<Long, Deque<Order>> book = side == Side.BUY ? bids : asks;
        int n = 0;
        for (Deque<Order> level : book.values()) {
            n += level.size();
        }
        return n;
    }

    /*
     * Read-only snapshot of all resting orders. Safe to call only from the
     * matcher thread.
     */
    public List<Order> snapshot(Side side) {
        java.util.List<Order> out = new java.util.ArrayList<>();
        NavigableMap<Long, Deque<Order>> book = side == Side.BUY ? bids : asks;
        for (Deque<Order> level : book.values()) {
            out.addAll(level);
        }
        return out;
    }
}
