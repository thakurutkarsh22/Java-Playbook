# ATM / Bank Transfer — Interview Q&A Cheat Sheet

Companion to `Readme.md`. The Readme explains the *design*; this file
captures the SDE-3-level questions an interviewer will actually ask. This
project's headline lesson is **deadlock avoidance**, so expect at least
one variant of "show me how you'd deadlock this and then how you'd fix it."

---

## Q1. Show me on a whiteboard exactly how this deadlocks without ordered locking.

**TL;DR** — Two threads, two locks, each holds one and waits on the other.
Classic wait-cycle in the resource-allocation graph.

### The bug, line by line

```java
// BAD: lock from-then-to, regardless of id order
transfer(String from, String to, long amount) {
    fromAcct.lock.lock();
    toAcct.lock.lock();
    // ...
}
```

```text
t=0   Thread A starts transfer(A, B):
        fromAcct = A
        toAcct   = B
        A.lock.lock()   ✓
        ← preempted by OS scheduler →

t=0.01 Thread B starts transfer(B, A):
        fromAcct = B
        toAcct   = A
        B.lock.lock()   ✓
        A.lock.lock()   ← waits, A still held by Thread A
        ← preempted →

t=0.02 Thread A resumes:
        B.lock.lock()   ← waits, B still held by Thread B
```

Wait graph:

```
   Thread A  ──waits──>  B.lock  ──held by──>  Thread B
       ↑                                            │
       └───────────────waits  A.lock <──held by─────┘
```

The cycle means neither thread ever proceeds. JVM thread dump shows:

```
"Thread-A" waiting to lock <0xBBB>  held by "Thread-B"
"Thread-B" waiting to lock <0xAAA>  held by "Thread-A"
```

### The fix

Acquire by sorted id:

```java
Account first  = compareTo < 0 ? from : to;
Account second = (first == from) ? to : from;
first.lock.lock();
second.lock.lock();
```

Now both threads acquire **the same lock first**, so the second always
waits at the *first* lock — never holding partial state. No cycle, no
deadlock.

---

## Q2. Why lexicographic on `id`? Why not `System.identityHashCode`?

**TL;DR** — Because `identityHashCode` is JVM-instance-specific and can
collide. A stable, deterministic, *globally agreed* order is what
deadlock-avoidance requires.

### identityHashCode pitfalls

```java
Account a1 = new Account("A", ...);
Account a2 = new Account("A", ...);  // same id, different JVM run
// identityHashCode(a1) != identityHashCode(a2) in general
```

Two issues:

1. **Per-JVM**: cluster nodes pick different orders. In a distributed
   lock scheme, this matters.
2. **Collisions**: identityHashCode CAN collide (it's a 32-bit hash, not
   a unique id). Two accounts with the same hash → "equal", so the
   ordering is ambiguous, so cycle possible.

### The id is stable forever

The `id` is a logical primary key. Same across JVMs, across DB shards,
across time. `compareTo` on the string is a total order. Done.

### What about `hashCode()` of the id (instead of the id itself)?

Same hash-collision problem. Use the id string directly. Strings are
cheap to compare and produce a total order.

---

## Q3. How does the ordering generalize to N locks?

**TL;DR** — Sort the N locks by a stable key, acquire in order. The
wait-graph is still a DAG.

### Sketch

```java
List<Account> sorted = new ArrayList<>(accounts);
sorted.sort(Comparator.comparing(Account::getId));

List<Lock> heldLocks = new ArrayList<>();
try {
    for (Account a : sorted) {
        a.getLock().lock();
        heldLocks.add(a.getLock());
    }
    // ... compound action across all N ...
} finally {
    // Release in REVERSE order. Not strictly necessary for correctness
    // (ReentrantLock release is order-independent for non-nested holds),
    // but matches the conventional "stack discipline" and helps the JIT.
    for (int i = heldLocks.size() - 1; i >= 0; i--) {
        heldLocks.get(i).unlock();
    }
}
```

### When does this come up?

- **Multi-account payouts**: payroll splits one transfer into 100
  destinations. Lock source + all 100 destinations in id order.
- **Inventory transfers**: move SKU x from warehouse A to warehouse B,
  while simultaneously decrementing the "in-transit" counter. Three locks.
- **Hotel multi-room reservation** (extension of project #5): one
  reservation covering 5 adjacent rooms.

### What you must avoid

`tryLock` cycles. Some people try to "be smart" with `tryLock` +
back-off-and-retry. It works, but it's *livelock*-prone (both threads
keep getting half the locks and retrying). Ordered locking is a strictly
simpler and equally correct fix.

---

## Q4. What if you don't know the ids in advance — e.g. the API gives you `Account` references?

**TL;DR** — Sort by *id derived from the reference*. As long as the
derivation is deterministic, you're fine.

### Code

```java
List<Account> sorted = new ArrayList<>(refs);
sorted.sort(Comparator.comparing(Account::getId));
// ... acquire in order ...
```

### When this is hard

When two `Account` references both have null/empty ids. Then your sort is
unstable and the ordering depends on insertion order — different threads
may sort the same set into different orders. **Never have an account
without a stable id.** It's a design smell.

---

## Q5. What about `tryLock` instead of `lock` — wouldn't that avoid deadlock differently?

**TL;DR** — Yes, but it can livelock and it's strictly more complex.
Ordered locking is the better answer 95% of the time.

### tryLock pattern

```java
while (true) {
    if (!from.tryLock(100, MILLISECONDS)) continue;
    try {
        if (!to.tryLock(100, MILLISECONDS)) { from.unlock(); continue; }
        try { /* transfer */ return; } finally { to.unlock(); }
    } finally { from.unlock(); }
}
```

### What can go wrong

**Livelock.** Two threads each grab their first lock, fail their second,
release, retry — in lockstep. Neither makes progress.

In practice, JVM scheduling randomness usually breaks the symmetry within
a few iterations. But "usually" is not a correctness guarantee.

### When tryLock IS right

- When the locks are owned by external systems (Redis distributed locks)
  and you genuinely don't know the global ordering.
- When you need a hard latency budget (e.g. "give up after 50ms"). Then
  `tryLock(timeout)` plus an SLO-aware caller is appropriate.

### What you should say in an interview

> "Ordered locking by id is the default. tryLock is a fallback when you
> can't establish a global order, but it requires careful back-off to
> avoid livelock — exponential jitter, capped retries, and an
> upper-bound budget. Most production code uses ordered locking."

---

## Q6. Walk me through your idempotency. What if the retry comes BEFORE the first request finishes?

**TL;DR** — That's exactly the race the double-check closes. Pre-check
sees null, lock blocks the retry behind the first request, retry's inside
check sees the populated map.

### The race, step by step

```text
Thread X (first)                            Thread Y (retry, before X finishes)
  pre-check k1 -> null                        pre-check k1 -> null
  acquire lock(A)
  acquire lock(B)                              acquire lock(A)  ← blocks behind X
  re-check k1 -> null
  debit/credit
  record transfer T
  set k1 -> T
  release lock(B)
  release lock(A)
                                              acquire lock(A)  ← unblocks
                                              acquire lock(B)
                                              re-check k1 -> T   ← short-circuit
                                              release locks
                                              return T
```

The retry **never debits A twice**. The inside-lock re-check is what
catches the case where X completed during Y's wait.

### What if both retries see null inside the lock?

That can't happen because the lock is held across the `recordTransfer` +
`put(k1, T)` step. Whichever thread acquires the lock first does the work
and sets the map; the next one sees the map populated.

### What if `clientRequestId` is null?

Then we *don't* dedupe. The caller has explicitly said "I don't need
idempotency on this one" (e.g. an internal admin op). For customer-facing
APIs, **always require** a client request id.

---

## Q7. What's the trade-off between locks-on-the-account vs locks-in-a-map (like TicketBooking)?

**TL;DR** — Locks-on-the-model are simpler when callers already hold
references; locks-in-a-map are required when callers find resources by id.

### Locks on the model (this project)

```java
class Account { ReentrantLock lock; ... }
account.getLock().lock();
```

- ✓ No lookup needed; lock travels with the object
- ✓ "I need two locks" is visible at the call site (great for teaching)
- ✗ Couples model to concurrency concerns
- ✗ Harder to swap for a distributed lock (you'd need to add a `LockId`
  method or similar)

### Locks in a service map (`TicketBooking`)

```java
Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
locks.computeIfAbsent(showId, k -> new ReentrantLock(true)).lock();
```

- ✓ Model stays pure
- ✓ Easy to swap for a distributed lock (`Map<showId, DistributedLock>`)
- ✗ Extra lookup per acquire (cheap, but non-zero)
- ✗ "I need two locks" is less visible at the call site

### Which to pick

Use locks-in-a-map when the lock is conceptually *part of the operation*
(reserve a seat → service's job). Use locks-on-the-model when the lock is
conceptually *part of the resource* (modify an account → account's job).
There's no wrong answer; pick by what the consumer code is doing.

---

## Q8. What does `BigDecimal` buy you over `long cents`?

**TL;DR** — Sub-cent precision. Use `BigDecimal` when the spec requires
it (Forex, crypto). Use `long` otherwise — it's smaller, faster, and
exact within its range.

### `long cents` limits

- Range: ±9.2 × 10^18 cents = ±$92 quadrillion. Plenty for retail.
- Precision: exact to the cent.
- Speed: 1-cycle add/subtract.

### When `BigDecimal` is required

- **Forex**: rates carry 4-6 decimal places (`1 USD = 0.92345 EUR`).
  Multiplying balance × rate needs sub-cent precision then rounding.
- **Crypto**: 8 decimal places (satoshi) or 18 (wei). `long` overflows.
- **Compound interest**: many small multiplications accumulate rounding
  errors with floats; `BigDecimal` controls it explicitly.

### Never `double` for money

```java
double d = 0.1 + 0.2;        // 0.30000000000000004
```

After 1000 cent-level operations this drifts by ~1 cent. After 1 million,
~$10. That's an audit finding waiting to happen.

---

## Q9. How do you scale this to two banks (cross-bank transfer)?

**TL;DR** — Saga pattern. Tentative debit on bank A, conditional commit
on bank B success. Idempotency keys at every step.

### The saga

```
1. authorizeDebit(A, $100, idempotencyKey=K1)   -- soft hold on A
2. credit(B, $100, idempotencyKey=K2)
   - if success:
       commitDebit(A, K1)                       -- finalize debit
   - if failure:
       cancelDebit(A, K1)                       -- release hold
```

Why this works:

- Each step is **idempotent**: the saga runner can retry any step.
- Each step is **reversible** (either commit or cancel the prior).
- We never have to lock across the network.

### What you give up

- **Strong consistency**: between step 1 and step 4 the money is "in
  flight" — not in A, not in B. Statements show this as "pending".
- **Latency**: saga is sequential RPCs.

### What you gain

- **Availability**: a B-side outage stops new transfers but doesn't
  freeze ongoing A operations.
- **Audit trail**: each step is a durable event.

### Real-world equivalents

- SWIFT messages: bank-to-bank settlement, ~T+1 (one business day).
- Payment networks (Visa/MC): authorization (hold) + capture (commit).
- Crypto (Bitcoin): tx + N-confirmations.

All are sagas with different names.

---

## Q10. How do you test this for correctness?

**TL;DR** — Property-based tests: random workload + conservation
invariant. JMH benchmarks for performance. JCStress for memory-model
verification.

### Property tests

```text
Forall:
  - N accounts with initial balances summing to T
  - M random transfers (random source, dest, amount)

Assert:
  - sum of final balances == T          ← conservation
  - no negative balance                   ← invariant
  - all `INSUFFICIENT_FUNDS` exceptions
    correspond to transfers where
    `from.balance < amount` at that
    point in time                         ← consistency
```

`jqwik` or `Hypothesis` (Python) for property tests. Pair with random
seeds you can replay.

### JCStress

[OpenJDK JCStress](https://github.com/openjdk/jcstress) is the standard
tool for verifying JMM-level correctness. It runs your code under all
allowed reorderings and reports unexpected outcomes.

For transfer code, you'd write a `@JCStressTest` that has two
`@Actor`-annotated methods doing `transfer(A,B)` and `transfer(B,A)`,
and a `@Result` that asserts conservation. JCStress would run it
millions of times under stress.

### Mock the clock for the conservation test

Use `Instant.now(Clock)` everywhere; in tests inject a `Clock.fixed`.
Makes the property tests deterministic.

---

## Bonus — quickfire round

| Question | Answer (≤2 lines) |
|---|---|
| Why isn't `synchronized` enough here? | It is, *for correctness*. But `ReentrantLock` gives `tryLock(timeout)`, fairness, and `Condition` — capabilities you'll need for the next iteration. |
| Why does the lock travel with the Account, not the AccountRepository? | Because callers already hold the reference, and the "two locks" reality is more visible at the call site. Map-based locking is fine; this is a teaching choice. |
| What if account ids are integers? | Same idea — sort numerically. The key requirement is a total order across all callers. |
| What's the worst case latency? | 2 lock acquisitions (each ~30 ns uncontended; up to µs contended) + 2 memory mutations. ~100 ns to ~10 µs. |
| How would you implement reversal/refund? | Same code, swapping (from, to). Distinct `clientRequestId` so it's idempotent independently. The Transfer schema gains a `reverseOf` field for audit. |
| Could you make this lock-free with `compareAndSet`? | For single-account ops yes (compare-and-set the balance). For TWO accounts simultaneously, no — there's no native CAS-2; you'd have to use STM (`AtomicReference` + careful retry), which is more complex than the ordered-lock approach. |
| When are 2 locks NOT enough? | When you also need to update a third object (e.g. a fraud-score table). Same rule generalizes: sort all N locks by id, acquire in order. |
| What's the failure mode if `to` doesn't exist? | We throw `AccountNotFoundException` *before* taking any locks (validation up front). Defensive: no partial-state corruption possible. |
