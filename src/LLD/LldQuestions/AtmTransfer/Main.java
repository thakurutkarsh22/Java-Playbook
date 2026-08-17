package LLD.LldQuestions.AtmTransfer;

import LLD.LldQuestions.AtmTransfer.exceptions.InsufficientFundsException;
import LLD.LldQuestions.AtmTransfer.models.Account;
import LLD.LldQuestions.AtmTransfer.models.Transfer;
import LLD.LldQuestions.AtmTransfer.repository.AccountRepository;
import LLD.LldQuestions.AtmTransfer.service.TransferService;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * Demo entry point.
 *
 *   Scenario 1: Cross-transfer storm. Threads pair up to transfer
 *               A->B AND B->A simultaneously, 1000 times. Without ordered
 *               locking this would deadlock. With it, ALL must complete
 *               and the conservation-of-money invariant must hold.
 *
 *   Scenario 2: Insufficient funds. Account has $10; we attempt $15. Must
 *               throw InsufficientFundsException; balance unchanged.
 *
 *   Scenario 3: Idempotency. Same clientRequestId twice. One debit, one
 *               credit. Both retries return the same Transfer object.
 *
 *   Scenario 4: Conservation of money. Random transfers between 5 accounts,
 *               100 threads × 50 ops each = 5000 transfers. Sum of balances
 *               must equal initial total.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        runDeadlockStress();
        runInsufficientFunds();
        runIdempotency();
        runConservationOfMoney();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scenarios
    // ────────────────────────────────────────────────────────────────────────

    private static void runDeadlockStress() throws Exception {
        System.out.println("\n=== Scenario 1: cross-transfer storm (would deadlock without ordered locking) ===");

        AccountRepository repo = new AccountRepository();
        repo.save(new Account("A", 1_000_000_00L));
        repo.save(new Account("B", 1_000_000_00L));
        TransferService service = new TransferService(repo);

        int rounds = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    service.transfer(UUID.randomUUID().toString(), "A", "B", 100);
                }
            } catch (Exception e) {
                System.err.println("  A->B failed: " + e);
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds; i++) {
                    service.transfer(UUID.randomUUID().toString(), "B", "A", 100);
                }
            } catch (Exception e) {
                System.err.println("  B->A failed: " + e);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        // If we deadlocked, this await would never return. We use a tight
        // timeout so a real failure is loud, not a hang.
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: deadlock suspected (transfers did not complete in 20s)");
        }
        pool.shutdown();

        long total = service.getBalance("A") + service.getBalance("B");
        System.out.println("  done. A=$" + service.getBalance("A") / 100.0
                + " B=$" + service.getBalance("B") / 100.0
                + "  total=$" + total / 100.0
                + " (expected $" + 2_000_000.0 + ")");
        if (total != 2_000_000_00L) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: money was lost or created. Final total = " + total);
        }
    }

    private static void runInsufficientFunds() {
        System.out.println("\n=== Scenario 2: insufficient funds ===");

        AccountRepository repo = new AccountRepository();
        repo.save(new Account("X", 10_00L));
        repo.save(new Account("Y", 0L));
        TransferService service = new TransferService(repo);

        boolean thrown = false;
        try {
            service.transfer(UUID.randomUUID().toString(), "X", "Y", 15_00L);
        } catch (InsufficientFundsException e) {
            thrown = true;
            System.out.println("  caught (as expected): " + e.getMessage());
        }
        if (!thrown) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: insufficient transfer should have thrown");
        }
        long total = service.getBalance("X") + service.getBalance("Y");
        if (total != 10_00L) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: failed transfer affected balances; total=" + total);
        }
        System.out.println("  balances unchanged: X=$" + service.getBalance("X") / 100.0
                + " Y=$" + service.getBalance("Y") / 100.0);
    }

    private static void runIdempotency() {
        System.out.println("\n=== Scenario 3: idempotent retry ===");

        AccountRepository repo = new AccountRepository();
        repo.save(new Account("P", 1000_00L));
        repo.save(new Account("Q", 0L));
        TransferService service = new TransferService(repo);

        String requestId = "client-tx-" + UUID.randomUUID();
        Transfer first = service.transfer(requestId, "P", "Q", 200_00L);
        Transfer again = service.transfer(requestId, "P", "Q", 200_00L);

        System.out.println("  first transferId=" + first.getId());
        System.out.println("  retry transferId=" + again.getId() + "  (must equal first)");
        System.out.println("  P=$" + service.getBalance("P") / 100.0
                + "  Q=$" + service.getBalance("Q") / 100.0
                + "  (Q must be $200, NOT $400)");

        if (!first.getId().equals(again.getId())) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: idempotency didn't dedup");
        }
        if (service.getBalance("Q") != 200_00L) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: retry double-credited Q to $"
                            + service.getBalance("Q") / 100.0);
        }
    }

    private static void runConservationOfMoney() throws Exception {
        System.out.println("\n=== Scenario 4: random transfers between 5 accounts; total invariant ===");

        AccountRepository repo = new AccountRepository();
        String[] ids = {"A1", "B2", "C3", "D4", "E5"};
        long perAccount = 1_000_00L;
        long initialTotal = 0;
        for (String id : ids) {
            repo.save(new Account(id, perAccount));
            initialTotal += perAccount;
        }
        TransferService service = new TransferService(repo);

        int threads = 100;
        int opsPerThread = 50;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insf = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.submit(() -> {
                java.util.Random rng = new java.util.Random(seed);
                try {
                    start.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        String fromId = ids[rng.nextInt(ids.length)];
                        String toId = ids[rng.nextInt(ids.length)];
                        long amount = rng.nextInt(500) + 1;
                        try {
                            service.transfer(UUID.randomUUID().toString(),
                                    fromId, toId, amount);
                            ok.incrementAndGet();
                        } catch (InsufficientFundsException e) {
                            insf.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: deadlock suspected in random storm");
        }
        pool.shutdown();

        long finalTotal = 0;
        for (String id : ids) {
            finalTotal += service.getBalance(id);
        }
        System.out.println("  ok=" + ok.get() + " insufficient=" + insf.get()
                + " finalTotal=" + finalTotal + " initialTotal=" + initialTotal);
        if (finalTotal != initialTotal) {
            throw new IllegalStateException(
                    "INVARIANT VIOLATED: money created or destroyed. delta="
                            + (finalTotal - initialTotal));
        }
    }
}
