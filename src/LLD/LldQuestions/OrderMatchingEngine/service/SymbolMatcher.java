package LLD.LldQuestions.OrderMatchingEngine.service;

import LLD.LldQuestions.OrderMatchingEngine.models.Order;
import LLD.LldQuestions.OrderMatchingEngine.models.Trade;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                          SymbolMatcher                                 ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * One matcher per symbol. Each matcher owns:
 *   - its own OrderBook
 *   - its own SingleThreadExecutor ("the matcher thread")
 *   - a tape (ConcurrentLinkedQueue<Trade>) for public read-side consumers
 *
 * The matcher thread is THE ONLY thread that ever mutates the OrderBook.
 * External callers submit orders via `submit()` which returns a
 * CompletableFuture<Order> -- they don't see the book at all.
 *
 * This is the ACTOR PATTERN applied to a different domain than the
 * ElevatorSystem (project #7):
 *   - Elevator: actor because the resource has continuous internal state
 *     (the car ticks every interval).
 *   - SymbolMatcher: actor because EVERY operation contends for the same
 *     data structure. A lock would have to be held for the entire match,
 *     so the "locked" version IS single-threaded with extra cost.
 *
 * The matching engine is the **anti-pattern lesson** of the curriculum:
 *
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  "Locks are NOT always the right answer for shared state. When  │
 *   │   the critical section is the entire operation, single-threaded │
 *   │   per shard wins. Shard by symbol; one thread per shard. NASDAQ │
 *   │   does this. CME does this. So does our 200-line LLD demo."     │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * Concurrency primitives
 * ──────────────────────
 *   matchExecutor       : SingleThreadExecutor (ONE thread per symbol)
 *   tape                : ConcurrentLinkedQueue<Trade> (lock-free multi-reader)
 *   lastTradePrice      : AtomicReference<Long> (read by ticker subscribers)
 *
 * No ReentrantLock. No synchronized. No volatile inside OrderBook.
 */
public class SymbolMatcher {

    private final String symbol;
    private final OrderBook book;
    private final ExecutorService matchExecutor;

    /*
     * Public tape: every executed trade goes here. Lock-free reads via
     * iterators (weakly consistent). The matcher thread is the sole
     * writer; readers can be on any thread.
     */
    private final ConcurrentLinkedQueue<Trade> tape = new ConcurrentLinkedQueue<>();

    /*
     * Last trade price for ticker symbols. AtomicReference so subscribers
     * can read without any synchronization.
     */
    private final AtomicReference<Long> lastTradePriceCents = new AtomicReference<>();

    public SymbolMatcher(String symbol) {
        this.symbol = symbol;
        this.book = new OrderBook(symbol);
        this.matchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "match-" + symbol);
            t.setDaemon(true);
            return t;
        });
    }

    public String getSymbol() {
        return symbol;
    }

    /*
     * Submit an order. Returns a future that completes when the order has
     * been (partially or fully) matched and rested.
     *
     * The submission itself is non-blocking: we just enqueue a Runnable
     * onto the matcher executor. The caller chooses whether to await.
     */
    public CompletableFuture<Order> submit(Order order) {
        if (!order.getSymbol().equals(symbol)) {
            throw new IllegalArgumentException(
                    "Order symbol " + order.getSymbol() + " != matcher symbol " + symbol);
        }
        CompletableFuture<Order> result = new CompletableFuture<>();
        matchExecutor.submit(() -> {
            try {
                Consumer<Trade> sink = trade -> {
                    tape.offer(trade);
                    lastTradePriceCents.set(trade.getPriceCents());
                };
                book.submit(order, sink);
                result.complete(order);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    public OrderBook getBook() {
        return book;
    }

    /*
     * Drain the tape into a List<Trade>. Useful for end-of-session reports.
     */
    public java.util.List<Trade> drainTape() {
        java.util.List<Trade> out = new java.util.ArrayList<>();
        Trade t;
        while ((t = tape.poll()) != null) {
            out.add(t);
        }
        return out;
    }

    public Long getLastTradePriceCents() {
        return lastTradePriceCents.get();
    }

    public void shutdown() {
        matchExecutor.shutdown();
    }
}
