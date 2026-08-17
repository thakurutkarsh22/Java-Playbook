package LLD.LldQuestions.OrderMatchingEngine;

import LLD.LldQuestions.OrderMatchingEngine.models.Order;
import LLD.LldQuestions.OrderMatchingEngine.models.Side;
import LLD.LldQuestions.OrderMatchingEngine.models.Trade;
import LLD.LldQuestions.OrderMatchingEngine.service.MatchingEngine;
import LLD.LldQuestions.OrderMatchingEngine.service.SymbolMatcher;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Demo entry point.
 *
 *   Scenario 1: Simple crossing trade. SELL @ $100 rests; BUY @ $102
 *               crosses; trade at $100 (the maker's price), quantity =
 *               min of the two sides.
 *
 *   Scenario 2: Price-time priority. Two SELLs at the same price; the
 *               older one (lower id) matches FIRST.
 *
 *   Scenario 3: Partial fills. BUY 100 shares against [SELL 30 @ $99,
 *               SELL 50 @ $100, SELL 40 @ $101]. We expect three trades
 *               (30, 50, 20) and the remaining 0 -- the order is fully
 *               filled at three different prices.
 *
 *   Scenario 4: Cross-symbol parallelism. 4 threads submit orders for 4
 *               different symbols simultaneously. Each runs on its own
 *               matcher thread; aggregate throughput >= sum of
 *               per-symbol throughputs.
 *
 *   Scenario 5: Conservation of shares. 1000 random orders on one
 *               symbol; aggregate volume(BUYs traded) = volume(SELLs
 *               traded). No shares created or lost.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        runSimpleCross();
        runPriceTimePriority();
        runPartialFills();
        runCrossSymbolParallelism();
        runConservationOfShares();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runSimpleCross() throws Exception {
        System.out.println("\n=== Scenario 1: SELL @ $100 rests, BUY @ $102 crosses -> trade @ $100 ===");

        MatchingEngine engine = new MatchingEngine();
        engine.submit(new Order("AAPL", Side.SELL, 10000, 50)).get();   // $100 × 50
        engine.submit(new Order("AAPL", Side.BUY,  10200, 50)).get();   // $102 × 50

        SymbolMatcher m = engine.getMatcher("AAPL");
        List<Trade> trades = m.drainTape();
        for (Trade t : trades) {
            System.out.println("  " + t);
        }
        if (trades.size() != 1 || trades.get(0).getPriceCents() != 10000
                || trades.get(0).getQuantity() != 50) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected ONE trade @ $100 × 50; got " + trades);
        }
        engine.shutdown();
    }

    private static void runPriceTimePriority() throws Exception {
        System.out.println("\n=== Scenario 2: same price -> oldest sell fills first ===");

        MatchingEngine engine = new MatchingEngine();
        Order oldSell = new Order("MSFT", Side.SELL, 30000, 10);
        engine.submit(oldSell).get();
        Order newSell = new Order("MSFT", Side.SELL, 30000, 10);
        engine.submit(newSell).get();

        engine.submit(new Order("MSFT", Side.BUY, 30000, 10)).get();

        List<Trade> trades = engine.getMatcher("MSFT").drainTape();
        Trade t = trades.get(0);
        System.out.println("  " + t);
        if (t.getSellOrderId() != oldSell.getId()) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: older sell (id=" + oldSell.getId()
                            + ") should have matched first; got " + t.getSellOrderId());
        }
        engine.shutdown();
    }

    private static void runPartialFills() throws Exception {
        System.out.println("\n=== Scenario 3: partial fills walk the book up the ladder ===");

        MatchingEngine engine = new MatchingEngine();
        engine.submit(new Order("TSLA", Side.SELL, 9900,  30)).get();
        engine.submit(new Order("TSLA", Side.SELL, 10000, 50)).get();
        engine.submit(new Order("TSLA", Side.SELL, 10100, 40)).get();

        // Big BUY at $102: takes ladder up to $101.
        Order buy = new Order("TSLA", Side.BUY, 10200, 100);
        engine.submit(buy).get();

        List<Trade> trades = engine.getMatcher("TSLA").drainTape();
        for (Trade t : trades) {
            System.out.println("  " + t);
        }
        // 30 + 50 + 20 = 100; three trades at three prices.
        if (trades.size() != 3) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: expected 3 partial trades, got " + trades.size());
        }
        long total = 0;
        for (Trade t : trades) {
            total += t.getQuantity();
        }
        if (total != 100) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: total filled = " + total + ", expected 100");
        }
        engine.shutdown();
    }

    private static void runCrossSymbolParallelism() throws Exception {
        System.out.println("\n=== Scenario 4: 4 symbols match in PARALLEL on their own threads ===");

        MatchingEngine engine = new MatchingEngine();
        String[] symbols = {"A", "B", "C", "D"};
        int ordersPerSymbol = 2000;

        long t0 = System.nanoTime();
        CountDownLatch done = new CountDownLatch(symbols.length);
        ExecutorService submitters = Executors.newFixedThreadPool(symbols.length);
        for (String sym : symbols) {
            submitters.submit(() -> {
                try {
                    for (int i = 0; i < ordersPerSymbol; i++) {
                        Side side = (i % 2 == 0) ? Side.BUY : Side.SELL;
                        // Submit interleaved BUY/SELL that mostly cross.
                        long price = 10000 + (i % 5);
                        engine.submit(new Order(sym, side, price, 10));
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        submitters.shutdown();
        // Allow matcher threads to drain.
        Thread.sleep(200);
        long elapsed = System.nanoTime() - t0;
        long totalSubmitted = symbols.length * (long) ordersPerSymbol;
        System.out.println("  submitted " + totalSubmitted
                + " orders across " + symbols.length + " symbols in "
                + elapsed / 1_000_000 + " ms");
        long totalTrades = 0;
        for (String sym : symbols) {
            totalTrades += engine.getMatcher(sym).drainTape().size();
        }
        System.out.println("  total trades = " + totalTrades);
        engine.shutdown();
    }

    private static void runConservationOfShares() throws Exception {
        System.out.println("\n=== Scenario 5: conservation of shares on random workload ===");

        MatchingEngine engine = new MatchingEngine();
        int orders = 1000;
        AtomicLong submittedBuyShares = new AtomicLong();
        AtomicLong submittedSellShares = new AtomicLong();
        java.util.Random rng = new java.util.Random(42);

        java.util.List<java.util.concurrent.Future<Order>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < orders; i++) {
            Side s = rng.nextBoolean() ? Side.BUY : Side.SELL;
            long price = 9800 + rng.nextInt(401);
            long qty = 1 + rng.nextInt(100);
            if (s == Side.BUY) submittedBuyShares.addAndGet(qty);
            else submittedSellShares.addAndGet(qty);
            futures.add(engine.submit(new Order("RND", s, price, qty)));
        }
        for (java.util.concurrent.Future<Order> f : futures) {
            f.get();
        }
        List<Trade> trades = engine.getMatcher("RND").drainTape();
        long tradedShares = 0;
        for (Trade t : trades) {
            tradedShares += t.getQuantity();
        }
        // Every trade by definition has equal buy-side and sell-side qty (one
        // counts as BOTH a buy of `qty` and a sell of `qty`). Conservation:
        // tradedShares is consumed from each side. So:
        //   remainingBuy  = submittedBuy  - tradedShares
        //   remainingSell = submittedSell - tradedShares
        // The matched volume must be <= min(submittedBuy, submittedSell).
        long maxPossible = Math.min(submittedBuyShares.get(), submittedSellShares.get());
        System.out.println("  buy submitted="  + submittedBuyShares
                + "  sell submitted=" + submittedSellShares
                + "  traded volume=" + tradedShares
                + "  (must be <= min=" + maxPossible + ")");
        if (tradedShares > maxPossible) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: traded volume " + tradedShares
                            + " > min(buy,sell) " + maxPossible);
        }

        // No order can be "double-spent": sum of remainingQuantities of resting
        // orders equals submitted - traded for each side.
        SymbolMatcher m = engine.getMatcher("RND");
        long restingBuy = 0;
        for (Order o : m.getBook().snapshot(Side.BUY)) {
            restingBuy += o.getRemainingQuantity();
        }
        long restingSell = 0;
        for (Order o : m.getBook().snapshot(Side.SELL)) {
            restingSell += o.getRemainingQuantity();
        }
        long expectedBuy  = submittedBuyShares.get()  - tradedShares;
        long expectedSell = submittedSellShares.get() - tradedShares;
        System.out.println("  resting buys=" + restingBuy + " (expected " + expectedBuy + ")"
                + "  resting sells=" + restingSell + " (expected " + expectedSell + ")");
        if (restingBuy != expectedBuy || restingSell != expectedSell) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: book accounting mismatch -- shares were created or lost");
        }
        engine.shutdown();
    }

    @SuppressWarnings("unused")
    private static void awaitTermination(ExecutorService p) throws InterruptedException {
        p.awaitTermination(2, TimeUnit.SECONDS);
    }
}
