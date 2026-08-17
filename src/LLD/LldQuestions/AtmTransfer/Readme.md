# ATM / Bank Transfer — Basic LLD

A minimal, production-shaped Low-Level Design for transferring money between
two bank accounts, focused on the **hardest** part of the problem:
**avoiding deadlock when two accounts must be locked simultaneously**, plus
**idempotency** so retries don't double-debit.

This is **project #6** in the [LLD concurrency curriculum](../README.md). It
is the canonical worked example for the pattern:

> *"When a single operation needs two (or N) locks, acquire them in a
> globally canonical order. Any consistent ordering — id, hashCode, name —
> works, as long as ALL callers follow it."*

Read `TicketBooking` and `HotelBooking` first.

---

## 1. Problem statement

Many ATMs / web requests run transfers concurrently. The system must
guarantee:

| Invariant | Plain English |
|---|---|
| **Conservation of money** | Total balance across all accounts after N transfers = total before. No creation, no loss. |
| **No double-debit on retry** | A retried request returns the original result; no extra debit. |
| **No deadlock** | Even pathological "transfer A→B and B→A simultaneously" workloads complete. |
| **Atomic transfer** | A transfer is all-or-nothing: either both `debit(from)` and `credit(to)` succeed, or neither. |
| **No phantom balance** | `getBalance()` always shows a consistent value, not a partially-applied transfer. |

---

## 2. The deadlock you must avoid

The textbook bug, in 6 lines:

```text
Thread A: transfer($100 A → B)              Thread B: transfer($50 B → A)
  lock(A) ✓                                   lock(B) ✓
  lock(B)  ← waits for B                     lock(A)  ← waits for A
                       <DEADLOCK>
```

Two threads each hold one lock and want the other's. Neither will release.
The JVM happily blocks both forever.

### The fix: canonical ordering

Sort the two locks by the account id. **Always** lock the smaller id first.

```text
Thread A: transfer($100 A → B)              Thread B: transfer($50 B → A)
  lock(min(A.id, B.id)) = lock(A) ✓           lock(min(A.id, B.id)) = lock(A) ← waits

                                              (no cycle: B waits for A; A finishes; B proceeds)
```

The wait-graph becomes a DAG. No cycle ⇒ no deadlock. **This is the lesson
of this project.**

---

## 3. Architecture

```
                        ┌──────────────────────────────┐
                        │            Main              │
                        │  (4 race-condition demos)    │
                        └───────────────┬──────────────┘
                                        │
                                        ▼
                        ┌──────────────────────────────┐
                        │       TransferService        │
                        │ - canonical lock ordering    │
                        │ - idempotency: requestId map │
                        └──────────────┬───────────────┘
                                       │
                                       ▼
                        ┌──────────────────────────────┐
                        │      AccountRepository       │
                        │ ConcurrentHashMap of Account │
                        │                              │
                        │ Account carries its OWN lock │
                        └──────────────────────────────┘
```

Compare to `TicketBooking`: there, the locks live in a separate
`Map<showId, Lock>` because callers find shows by id. Here, the caller
already has the `Account` reference, so the lock travels with the data.
Both designs are correct; the choice depends on your access pattern.

---

## 4. Directory layout

```
AtmTransfer/
├── Main.java                          # 4 concurrent demo scenarios
├── Readme.md                          # this file
├── Interview-QA.md                    # SDE-3 Q&A
├── models/
│   ├── Account.java                   # ★ id + balance (long cents) + lock
│   ├── Transfer.java                  # immutable receipt
│   └── TransferStatus.java
├── service/
│   └── TransferService.java           # ★ ordered locking + idempotency
├── repository/
│   └── AccountRepository.java
└── exceptions/
    ├── InsufficientFundsException.java
    └── AccountNotFoundException.java
```

---

## 5. Concurrency primitives, in the order they matter

### 5a. Canonical lock ordering (THE LESSON)

```java
Account first  = from.getId().compareTo(to.getId()) < 0 ? from : to;
Account second = (first == from) ? to : from;

first.getLock().lock();
try {
    second.getLock().lock();
    try { ... } finally { second.getLock().unlock(); }
} finally { first.getLock().unlock(); }
```

Why **lexicographic on the id** and not on `System.identityHashCode`?

- `identityHashCode` is per-JVM-run. Restarts shuffle it. The order changes.
- `compareTo` on the *id string* is stable across all JVMs, all time, all
  callers. That's the property deadlock-avoidance requires.

The same trick generalizes to **N locks**: sort the list of account ids,
acquire in order. Used by `lockMultiple([A,B,C,D])` style APIs.

### 5b. Per-account `ReentrantLock` (co-located with the model)

Each `Account` carries its own lock. Why colocate?

- The caller already has the `Account` reference → no lookup table needed.
- The "I need two locks" fact is **visible at the call site**, which is
  the whole pedagogical point of this project.

### 5c. Idempotency via `clientRequestId`

Same shape as `HotelBooking`: a `ConcurrentHashMap<requestId, Transfer>`
with the double-check-after-lock idiom.

```java
// fast path (no lock)
Transfer existing = transfersByRequest.get(clientRequestId);
if (existing != null) return existing;

// ... acquire both locks ...

// slow-path re-check inside the lock
existing = transfersByRequest.get(clientRequestId);
if (existing != null) return existing;
```

Why is this *especially* important for transfers?

Because the failure mode is **double debit on retry**. A flaky network
causes the client to retry; without idempotency, the second request
debits the same account again. Both calls succeed; balance is wrong;
customer is angry; regulator is angrier.

### 5d. `long cents`, never `double dollars`

```java
private long balanceCents;
```

IEEE 754 double can't represent `0.1 + 0.2` exactly. After ~1000 cent-
level transfers the balance is off by pennies. We use integer cents and
the math is exact.

In a real system: `BigDecimal` if you need fractional cents (some
currencies have 4 decimal places); `long cents` is fine for USD/EUR/INR.

### 5e. Self-transfer short-circuit

```java
if (fromId.equals(toId)) { ... lock once ... }
```

`A → A` is a special case. Sorting `[A, A]` gives `[A, A]`, so the
ordered-locking code tries to lock the **same lock twice**. `ReentrantLock`
allows reentrance, so this is correct (debit and credit cancel out), but
it's clearer to short-circuit.

---

## 6. The race we close with the double-check

```text
Thread X: transfer(req="k1", $200 A→B)      Thread Y: transfer(req="k1", $200 A→B) -- retry
  pre-check k1 -> null                        pre-check k1 -> null

  acquire lock(A)
  acquire lock(B)
  re-check k1 -> null
  debit A, credit B
  record transfer T1
  set k1 → T1
  release locks

                                              acquire lock(A)
                                              acquire lock(B)
                                              re-check k1 → T1   ← returns T1 without retry!
                                              release locks
                                              return T1
```

The retry **never executes the debit/credit a second time**. It just
returns the already-recorded Transfer. This is the "at-most-once on retry"
guarantee.

---

## 7. Demo (`Main.java`)

Four scenarios.

### Scenario 1 — Cross-transfer storm

Two threads pair up to do `A→B` and `B→A` 1000 times each, simultaneously.
Without ordered locking, this deadlocks in seconds. With it, all 2000
transfers complete and the total balance is preserved exactly.

```
=== Scenario 1: cross-transfer storm (would deadlock without ordered locking) ===
  done. A=$1000000.0 B=$1000000.0  total=$2000000.0 (expected $2000000.0)
```

### Scenario 2 — Insufficient funds

Try to transfer more than the source has. Must throw, and the balance must
be unchanged.

```
=== Scenario 2: insufficient funds ===
  caught (as expected): Account X has $10.0, requested $15.0 ...
  balances unchanged: X=$10.0 Y=$0.0
```

### Scenario 3 — Idempotent retry

Same `clientRequestId` twice. Q must be credited only **once**.

```
=== Scenario 3: idempotent retry ===
  first transferId=dc801aa9-...
  retry transferId=dc801aa9-...  (must equal first)
  P=$800.0  Q=$200.0  (Q must be $200, NOT $400)
```

### Scenario 4 — Conservation of money under chaos

100 threads × 50 random transfers between 5 accounts. The *sum* of
balances must equal the initial total.

```
=== Scenario 4: random transfers between 5 accounts; total invariant ===
  ok=5000 insufficient=0 finalTotal=500000 initialTotal=500000
```

---

## 8. How to run

```bash
cd bel-20
javac -d /tmp/at-build $(find LLD/LldQuestions/AtmTransfer -name '*.java')
java -cp /tmp/at-build LLD.LldQuestions.AtmTransfer.Main
```

---

## 9. What was deliberately left out

| Skipped | Why fine for basic LLD | What to add for production |
|---|---|---|
| Persistence | In-memory accounts | DB transaction around `(debit, credit, insert transfer)`; ACID guarantees match the locking guarantees |
| Distributed (cross-bank) | Same JVM | Saga / 2PC: tentative debit on bank A, commit on bank B success, rollback otherwise. Lock-free at the saga level. |
| Audit log | Just `Transfer` object | Append-only event log; idempotency-key-based dedup at the DB level (UNIQUE constraint) |
| Reversal / refund | None | Same shape as transfer, with a back-pointer; idempotency key prevents double-refund |
| Holds / authorizations | Direct transfer only | Two-phase: `authorize` (soft hold) → `capture` (commit). Same pattern as TicketBooking. |
| Fees | None | Strategy: source fee, dest fee, both — easy after the transfer engine is right |
| Currency conversion | Single currency | Wrap balance in `(amount, currency)`; reject mismatched transfers; integrate an FX service |
| Rate limiting | None | Plug in project #3 (`RateLimiter`) keyed by source account |

---

## 10. Interview-ready one-liner

> Each account owns its own `ReentrantLock`. A transfer between accounts A
> and B acquires both locks in **lexicographic id order** (`min(id) first`),
> guaranteeing no cycle in the wait-graph and therefore no deadlock under
> any workload — including the cross-transfer storm `A→B || B→A`.
> Idempotency is a `ConcurrentHashMap<requestId, Transfer>` with a
> double-check-after-lock so retries return the original Transfer rather
> than re-running the debit. Money is `long cents`, never `double dollars`,
> and the demo proves conservation of money under 5000 random transfers
> across 5 accounts on 100 threads.
