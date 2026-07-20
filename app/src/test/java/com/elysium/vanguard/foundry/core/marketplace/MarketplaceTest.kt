package com.elysium.vanguard.foundry.core.marketplace

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Phase F6 first half (G8, I-6.1) — the JVM
 * tests for the Marketplace primitives
 * (RFQ + Offer + Order).
 *
 * The tests cover:
 *   - RFQ.Open invariants (blank
 *     componentSpec, non-positive quantity,
 *     negative maxBudget, non-3-letter
 *     currency, non-positive deadline).
 *   - RFQ.Closed status is CLOSED.
 *   - Offer invariants (non-positive
 *     pricePerUnit, non-3-letter currency,
 *     non-positive deliveryTimeDays,
 *     non-positive validUntilMs).
 *   - Order invariants (non-positive
 *     totalAmount, non-3-letter currency,
 *     non-positive timestamp).
 *   - Every status enum has the right values.
 *   - Realistic scenario: a buyer creates
 *     an RFQ; a supplier submits an Offer;
 *     the buyer accepts the Offer → creates
 *     an Order.
 */
class MarketplaceTest {

    // ============================================================
    // RFQ.Open invariants
    // ============================================================

    @Test
    fun `RFQ Open accepts a well-formed configuration`() {
        val rfq = buildOpenRFQ()
        assertEquals(RFQStatus.OPEN, rfq.status)
    }

    @Test
    fun `RFQ Open rejects blank componentSpec`() {
        try {
            buildOpenRFQ(componentSpec = "")
            fail("expected IllegalArgumentException for blank componentSpec")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("componentSpec"))
        }
    }

    @Test
    fun `RFQ Open rejects zero quantity`() {
        try {
            buildOpenRFQ(quantity = 0)
            fail("expected IllegalArgumentException for zero quantity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("quantity"))
        }
    }

    @Test
    fun `RFQ Open rejects negative maxBudget`() {
        try {
            buildOpenRFQ(maxBudget = BigDecimal("-1.0"))
            fail("expected IllegalArgumentException for negative maxBudget")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxBudget"))
        }
    }

    @Test
    fun `RFQ Open rejects non-3-letter currency`() {
        try {
            buildOpenRFQ(currency = "US")
            fail("expected IllegalArgumentException for non-3-letter currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    @Test
    fun `RFQ Open rejects non-positive deadlineMs`() {
        try {
            buildOpenRFQ(deadlineMs = 0L)
            fail("expected IllegalArgumentException for non-positive deadlineMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("deadlineMs"))
        }
    }

    // ============================================================
    // RFQ.Closed
    // ============================================================

    @Test
    fun `RFQ Closed status is CLOSED`() {
        val rfq = buildClosedRFQ()
        assertEquals(RFQStatus.CLOSED, rfq.status)
    }

    // ============================================================
    // Offer invariants
    // ============================================================

    @Test
    fun `Offer accepts a well-formed configuration`() {
        val offer = buildOffer()
        assertEquals(OfferStatus.PENDING, offer.status)
    }

    @Test
    fun `Offer rejects zero pricePerUnit`() {
        try {
            buildOffer(pricePerUnit = BigDecimal.ZERO)
            fail("expected IllegalArgumentException for zero pricePerUnit")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pricePerUnit"))
        }
    }

    @Test
    fun `Offer rejects non-3-letter currency`() {
        try {
            buildOffer(currency = "US")
            fail("expected IllegalArgumentException for non-3-letter currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    @Test
    fun `Offer rejects non-positive deliveryTimeDays`() {
        try {
            buildOffer(deliveryTimeDays = 0)
            fail("expected IllegalArgumentException for zero deliveryTimeDays")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("deliveryTimeDays"))
        }
    }

    @Test
    fun `Offer rejects non-positive validUntilMs`() {
        try {
            buildOffer(validUntilMs = 0L)
            fail("expected IllegalArgumentException for non-positive validUntilMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("validUntilMs"))
        }
    }

    // ============================================================
    // Order invariants
    // ============================================================

    @Test
    fun `Order accepts a well-formed configuration`() {
        val order = buildOrder()
        assertEquals(OrderStatus.PENDING, order.status)
    }

    @Test
    fun `Order rejects zero totalAmount`() {
        try {
            buildOrder(totalAmount = BigDecimal.ZERO)
            fail("expected IllegalArgumentException for zero totalAmount")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("totalAmount"))
        }
    }

    @Test
    fun `Order rejects non-3-letter currency`() {
        try {
            buildOrder(currency = "US")
            fail("expected IllegalArgumentException for non-3-letter currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    @Test
    fun `Order rejects non-positive timestampMs`() {
        try {
            buildOrder(timestampMs = 0L)
            fail("expected IllegalArgumentException for non-positive timestampMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // Status enums
    // ============================================================

    @Test
    fun `RFQStatus has OPEN and CLOSED`() {
        assertNotNull(RFQStatus.OPEN)
        assertNotNull(RFQStatus.CLOSED)
    }

    @Test
    fun `OfferStatus has the 4 lifecycle states`() {
        assertNotNull(OfferStatus.PENDING)
        assertNotNull(OfferStatus.ACCEPTED)
        assertNotNull(OfferStatus.REJECTED)
        assertNotNull(OfferStatus.EXPIRED)
    }

    @Test
    fun `OrderStatus has the 3 lifecycle states`() {
        assertNotNull(OrderStatus.PENDING)
        assertNotNull(OrderStatus.FULFILLED)
        assertNotNull(OrderStatus.CANCELLED)
    }

    // ============================================================
    // Realistic scenario: the full marketplace flow
    // ============================================================

    @Test
    fun `realistic scenario a buyer creates an RFQ, supplier offers, buyer accepts, order is created`() {
        // Step 1: The buyer creates an RFQ.
        val rfq = buildOpenRFQ(
            componentSpec = "V8 engine block, 6.2L displacement, 450 HP",
            quantity = 10,
            maxBudget = BigDecimal("50000.00"),
            currency = "USD",
        )
        assertEquals(RFQStatus.OPEN, rfq.status)

        // Step 2: A supplier submits an offer.
        val offer = buildOffer(
            rfqId = rfq.rfqId,
            pricePerUnit = BigDecimal("4500.00"),
            deliveryTimeDays = 30,
        )
        assertEquals(OfferStatus.PENDING, offer.status)
        assertEquals(rfq.rfqId, offer.rfqId)

        // Step 3: The buyer accepts the offer.
        val acceptedOffer = offer.copy(status = OfferStatus.ACCEPTED)
        // The RFQ is now closed (the buyer has
        // accepted an offer).
        val closedRFQ = RFQ.Closed(
            rfqId = rfq.rfqId,
            buyerId = rfq.buyerId,
            programId = rfq.programId,
            componentSpec = rfq.componentSpec,
            quantity = rfq.quantity,
            maxBudget = rfq.maxBudget,
            currency = rfq.currency,
            deadlineMs = rfq.deadlineMs,
            signature = rfq.signature,
            acceptedOfferId = offer.offerId,
        )
        assertEquals(RFQStatus.CLOSED, closedRFQ.status)
        assertEquals(offer.offerId, closedRFQ.acceptedOfferId)

        // Step 4: The order is created.
        val order = buildOrder(
            rfqId = rfq.rfqId,
            offerId = offer.offerId,
            buyerId = rfq.buyerId,
            totalAmount = offer.pricePerUnit.multiply(
                BigDecimal(rfq.quantity),
            ),
            currency = offer.currency,
        )
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(
            BigDecimal("45000.00"),
            order.totalAmount,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildOpenRFQ(
        componentSpec: String = "Test component",
        quantity: Int = 1,
        maxBudget: BigDecimal = BigDecimal("1000.00"),
        currency: String = "USD",
        deadlineMs: Long = 1_000L,
    ): RFQ.Open = RFQ.Open(
        rfqId = RFQId.random(),
        buyerId = UserId.random(),
        programId = VehicleProgramId.random(),
        componentSpec = componentSpec,
        quantity = quantity,
        maxBudget = maxBudget,
        currency = currency,
        deadlineMs = deadlineMs,
        signature = Signature("rfq-signature"),
    )

    private fun buildClosedRFQ(
        acceptedOfferId: OfferId? = null,
    ): RFQ.Closed = RFQ.Closed(
        rfqId = RFQId.random(),
        buyerId = UserId.random(),
        programId = VehicleProgramId.random(),
        componentSpec = "Test component",
        quantity = 1,
        maxBudget = BigDecimal("1000.00"),
        currency = "USD",
        deadlineMs = 1_000L,
        signature = Signature("rfq-signature"),
        acceptedOfferId = acceptedOfferId,
    )

    private fun buildOffer(
        rfqId: RFQId = RFQId.random(),
        supplierId: UserId = UserId.random(),
        pricePerUnit: BigDecimal = BigDecimal("100.00"),
        currency: String = "USD",
        deliveryTimeDays: Int = 30,
        validUntilMs: Long = 1_000L,
        status: OfferStatus = OfferStatus.PENDING,
    ): Offer = Offer(
        offerId = OfferId.random(),
        rfqId = rfqId,
        supplierId = supplierId,
        pricePerUnit = pricePerUnit,
        currency = currency,
        deliveryTimeDays = deliveryTimeDays,
        validUntilMs = validUntilMs,
        status = status,
        signature = Signature("offer-signature"),
    )

    private fun buildOrder(
        rfqId: RFQId = RFQId.random(),
        offerId: OfferId = OfferId.random(),
        buyerId: UserId = UserId.random(),
        supplierId: UserId = UserId.random(),
        totalAmount: BigDecimal = BigDecimal("100.00"),
        currency: String = "USD",
        status: OrderStatus = OrderStatus.PENDING,
        timestampMs: Long = 1_000L,
    ): Order = Order(
        orderId = OrderId.random(),
        rfqId = rfqId,
        offerId = offerId,
        buyerId = buyerId,
        supplierId = supplierId,
        programId = VehicleProgramId.random(),
        totalAmount = totalAmount,
        currency = currency,
        status = status,
        timestampMs = timestampMs,
        signature = Signature("order-signature"),
    )
}
