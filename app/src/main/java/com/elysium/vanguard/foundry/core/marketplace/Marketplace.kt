package com.elysium.vanguard.foundry.core.marketplace

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F6 first half (G8, I-6.1) — the
 * **Marketplace**, the B2B marketplace primitives
 * for the Foundry's contributor program.
 *
 * The marketplace is the **commercial layer**
 * that uses the royalty foundation (Phase F5
 * first half), the license (Phase F5 second
 * half), and the double-entry ledger (Phase F5
 * third half) to power B2B transactions.
 *
 * The marketplace is **3 primitives**:
 *
 *   - **`RFQ` (Request for Quote)** — a buyer
 *     requests quotes from suppliers for a
 *     specific vehicle component.
 *   - **`Offer`** — a supplier responds to an
 *     RFQ with a quote (price + delivery time).
 *   - **`Order`** — a buyer accepts an offer +
 *     creates an order (the transaction is
 *     committed).
 *
 * The 3 primitives form the **canonical B2B
 * marketplace flow**:
 *   1. The buyer creates an RFQ.
 *   2. Suppliers submit Offers.
 *   3. The buyer accepts an Offer → creates an
 *      Order.
 *   4. The Order is recorded in the double-
 *      entry ledger.
 *
 * The marketplace is **pure-domain** (no I/O,
 * no Android dependencies). The test
 * implementation is an in-memory registry;
 * the production implementation is a
 * distributed registry (a future Phase 7+
 * increment).
 */
sealed class RFQ {

    /**
     * The RFQ's unique id. The id is a UUID
     * (per the Foundry id convention); the
     * id is the join key the supplier uses
     * to find the RFQ.
     */
    abstract val rfqId: RFQId

    /**
     * The buyer who created the RFQ. The
     * buyer is a [UserId] (the platform
     * user; the buyer is NOT a model
     * agent).
     */
    abstract val buyerId: UserId

    /**
     * The program the RFQ is for. The
     * supplier's offer must be for the same
     * program.
     */
    abstract val programId: VehicleProgramId

    /**
     * The RFQ's component spec. The
     * component spec is a human-readable
     * description of the component the
     * buyer is requesting (e.g. "V8 engine
     * block, 6.2L displacement, 450 HP").
     */
    abstract val componentSpec: String

    /**
     * The RFQ's quantity. The quantity is
     * a positive integer (the buyer needs
     * N units).
     */
    abstract val quantity: Int

    /**
     * The RFQ's max budget. The max budget
     * is a `BigDecimal` in the RFQ's
     * currency (per ADR-0001). The supplier's
     * offer may exceed the max budget (the
     * buyer may choose to accept a higher
     * price; the max budget is a filter,
     * not a hard limit).
     */
    abstract val maxBudget: BigDecimal

    /**
     * The RFQ's currency. The currency is a
     * 3-letter ISO 4217 code.
     */
    abstract val currency: String

    /**
     * The RFQ's deadline. The deadline is
     * the millis since epoch the supplier
     * must respond by.
     */
    abstract val deadlineMs: Long

    /**
     * The RFQ's signature. The signature
     * binds the RFQ to the buyer.
     */
    abstract val signature: Signature

    /**
     * The RFQ's status. The status is a
     * typed lifecycle value.
     */
    abstract val status: RFQStatus

    /**
     * An open RFQ. Suppliers can submit
     * offers.
     */
    data class Open(
        override val rfqId: RFQId,
        override val buyerId: UserId,
        override val programId: VehicleProgramId,
        override val componentSpec: String,
        override val quantity: Int,
        override val maxBudget: BigDecimal,
        override val currency: String,
        override val deadlineMs: Long,
        override val signature: Signature,
    ) : RFQ() {
        override val status: RFQStatus = RFQStatus.OPEN
        init {
            require(componentSpec.isNotBlank()) {
                "RFQ.Open.componentSpec must not be blank"
            }
            require(quantity > 0) {
                "RFQ.Open.quantity must be > 0, got $quantity"
            }
            require(maxBudget >= BigDecimal.ZERO) {
                "RFQ.Open.maxBudget must be >= 0, got $maxBudget"
            }
            require(currency.length == 3) {
                "RFQ.Open.currency must be a 3-letter ISO 4217 code, " +
                    "got: $currency"
            }
            require(deadlineMs > 0) {
                "RFQ.Open.deadlineMs must be > 0, got $deadlineMs"
            }
        }
    }

    /**
     * A closed RFQ. The buyer has accepted
     * an offer (or the deadline has passed);
     * no more offers are accepted.
     */
    data class Closed(
        override val rfqId: RFQId,
        override val buyerId: UserId,
        override val programId: VehicleProgramId,
        override val componentSpec: String,
        override val quantity: Int,
        override val maxBudget: BigDecimal,
        override val currency: String,
        override val deadlineMs: Long,
        override val signature: Signature,
        val acceptedOfferId: OfferId?,
    ) : RFQ() {
        override val status: RFQStatus = RFQStatus.CLOSED
    }
}

/**
 * The typed status of an RFQ.
 */
enum class RFQStatus {
    /** The RFQ is open; suppliers can submit
     *  offers. */
    OPEN,

    /** The RFQ is closed; no more offers are
     *  accepted. The buyer has accepted an
     *  offer or the deadline has passed. */
    CLOSED,
}

/**
 * The typed id of an RFQ. The id is a UUID
 * (per the Foundry id convention).
 */
@JvmInline
value class RFQId(val value: UUID) {
    companion object {
        fun random(): RFQId = RFQId(UUID.randomUUID())
        fun from(raw: String): Result<RFQId> = try {
            Result.success(RFQId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("RFQId", raw, e))
        }
    }
}

/**
 * The **Offer**, a supplier's response to an
 * RFQ. The offer has:
 *   - **`offerId`** — UUID.
 *   - **`rfqId`** — the RFQ the offer is for.
 *   - **`supplierId`** — the supplier (a [UserId]
 *     for human suppliers; a future
 *     `SupplierId` for organization suppliers).
 *   - **`pricePerUnit`** — `BigDecimal` in the
 *     offer's currency (per ADR-0001).
 *   - **`currency`** — 3-letter ISO 4217 code.
 *   - **`deliveryTimeDays`** — the supplier's
 *     estimated delivery time in days.
 *   - **`validUntilMs`** — the offer's
 *     expiration timestamp (the buyer must
 *     accept before this time).
 *   - **`status`** — the offer's lifecycle state.
 *   - **`signature`** — the offer's signature.
 *
 * The offer is **immutable** (a data class; no
 * setters). A new offer is a new value. The
 * offer's lifecycle (an acceptance, a
 * rejection) is a new `Offer` value, not a
 * mutation of the existing one.
 */
data class Offer(
    val offerId: OfferId,
    val rfqId: RFQId,
    val supplierId: UserId,
    val pricePerUnit: BigDecimal,
    val currency: String,
    val deliveryTimeDays: Int,
    val validUntilMs: Long,
    val status: OfferStatus,
    val signature: Signature,
) {
    init {
        require(pricePerUnit > BigDecimal.ZERO) {
            "Offer.pricePerUnit must be > 0, got $pricePerUnit"
        }
        require(currency.length == 3) {
            "Offer.currency must be a 3-letter ISO 4217 code, " +
                "got: $currency"
        }
        require(deliveryTimeDays > 0) {
            "Offer.deliveryTimeDays must be > 0, got $deliveryTimeDays"
        }
        require(validUntilMs > 0) {
            "Offer.validUntilMs must be > 0, got $validUntilMs"
        }
    }
}

/**
 * The typed status of an offer.
 */
enum class OfferStatus {
    /** The offer is pending; the buyer has
     *  not yet decided. */
    PENDING,

    /** The offer has been accepted by the
     *  buyer; the order is created. */
    ACCEPTED,

    /** The offer has been rejected by the
     *  buyer. */
    REJECTED,

    /** The offer has expired (the validUntilMs
     *  has passed). */
    EXPIRED,
}

/**
 * The typed id of an offer. The id is a UUID
 * (per the Foundry id convention).
 */
@JvmInline
value class OfferId(val value: UUID) {
    companion object {
        fun random(): OfferId = OfferId(UUID.randomUUID())
        fun from(raw: String): Result<OfferId> = try {
            Result.success(OfferId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("OfferId", raw, e))
        }
    }
}

/**
 * The **Order**, the buyer's acceptance of an
 * offer. The order has:
 *   - **`orderId`** — UUID.
 *   - **`rfqId`** — the RFQ the order is for.
 *   - **`offerId`** — the offer the order is
 *     accepting.
 *   - **`buyerId`** — the buyer (a [UserId]).
 *   - **`supplierId`** — the supplier.
 *   - **`programId`** — the program the order
 *     is for.
 *   - **`totalAmount`** — the total order
 *     amount (pricePerUnit * quantity) in
 *     the order's currency.
 *   - **`currency`** — 3-letter ISO 4217 code.
 *   - **`status`** — the order's lifecycle state.
 *   - **`timestampMs`** — when the order was
 *     created.
 *   - **`signature`** — the order's signature.
 *
 * The order is **immutable** (a data class; no
 * setters). A new order is a new value. The
 * order's lifecycle (a fulfillment, a
 * cancellation) is a new `Order` value, not a
 * mutation of the existing one.
 *
 * The order is the **commit point** of the
 * marketplace: once the order is created, the
 * transaction is recorded in the double-entry
 * ledger.
 */
data class Order(
    val orderId: OrderId,
    val rfqId: RFQId,
    val offerId: OfferId,
    val buyerId: UserId,
    val supplierId: UserId,
    val programId: VehicleProgramId,
    val totalAmount: BigDecimal,
    val currency: String,
    val status: OrderStatus,
    val timestampMs: Long,
    val signature: Signature,
) {
    init {
        require(totalAmount > BigDecimal.ZERO) {
            "Order.totalAmount must be > 0, got $totalAmount"
        }
        require(currency.length == 3) {
            "Order.currency must be a 3-letter ISO 4217 code, " +
                "got: $currency"
        }
        require(timestampMs > 0) {
            "Order.timestampMs must be > 0, got $timestampMs"
        }
    }
}

/**
 * The typed status of an order.
 */
enum class OrderStatus {
    /** The order is pending; the supplier
     *  has not yet fulfilled. */
    PENDING,

    /** The order is fulfilled; the supplier
     *  has delivered the component. */
    FULFILLED,

    /** The order is cancelled; the buyer or
     *  the supplier cancelled before
     *  fulfillment. */
    CANCELLED,
}

/**
 * The typed id of an order. The id is a UUID
 * (per the Foundry id convention).
 */
@JvmInline
value class OrderId(val value: UUID) {
    companion object {
        fun random(): OrderId = OrderId(UUID.randomUUID())
        fun from(raw: String): Result<OrderId> = try {
            Result.success(OrderId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("OrderId", raw, e))
        }
    }
}
