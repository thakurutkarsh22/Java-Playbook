package LLD.LldQuestions.OrderMatchingEngine.service;

import LLD.LldQuestions.OrderMatchingEngine.models.Order;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                         MatchingEngine                                 ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Top-level facade. Holds a SymbolMatcher per symbol; routes incoming
 * orders to the matcher for their symbol. Different symbols match in
 * parallel because each symbol has its own thread.
 *
 * Concurrency model
 * ─────────────────
 *   - The facade itself is lock-free. It uses ConcurrentHashMap.computeIfAbsent
 *     to lazily create a SymbolMatcher per symbol on first use. Racing
 *     callers on a brand-new symbol still see the SAME matcher.
 *
 * Throughput model
 * ────────────────
 *   - Throughput per symbol: ~1M orders/sec (single-threaded matching
 *     with O(log price levels) per match -- bounded by L1/L2 cache).
 *   - Throughput across symbols: linear in symbol count. 100 symbols at
 *     1M/s each ≈ 100M/s aggregate. NASDAQ runs ~10M trades/sec at peak
 *     across ~5k symbols; the math works.
 *
 * What about cross-symbol orders (FX cross, futures spreads)?
 *   - You'd need to coordinate two matcher threads. Same options as ATM
 *     transfer (project #6): lock both in canonical order, OR send a
 *     two-phase message via a dedicated cross-product matcher.
 */
public class MatchingEngine {

    private final ConcurrentHashMap<String, SymbolMatcher> matchers = new ConcurrentHashMap<>();

    public CompletableFuture<Order> submit(Order order) {
        SymbolMatcher matcher = matchers.computeIfAbsent(
                order.getSymbol(), SymbolMatcher::new);
        return matcher.submit(order);
    }

    public SymbolMatcher getMatcher(String symbol) {
        return matchers.get(symbol);
    }

    public void shutdown() {
        for (SymbolMatcher m : matchers.values()) {
            m.shutdown();
        }
    }
}
