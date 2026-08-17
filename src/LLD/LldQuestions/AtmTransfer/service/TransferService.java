package LLD.LldQuestions.AtmTransfer.service;

import LLD.LldQuestions.AtmTransfer.exceptions.AccountNotFoundException;
import LLD.LldQuestions.AtmTransfer.exceptions.InsufficientFundsException;
import LLD.LldQuestions.AtmTransfer.models.Account;
import LLD.LldQuestions.AtmTransfer.models.Transfer;
import LLD.LldQuestions.AtmTransfer.models.TransferStatus;
import LLD.LldQuestions.AtmTransfer.repository.AccountRepository;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                         TransferService                                ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * This project is the **deadlock-avoidance lesson** in the curriculum.
 * Everything else (per-key locks, idempotency, atomic compound action) you
 * have seen in earlier projects. The new thing is:
 *
 *   To safely transfer between TWO accounts you must acquire TWO locks.
 *   Two locks acquired in inconsistent order create a cycle (deadlock).
 *
 *   The rule: ALWAYS acquire locks in a globally-agreed canonical order.
 *   Here we use lexicographic order on accountId.
 *
 * The cycle scenario (BAD CODE)
 * ─────────────────────────────
 *
 *   Thread A: transfer($100 from acct=X to acct=Y)
 *     X.lock.lock()
 *     Y.lock.lock()   <- waits if Y is locked
 *
 *   Thread B: transfer($50 from acct=Y to acct=X)
 *     Y.lock.lock()
 *     X.lock.lock()   <- waits because A holds X
 *
 *   A waits on Y, B waits on X. Neither releases. Deadlock.
 *
 * The fix (THIS CODE)
 * ───────────────────
 *
 *   Sort the two accounts by ID. Always lock the smaller id first, the
 *   larger second. Independent of which is the source or destination.
 *
 *     X.id < Y.id  ->  lock X then Y
 *     X.id > Y.id  ->  lock Y then X
 *
 *   Both threads now lock X first (because X.id < Y.id from both sides),
 *   so one waits at the FIRST lock, never both threads holding partial sets.
 *
 * Lexicographic on the *id*, not on the Account reference's identityHashCode?
 *   - Yes, deliberately. Two account objects could differ by identity hash
 *     across JVM restarts -- the order would change. Using the stable id
 *     keeps the order **deterministic across all callers everywhere**,
 *     which is the property deadlock-avoidance requires.
 *
 * What about self-transfer (X -> X)?
 *   - Same account; sort puts the same lock on both sides. We acquire it
 *     once (ReentrantLock is reentrant), increment + decrement = no-op, no
 *     deadlock. We DO short-circuit at the top for clarity.
 *
 * Idempotency
 * ───────────
 * Same pattern as HotelBooking: a `clientRequestId` keyed CHM that maps to
 * the existing Transfer. Pre-check before lock (fast path); re-check inside
 * the lock (correctness).
 *
 * Why no two-phase commit?
 * ────────────────────────
 * Because both accounts live in the same JVM. In the multi-database case
 * you'd need a 2PC or saga -- out of scope here. The lesson here is the
 * single-process deadlock-avoidance rule.
 */
public class TransferService {

    private final AccountRepository accountRepository;
    private final Map<String, Transfer> transfersByRequest = new ConcurrentHashMap<>();
    private final Map<String, Transfer> transfersById = new ConcurrentHashMap<>();

    public TransferService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /*
     * Transfer `amountCents` from `fromId` to `toId`. Idempotent on
     * `clientRequestId`: a retry returns the original Transfer.
     */
    public Transfer transfer(String clientRequestId,
                             String fromId,
                             String toId,
                             long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        // Idempotency fast path (no lock).
        if (clientRequestId != null) {
            Transfer existing = transfersByRequest.get(clientRequestId);
            if (existing != null) {
                return existing;
            }
        }

        if (fromId.equals(toId)) {
            // Self-transfer: pure no-op semantically but we still record it,
            // because callers expect a Transfer object back regardless.
            Account a = requireAccount(fromId);
            a.getLock().lock();
            try {
                return recordTransfer(clientRequestId, fromId, toId, amountCents,
                        TransferStatus.SUCCESS);
            } finally {
                a.getLock().unlock();
            }
        }

        Account from = requireAccount(fromId);
        Account to = requireAccount(toId);

        // ── DEADLOCK-AVOIDANCE: lock in canonical order by id ─────────────
        // Whichever account has the smaller id is locked first by EVERY
        // caller, no matter whether it's their `from` or `to`. So the
        // wait-graph is a DAG and can never have a cycle.
        Account first  = from.getId().compareTo(to.getId()) < 0 ? from : to;
        Account second = (first == from) ? to : from;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                // Idempotency double-check inside the lock. Another thread
                // with the same clientRequestId might have finished the
                // transfer while we were waiting.
                if (clientRequestId != null) {
                    Transfer existing = transfersByRequest.get(clientRequestId);
                    if (existing != null) {
                        return existing;
                    }
                }

                if (from.getBalance() < amountCents) {
                    Transfer rec = recordTransfer(clientRequestId, fromId, toId, amountCents,
                            TransferStatus.INSUFFICIENT_FUNDS);
                    throw new InsufficientFundsException(
                            "Account " + fromId + " has $" + from.getBalance() / 100.0
                                    + ", requested $" + amountCents / 100.0
                                    + " (transferId=" + rec.getId() + ")");
                }

                from.debit(amountCents);
                to.credit(amountCents);
                return recordTransfer(clientRequestId, fromId, toId, amountCents,
                        TransferStatus.SUCCESS);
            } finally {
                second.getLock().unlock();
            }
        } finally {
            first.getLock().unlock();
        }
    }

    public Transfer getTransfer(String id) {
        return transfersById.get(id);
    }

    public long getBalance(String accountId) {
        Account a = requireAccount(accountId);
        a.getLock().lock();
        try {
            return a.getBalance();
        } finally {
            a.getLock().unlock();
        }
    }

    private Transfer recordTransfer(String requestId,
                                    String fromId,
                                    String toId,
                                    long amountCents,
                                    TransferStatus status) {
        Transfer t = new Transfer(UUID.randomUUID().toString(),
                fromId, toId, amountCents, status);
        transfersById.put(t.getId(), t);
        if (requestId != null) {
            transfersByRequest.put(requestId, t);
        }
        return t;
    }

    private Account requireAccount(String id) {
        Account a = accountRepository.get(id);
        if (a == null) {
            throw new AccountNotFoundException("Unknown account: " + id);
        }
        return a;
    }
}
