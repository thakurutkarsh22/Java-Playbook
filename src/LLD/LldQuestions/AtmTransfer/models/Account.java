package LLD.LldQuestions.AtmTransfer.models;

import java.util.concurrent.locks.ReentrantLock;

/*
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║                              Account                                   ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Each account carries its OWN ReentrantLock. This is the per-key locking
 * pattern, but exposed differently than in TicketBooking/HotelBooking --
 * here the lock travels WITH the data, because:
 *
 *   - We never need a "find the lock by id" lookup (unlike TicketBooking,
 *     where the lock provider is a Map<showId, Lock> because shows are
 *     looked up by id at the gate). Here the caller already has the
 *     Account references.
 *
 *   - It makes the deadlock-avoidance rule textually obvious: to transfer
 *     A -> B you must `lock(A); lock(B);` in some order. Co-locating the
 *     lock with the account makes the "two locks" reality visible at the
 *     call site, which is the whole point of this exercise.
 *
 * The balance is `long cents` (money in pennies). We never use double for
 * money -- IEEE 754 floats lose pennies under addition (0.1 + 0.2 != 0.3
 * in IEEE).
 *
 * Reads of `balance` outside the lock are fine for diagnostics but MUST
 * not be used to make a transfer decision. The transfer service always
 * re-reads inside the lock.
 *
 * Why no `volatile` on balance?
 *   - Every read/write that matters for correctness is INSIDE the lock,
 *     so happens-before is already guaranteed by lock release/acquire.
 *     Diagnostic reads (`getBalanceUnsafe`) accept staleness; if we ever
 *     wanted them to be consistent we'd add volatile, but the docs make
 *     it clear they aren't.
 */
public class Account {

    private final String id;
    private long balanceCents;
    private final ReentrantLock lock = new ReentrantLock();

    public Account(String id, long initialCents) {
        if (initialCents < 0) {
            throw new IllegalArgumentException("initial balance must be >= 0");
        }
        this.id = id;
        this.balanceCents = initialCents;
    }

    public String getId() {
        return id;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    /*
     * Mutate balance. Caller MUST hold this account's lock.
     *
     * We don't assert `lock.isHeldByCurrentThread()` to keep the model
     * simple, but in a real codebase this is a great place to add it
     * (`Preconditions.checkState(lock.isHeldByCurrentThread())`) -- it
     * catches "forgot to lock" bugs at the earliest possible moment.
     */
    public void debit(long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("debit must be >= 0");
        }
        if (balanceCents < cents) {
            throw new IllegalStateException(
                    "Insufficient funds in " + id + ": have " + balanceCents
                            + " cents, need " + cents);
        }
        balanceCents -= cents;
    }

    public void credit(long cents) {
        if (cents < 0) {
            throw new IllegalArgumentException("credit must be >= 0");
        }
        balanceCents += cents;
    }

    /*
     * Diagnostic read; may return a stale value. Do NOT use for transfer
     * preconditions -- always re-read inside the lock.
     */
    public long getBalanceUnsafe() {
        return balanceCents;
    }

    /*
     * Consistent read; caller must hold the lock.
     */
    public long getBalance() {
        return balanceCents;
    }

    @Override
    public String toString() {
        return "Account{" + id + " $" + balanceCents / 100.0 + "}";
    }
}
