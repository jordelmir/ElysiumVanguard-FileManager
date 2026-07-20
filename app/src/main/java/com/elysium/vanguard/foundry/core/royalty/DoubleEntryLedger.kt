package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F5 third half (G6+G7, I-5.3) — the
 * **Double-Entry Ledger**, the platform's
 * accounting layer that records every settlement.
 *
 * The ledger is the **double-entry accounting**
 * system:
 *   - Every settlement has a **debit** + a
 *     **credit** (the platform's cut + the
 *     contributor's cut).
 *   - Every entry is **immutable** (a settled
 *     entry cannot be modified; a correction
 *     is a new compensating entry).
 *   - The ledger's totals are **balanced** (the
 *     sum of all debits equals the sum of all
 *     credits at any point in time).
 *
 * The ledger is the **audit** artifact: a
 * regulator (a tax authority, a financial
 * auditor) can inspect the ledger +
 * reconstruct every transaction.
 *
 * The ledger is **pure-domain** (no I/O, no
 * Android dependencies). The test implementation
 * is an in-memory ledger; the production
 * implementation is a persistent ledger (a
 * future Phase 7+ increment).
 *
 * The ledger is **append-only**: a new entry
 * appends to the existing list; existing
 * entries are never modified. The ledger's
 * state is the list of all entries.
 */
sealed class DoubleEntryLedger {

    /**
     * The ledger's current state. The state
     * is the list of all entries (in
     * append order).
     */
    abstract val entries: List<LedgerEntry>

    /**
     * Append a new entry to the ledger. The
     * entry is added to the end of the
     * list; the existing entries are
     * preserved.
     */
    abstract fun append(entry: LedgerEntry): Result<Unit>

    /**
     * The total debits for a currency. The
     * total is the sum of every entry's
     * debit amount for the currency.
     */
    abstract fun totalDebits(currency: String): BigDecimal

    /**
     * The total credits for a currency. The
     * total is the sum of every entry's
     * credit amount for the currency.
     */
    abstract fun totalCredits(currency: String): BigDecimal

    /**
     * Check whether the ledger is balanced
     * for a currency. The ledger is balanced
     * when `totalDebits(currency) ==
     * totalCredits(currency)`.
     */
    fun isBalanced(currency: String): Boolean =
        totalDebits(currency).compareTo(totalCredits(currency)) == 0
}

/**
 * The typed ledger entry. The entry is
 * **immutable** (a data class; no setters). A
 * new entry is a new value. The ledger's
 * lifecycle (a correction, a reversal) is a
 * new `LedgerEntry` value, not a mutation of
 * the existing one.
 *
 * Per double-entry accounting:
 *   - Every entry has a **debit account** +
 *     a **credit account** + an amount.
 *   - The sum of all debits equals the sum
 *     of all credits (the ledger is
 *     balanced).
 *   - The entry records the transaction
 *     id (the settlement id) + the
 *     timestamp + the description.
 */
data class LedgerEntry(
    /**
     * The entry's unique id. The id is a
     * UUID (per the Foundry id convention);
     * the id is the join key the auditor
     * uses to reference the entry.
     */
    val entryId: LedgerEntryId,

    /**
     * The transaction the entry records.
     * Every entry is for one transaction
     * (a settlement from Phase F5 first
     * half).
     */
    val transactionId: String,

    /**
     * The debit account. The debit is the
     * account that "loses" the money (per
     * standard accounting terminology).
     */
    val debitAccount: Account,

    /**
     * The credit account. The credit is the
     * account that "gains" the money.
     */
    val creditAccount: Account,

    /**
     * The entry's amount. The amount is a
     * `BigDecimal` in the entry's
     * currency (per ADR-0001). The amount
     * is positive; the direction is
     * encoded by the account roles.
     */
    val amount: BigDecimal,

    /**
     * The entry's currency. The currency
     * is a 3-letter ISO 4217 code.
     */
    val currency: String,

    /**
     * The entry's human-readable description.
     * The description is what the auditor
     * reads to understand the transaction.
     */
    val description: String,

    /**
     * The entry's timestamp. The timestamp
     * is the millis since epoch the entry
     * was appended.
     */
    val timestampMs: Long,

    /**
     * The entry's signature. The signature
     * binds the entry to the platform (a
     * future Phase 7+ increment may
     * require a multi-signature for
     * high-value entries).
     */
    val signature: Signature,
) {
    init {
        require(transactionId.isNotBlank()) {
            "LedgerEntry.transactionId must not be blank"
        }
        require(amount > BigDecimal.ZERO) {
            "LedgerEntry.amount must be > 0, got $amount"
        }
        require(currency.length == 3) {
            "LedgerEntry.currency must be a 3-letter ISO 4217 code, got: $currency"
        }
        require(currency.all { it.isUpperCase() }) {
            "LedgerEntry.currency must be uppercase, got: $currency"
        }
        require(description.isNotBlank()) {
            "LedgerEntry.description must not be blank"
        }
        require(timestampMs > 0) {
            "LedgerEntry.timestampMs must be > 0, got $timestampMs"
        }
    }
}

/**
 * The typed account. The account is a
 * **destination** in the ledger (a debit or
 * a credit).
 *
 * The account has:
 *   - `accountId` — UUID.
 *   - `displayName` — human-readable.
 *   - `type` — the typed account kind.
 *
 * The standard account types for the
 * platform are:
 *   - `PLATFORM_REVENUE` — the platform's
 *     revenue account (the platform's cut
 *     of every settlement).
 *   - `CONTRIBUTOR_RECEIVABLE` — the
 *     contributor's receivable account
 *     (the contributor's cut of every
 *     settlement; the platform owes the
 *     contributor this amount).
 *   - `CUSTOMER_CASH` — the customer's
 *     cash account (the customer paid the
 *     gross amount; the cash goes to
 *     PLATFORM_REVENUE + CONTRIBUTOR_RECEIVABLE).
 */
data class Account(
    val accountId: AccountId,
    val displayName: String,
    val type: AccountType,
) {
    init {
        require(displayName.isNotBlank()) {
            "Account.displayName must not be blank"
        }
    }
}

/**
 * The typed account kind. The kind is the
 * **typed classification** of the account;
 * a `when` on the kind is **exhaustive**.
 */
enum class AccountType(val displayLabel: String) {
    /** The platform's revenue account. */
    PLATFORM_REVENUE("Platform Revenue"),

    /** The contributor's receivable account. */
    CONTRIBUTOR_RECEIVABLE("Contributor Receivable"),

    /** The customer's cash account. */
    CUSTOMER_CASH("Customer Cash"),

    /** A general expense account. */
    EXPENSE("Expense"),

    /** A general liability account. */
    LIABILITY("Liability"),
}

/**
 * The typed id of a ledger entry. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class LedgerEntryId(val value: UUID) {
    companion object {
        fun random(): LedgerEntryId = LedgerEntryId(UUID.randomUUID())
        fun from(raw: String): Result<LedgerEntryId> = try {
            Result.success(LedgerEntryId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("LedgerEntryId", raw, e))
        }
    }
}

/**
 * The typed id of an account. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun random(): AccountId = AccountId(UUID.randomUUID())
        fun from(raw: String): Result<AccountId> = try {
            Result.success(AccountId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("AccountId", raw, e))
        }
    }
}

/**
 * The in-memory [DoubleEntryLedger] for testing.
 * The ledger is the stateless composition of:
 *   - A list of entries (append-only).
 *
 * The ledger is **thread-safe** (the underlying
 * list is a `CopyOnWriteArrayList` for safe
 * iteration during total computation).
 */
class InMemoryDoubleEntryLedger : DoubleEntryLedger() {

    private val mutableEntries:
        java.util.concurrent.CopyOnWriteArrayList<LedgerEntry> =
        java.util.concurrent.CopyOnWriteArrayList()

    override val entries: List<LedgerEntry>
        get() = mutableEntries.toList()

    override fun append(entry: LedgerEntry): Result<Unit> {
        mutableEntries.add(entry)
        return Result.success(Unit)
    }

    override fun totalDebits(currency: String): BigDecimal =
        mutableEntries
            .filter { it.currency == currency }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }

    override fun totalCredits(currency: String): BigDecimal =
        mutableEntries
            .filter { it.currency == currency }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount }
}
