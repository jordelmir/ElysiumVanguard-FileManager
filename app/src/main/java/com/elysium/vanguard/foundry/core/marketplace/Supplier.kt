package com.elysium.vanguard.foundry.core.marketplace

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The retry classification for a
 * [SupplierRegistryError]. Mirrors
 * [FoundryError.RetryClassification]
 * because the platform's error contract
 * is preserved (per `.ai/AGENTS.md`
 * section 24.4 — every error has a
 * retry classification).
 *
 * Why this is a separate enum (not
 * reusing [FoundryError.RetryClassification]
 * directly): Kotlin sealed classes only
 * permit subclassing in the same package
 * where the base class is declared. The
 * [SupplierRegistryError] lives in the
 * `marketplace` package; [FoundryError]
 * lives in `ontology.primitives`. The
 * error envelope is reused via this
 * mirror enum + the same field shape
 * (`code`, `retryClassification`,
 * `message`).
 */
enum class SupplierRegistryRetryClassification {
    /** The client MAY retry the same
     *  request immediately. */
    RETRYABLE_IMMEDIATE,

    /** The client MAY retry after
     *  exponential backoff. */
    RETRYABLE_BACKOFF,

    /** The client MAY retry only if the
     *  request is idempotent. */
    RETRYABLE_IDEMPOTENT_ONLY,

    /** The client MUST NOT retry. */
    NON_RETRYABLE,
}

/**
 * Phase F6 second half (G8, I-6.2) — the
 * **Supplier Network**, the typed directory
 * the marketplace uses to find suppliers.
 *
 * The supplier network is the **discovery
 * layer** for the marketplace (Phase F6
 * first half). The marketplace flow is:
 *
 *   1. The buyer creates an **RFQ**
 *      (Phase F6 first half).
 *   2. The marketplace **queries the
 *      supplier network** for suppliers
 *      that match the RFQ's
 *      componentSpec + region.
 *   3. The matching suppliers submit
 *      **Offers**.
 *   4. The buyer accepts an Offer → creates
 *      an **Order**.
 *
 * The supplier network is the **step 2**
 * primitive: the typed registry of
 * suppliers, capabilities, regions, and
 * certifications. The registry is
 * **pure-domain** (no I/O, no Android
 * dependencies). The test implementation is
 * an in-memory registry; the production
 * implementation is a distributed registry
 * (a future Phase 7+ increment).
 *
 * The supplier network is **5 primitives**:
 *
 *   - **`Supplier`** — the typed identity
 *     of a supplier (a human or an
 *     organization).
 *   - **`SupplierQualification`** — the
 *     typed record of a supplier's
 *     capabilities (what they can supply,
 *     where they serve, the certifications
 *     they hold).
 *   - **`SupplierCapability`** — a single
 *     capability the supplier offers
 *     (e.g. "engine-blocks for passenger
 *     cars, 100-5000 units/year").
 *   - **`Certification`** — a typed
 *     certification the supplier holds
 *     (e.g. "ISO 9001 issued by TÜV SÜD
 *     until 2027-12-31").
 *   - **`Region`** — a typed region the
 *     supplier serves (a country, a
 *     continent, or worldwide).
 *
 * The 5 primitives form the **canonical
 * supplier network**:
 *
 *   1. A supplier registers (the
 *      `Supplier` is added to the
 *      registry).
 *   2. The supplier submits a
 *      `SupplierQualification` (the
 *      capabilities + regions +
 *      certifications are added).
 *   3. The marketplace queries the
 *      registry for suppliers that match
 *      a capability (e.g. "engine-blocks")
 *      AND a region (e.g. Country "US").
 *   4. The matching suppliers are the
 *      candidates for the RFQ.
 */
sealed class SupplierRegistry {

    /**
     * The registry's current state. The
     * state is the list of all registered
     * suppliers + their qualifications
     * (in registration order).
     */
    abstract val suppliers: List<Supplier>

    /**
     * Register a new supplier. The supplier
     * is added to the registry; the
     * supplier's id is the join key.
     *
     * Returns a `Result.failure` if the
     * supplier id is already registered.
     */
    abstract fun register(supplier: Supplier): Result<Unit>

    /**
     * Add a qualification to a supplier. The
     * qualification is added to the
     * supplier's record; the supplier must
     * be registered first.
     *
     * Returns a `Result.failure` if the
     * supplier is not registered OR if the
     * qualification id is already used.
     */
    abstract fun addQualification(
        qualification: SupplierQualification,
    ): Result<Unit>

    /**
     * Get a supplier by id. Returns
     * `null` if the supplier is not
     * registered.
     */
    abstract fun getSupplier(supplierId: SupplierId): Supplier?

    /**
     * Get a supplier's qualifications.
     * Returns an empty list if the supplier
     * is not registered OR has no
     * qualifications yet.
     */
    abstract fun getQualifications(
        supplierId: SupplierId,
    ): List<SupplierQualification>

    /**
     * Find suppliers that offer a specific
     * capability. The match is exact
     * (case-sensitive) on the capability
     * name. Returns an empty list if no
     * supplier offers the capability.
     */
    abstract fun findByCapability(
        capabilityName: String,
    ): List<Supplier>

    /**
     * Find suppliers that serve a specific
     * country. The match is exact
     * (case-sensitive) on the 2-letter
     * ISO 3166-1 country code. A supplier
     * that serves "Worldwide" or
     * "Continental" is included when the
     * country is part of that region.
     * Returns an empty list if no
     * supplier serves the country.
     */
    abstract fun findByCountry(
        countryCode: String,
    ): List<Supplier>

    /**
     * Find suppliers that serve a specific
     * region. The match is exact on the
     * region. Returns an empty list if no
     * supplier serves the region.
     */
    abstract fun findByRegion(
        region: Region,
    ): List<Supplier>

    /**
     * Find suppliers that offer a specific
     * capability AND serve a specific
     * country. The intersection of
     * [findByCapability] and
     * [findByCountry]. Returns an empty
     * list if no supplier matches both
     * criteria.
     */
    abstract fun findByCapabilityAndCountry(
        capabilityName: String,
        countryCode: String,
    ): List<Supplier>
}

/**
 * The typed identity of a supplier. The id
 * is a UUID (per the Foundry id convention).
 *
 * The id is distinct from [UserId] because
 * a supplier can be an organization (not
 * just a single human user). The supplier
 * id is the join key the marketplace uses
 * to find the supplier.
 */
@JvmInline
value class SupplierId(val value: UUID) {
    companion object {
        fun random(): SupplierId = SupplierId(UUID.randomUUID())
        fun from(raw: String): Result<SupplierId> = try {
            Result.success(SupplierId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("SupplierId", raw, e))
        }
    }
}

/**
 * The typed id of a qualification. The id
 * is a UUID (per the Foundry id convention).
 *
 * The qualification id is distinct from
 * the supplier id because a supplier can
 * have multiple qualifications (e.g. a
 * qualification for engine blocks + a
 * qualification for transmissions).
 */
@JvmInline
value class QualificationId(val value: UUID) {
    companion object {
        fun random(): QualificationId = QualificationId(UUID.randomUUID())
        fun from(raw: String): Result<QualificationId> = try {
            Result.success(QualificationId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                FoundryError.InvalidUuidFormat("QualificationId", raw, e),
            )
        }
    }
}

/**
 * The **Supplier**, the typed identity of a
 * supplier. The supplier has:
 *   - **`supplierId`** — UUID; the join
 *     key the marketplace uses to find
 *     the supplier.
 *   - **`name`** — the supplier's
 *     display name (e.g. "Acme Powertrain
 *     Co.").
 *   - **`legalEntity`** — the legal name
 *     of the supplier (e.g. "Acme
 *     Powertrain Corporation, Inc.").
 *   - **`countryCode`** — the 2-letter
 *     ISO 3166-1 alpha-2 country code
 *     where the supplier is headquartered.
 *   - **`yearEstablished`** — the year
 *     the supplier was established
 *     (> 1800, <= current year).
 *   - **`contactEmail`** — the supplier's
 *     contact email (RFC 5322 simplified).
 *   - **`signature`** — the supplier's
 *     signature; binds the supplier to
 *     the registry.
 *
 * The supplier is **immutable** (a data
 * class; no setters). A new supplier is a
 * new value. The supplier's qualifications
 * (the capabilities + regions) are added
 * separately via [SupplierRegistry.addQualification].
 */
data class Supplier(
    val supplierId: SupplierId,
    val name: String,
    val legalEntity: String,
    val countryCode: String,
    val yearEstablished: Int,
    val contactEmail: String,
    val signature: Signature,
) {
    init {
        require(name.isNotBlank()) {
            "Supplier.name must not be blank"
        }
        require(legalEntity.isNotBlank()) {
            "Supplier.legalEntity must not be blank"
        }
        require(countryCode.length == 2) {
            "Supplier.countryCode must be a 2-letter ISO 3166-1 " +
                "alpha-2 code, got: $countryCode"
        }
        require(countryCode.all { it.isUpperCase() }) {
            "Supplier.countryCode must be uppercase, got: $countryCode"
        }
        require(countryCode.all { it.isLetter() }) {
            "Supplier.countryCode must be letters only, got: $countryCode"
        }
        require(yearEstablished in MIN_YEAR..MAX_YEAR) {
            "Supplier.yearEstablished must be in $MIN_YEAR..$MAX_YEAR, " +
                "got: $yearEstablished"
        }
        require(contactEmail.isNotBlank()) {
            "Supplier.contactEmail must not be blank"
        }
        require(contactEmail.contains("@") && contactEmail.contains(".")) {
            "Supplier.contactEmail must contain '@' and '.', " +
                "got: $contactEmail"
        }
    }

    companion object {
        /** The minimum year a supplier can
         *  be established. */
        const val MIN_YEAR: Int = 1800

        /** The maximum year a supplier can
         *  be established (the current
         *  year; suppliers cannot be from
         *  the future). */
        const val MAX_YEAR: Int = 2100
    }
}

/**
 * The **Certification**, a typed
 * certification the supplier holds. The
 * certification has:
 *   - **`name`** — the certification name
 *     (e.g. "ISO 9001", "IATF 16949",
 *     "AS9100").
 *   - **`issuer`** — the certification
 *     issuer (e.g. "TÜV SÜD", "BSI",
 *     "DNV").
 *   - **`validUntilMs`** — the
 *     certification's expiration
 *     timestamp (millis since epoch).
 *
 * The certification is **immutable** (a
 * data class; no setters). A re-certification
 * is a new `Certification` value.
 */
data class Certification(
    val name: String,
    val issuer: String,
    val validUntilMs: Long,
) {
    init {
        require(name.isNotBlank()) {
            "Certification.name must not be blank"
        }
        require(issuer.isNotBlank()) {
            "Certification.issuer must not be blank"
        }
        require(validUntilMs > 0) {
            "Certification.validUntilMs must be > 0, got $validUntilMs"
        }
    }
}

/**
 * The **SupplierCapability**, a single
 * capability the supplier offers. The
 * capability has:
 *   - **`capabilityName`** — the
 *     capability name (e.g.
 *     "engine-blocks", "transmissions",
 *     "wiring-harnesses").
 *   - **`vehicleDomain`** — the vehicle
 *     domain (e.g. "passenger-cars",
 *     "commercial-trucks", "motorcycles").
 *   - **`minVolumePerYear`** — the
 *     supplier's minimum annual
 *     production capacity (> 0).
 *   - **`maxVolumePerYear`** — the
 *     supplier's maximum annual
 *     production capacity (>= min).
 *   - **`certifications`** — the
 *     certifications the supplier holds
 *     for this capability (can be empty).
 *
 * The capability is **immutable** (a data
 * class; no setters). A capability
 * revision is a new
 * `SupplierQualification` value.
 */
data class SupplierCapability(
    val capabilityName: String,
    val vehicleDomain: String,
    val minVolumePerYear: Int,
    val maxVolumePerYear: Int,
    val certifications: List<Certification>,
) {
    init {
        require(capabilityName.isNotBlank()) {
            "SupplierCapability.capabilityName must not be blank"
        }
        require(vehicleDomain.isNotBlank()) {
            "SupplierCapability.vehicleDomain must not be blank"
        }
        require(minVolumePerYear > 0) {
            "SupplierCapability.minVolumePerYear must be > 0, " +
                "got $minVolumePerYear"
        }
        require(maxVolumePerYear >= minVolumePerYear) {
            "SupplierCapability.maxVolumePerYear ($maxVolumePerYear) " +
                "must be >= minVolumePerYear ($minVolumePerYear)"
        }
    }
}

/**
 * The **Region**, a typed region the
 * supplier serves. The region is one of:
 *   - **Country** — a single 2-letter
 *     ISO 3166-1 alpha-2 country code.
 *   - **Continental** — a continent code
 *     (e.g. "EU" for Europe, "AS" for
 *     Asia, "NA" for North America,
 *     "SA" for South America, "AF" for
 *     Africa, "OC" for Oceania, "AN" for
 *     Antarctica).
 *   - **Worldwide** — every country.
 */
sealed class Region {
    /**
     * A single 2-letter ISO 3166-1
     * alpha-2 country code.
     */
    data class Country(
        val countryCode: String,
    ) : Region() {
        init {
            require(countryCode.length == 2) {
                "Region.Country.countryCode must be a 2-letter " +
                    "ISO 3166-1 alpha-2 code, got: $countryCode"
            }
            require(countryCode.all { it.isUpperCase() }) {
                "Region.Country.countryCode must be uppercase, " +
                    "got: $countryCode"
            }
            require(countryCode.all { it.isLetter() }) {
                "Region.Country.countryCode must be letters only, " +
                    "got: $countryCode"
            }
        }
    }

    /**
     * A continent code. The 7 standard
     * continent codes are: AF, AS, EU,
     * NA, OC, SA, AN.
     */
    data class Continental(
        val continentCode: String,
    ) : Region() {
        init {
            require(continentCode in VALID_CONTINENT_CODES) {
                "Region.Continental.continentCode must be one of " +
                    "$VALID_CONTINENT_CODES, got: $continentCode"
            }
        }

        companion object {
            /** The 7 standard continent
             *  codes (per ISO 3166-1). */
            val VALID_CONTINENT_CODES: Set<String> = setOf(
                "AF", // Africa
                "AS", // Asia
                "EU", // Europe
                "NA", // North America
                "OC", // Oceania
                "SA", // South America
                "AN", // Antarctica
            )
        }
    }

    /**
     * Worldwide — every country. The
     * supplier serves any region.
     */
    data object Worldwide : Region()

    /**
     * Check whether this region includes
     * the given country code. The check
     * is:
     *   - [Country]: exact match on the
     *     2-letter code.
     *   - [Continental]: the country
     *     belongs to the continent
     *     (per [COUNTRY_TO_CONTINENT]).
     *   - [Worldwide]: always `true`.
     */
    fun includes(countryCode: String): Boolean = when (this) {
        is Country -> this.countryCode == countryCode
        is Continental ->
            COUNTRY_TO_CONTINENT[countryCode] == this.continentCode
        Worldwide -> true
    }

    companion object {
        /**
         * A small ISO 3166-1 alpha-2 →
         * continent code map. The map is
         * **not exhaustive** (it covers the
         * countries the platform supports;
         * a future Phase 7+ increment may
         * add more countries).
         */
        val COUNTRY_TO_CONTINENT: Map<String, String> = mapOf(
            // North America
            "US" to "NA", // United States
            "CA" to "NA", // Canada
            "MX" to "NA", // Mexico
            // South America
            "BR" to "SA", // Brazil
            "AR" to "SA", // Argentina
            "CL" to "SA", // Chile
            "CO" to "SA", // Colombia
            "PE" to "SA", // Peru
            // Europe
            "GB" to "EU", // United Kingdom
            "DE" to "EU", // Germany
            "FR" to "EU", // France
            "IT" to "EU", // Italy
            "ES" to "EU", // Spain
            "NL" to "EU", // Netherlands
            "PL" to "EU", // Poland
            "SE" to "EU", // Sweden
            "FI" to "EU", // Finland
            "NO" to "EU", // Norway
            "DK" to "EU", // Denmark
            // Asia
            "CN" to "AS", // China
            "JP" to "AS", // Japan
            "KR" to "AS", // South Korea
            "IN" to "AS", // India
            "TW" to "AS", // Taiwan
            "SG" to "AS", // Singapore
            "TH" to "AS", // Thailand
            "VN" to "AS", // Vietnam
            "MY" to "AS", // Malaysia
            "ID" to "AS", // Indonesia
            "PH" to "AS", // Philippines
            "IL" to "AS", // Israel
            "AE" to "AS", // United Arab Emirates
            "SA" to "AS", // Saudi Arabia
            "TR" to "AS", // Turkey
            // Oceania
            "AU" to "OC", // Australia
            "NZ" to "OC", // New Zealand
            // Africa
            "ZA" to "AF", // South Africa
            "EG" to "AF", // Egypt
            "NG" to "AF", // Nigeria
            "KE" to "AF", // Kenya
            "MA" to "AF", // Morocco
        )
    }
}

/**
 * The **SupplierQualification**, the typed
 * record of a supplier's capabilities. The
 * qualification has:
 *   - **`qualificationId`** — UUID; the
 *     join key the marketplace uses to
 *     reference the qualification.
 *   - **`supplierId`** — the supplier
 *     the qualification is for.
 *   - **`capabilities`** — the
 *     capabilities the supplier offers
 *     (e.g. "engine-blocks",
 *     "transmissions").
 *   - **`regions`** — the regions the
 *     supplier serves (e.g. Country "US",
 *     Continental "EU", Worldwide).
 *   - **`lastReviewedMs`** — the
 *     timestamp the qualification was
 *     last reviewed (millis since
 *     epoch).
 *   - **`reviewerId`** — the [UserId]
 *     that reviewed the qualification
 *     (a human reviewer; AI agents
 *     cannot approve supplier
 *     qualifications per the AI
 *     authority boundary in
 *     `.ai/AGENTS.md`).
 *   - **`signature`** — the
 *     qualification's signature; binds
 *     the qualification to the
 *     supplier + reviewer.
 *
 * The qualification is **immutable** (a
 * data class; no setters). A new
 * qualification is a new value. A
 * re-qualification (a new reviewer + a
 * new set of capabilities) is a new
 * `SupplierQualification` value with a
 * new id.
 */
data class SupplierQualification(
    val qualificationId: QualificationId,
    val supplierId: SupplierId,
    val capabilities: List<SupplierCapability>,
    val regions: List<Region>,
    val lastReviewedMs: Long,
    val reviewerId: UserId,
    val signature: Signature,
) {
    init {
        require(capabilities.isNotEmpty()) {
            "SupplierQualification.capabilities must not be empty"
        }
        require(regions.isNotEmpty()) {
            "SupplierQualification.regions must not be empty"
        }
        require(lastReviewedMs > 0) {
            "SupplierQualification.lastReviewedMs must be > 0, " +
                "got $lastReviewedMs"
        }
    }

    /**
     * Check whether the qualification
     * offers the given capability name.
     */
    fun offersCapability(capabilityName: String): Boolean =
        capabilities.any { it.capabilityName == capabilityName }

    /**
     * Check whether the qualification
     * serves the given country code. The
     * check uses [Region.includes].
     */
    fun servesCountry(countryCode: String): Boolean =
        regions.any { it.includes(countryCode) }
}

/**
 * The in-memory [SupplierRegistry] for
 * testing. The registry is the stateless
 * composition of:
 *   - A list of registered suppliers
 *     (insert order).
 *   - A map of supplier id → qualifications
 *     (append order).
 *
 * The registry is **thread-safe** (the
 * underlying collections are
 * `CopyOnWriteArrayList` for safe iteration
 * during query).
 */
class InMemorySupplierRegistry : SupplierRegistry() {

    private val mutableSuppliers:
        CopyOnWriteArrayList<Supplier> = CopyOnWriteArrayList()

    private val mutableQualifications:
        CopyOnWriteArrayList<SupplierQualification> =
        CopyOnWriteArrayList()

    /**
     * A fast lookup of supplier by id. The
     * map is rebuilt on every mutation
     * (register) — the registry is not a
     * hot path.
     */
    private val suppliersById: MutableMap<SupplierId, Supplier> =
        java.util.concurrent.ConcurrentHashMap()

    override val suppliers: List<Supplier>
        get() = mutableSuppliers.toList()

    override fun register(supplier: Supplier): Result<Unit> {
        if (suppliersById.containsKey(supplier.supplierId)) {
            return Result.failure(
                SupplierRegistryError.SupplierAlreadyRegistered(
                    supplier.supplierId,
                ),
            )
        }
        mutableSuppliers.add(supplier)
        suppliersById[supplier.supplierId] = supplier
        return Result.success(Unit)
    }

    override fun addQualification(
        qualification: SupplierQualification,
    ): Result<Unit> {
        if (!suppliersById.containsKey(qualification.supplierId)) {
            return Result.failure(
                SupplierRegistryError.SupplierNotFound(
                    qualification.supplierId,
                ),
            )
        }
        // Reject duplicate qualification ids
        // (every qualification id is unique).
        if (mutableQualifications.any {
                it.qualificationId == qualification.qualificationId
            }) {
            return Result.failure(
                SupplierRegistryError.DuplicateQualificationId(
                    qualification.qualificationId,
                ),
            )
        }
        mutableQualifications.add(qualification)
        return Result.success(Unit)
    }

    override fun getSupplier(supplierId: SupplierId): Supplier? =
        suppliersById[supplierId]

    override fun getQualifications(
        supplierId: SupplierId,
    ): List<SupplierQualification> =
        mutableQualifications.filter { it.supplierId == supplierId }

    override fun findByCapability(
        capabilityName: String,
    ): List<Supplier> {
        val supplierIds: Set<SupplierId> = mutableQualifications
            .filter { it.offersCapability(capabilityName) }
            .map { it.supplierId }
            .toSet()
        return mutableSuppliers.filter { it.supplierId in supplierIds }
    }

    override fun findByCountry(
        countryCode: String,
    ): List<Supplier> {
        val supplierIds: Set<SupplierId> = mutableQualifications
            .filter { it.servesCountry(countryCode) }
            .map { it.supplierId }
            .toSet()
        return mutableSuppliers.filter { it.supplierId in supplierIds }
    }

    override fun findByRegion(
        region: Region,
    ): List<Supplier> {
        val supplierIds: Set<SupplierId> = mutableQualifications
            .filter { quals ->
                quals.regions.any { it == region }
            }
            .map { it.supplierId }
            .toSet()
        return mutableSuppliers.filter { it.supplierId in supplierIds }
    }

    override fun findByCapabilityAndCountry(
        capabilityName: String,
        countryCode: String,
    ): List<Supplier> {
        val supplierIds: Set<SupplierId> = mutableQualifications
            .filter {
                it.offersCapability(capabilityName) &&
                    it.servesCountry(countryCode)
            }
            .map { it.supplierId }
            .toSet()
        return mutableSuppliers.filter { it.supplierId in supplierIds }
    }
}

/**
 * The typed error envelope for the
 * supplier network. The error mirrors
 * the [FoundryError] contract (same
 * `code` + `retryClassification` +
 * `message` shape) but lives in the
 * `marketplace` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared.
 *
 * Per `.ai/STANDARDS.md` section 7 +
 * `.ai/AGENTS.md` section 24.1:
 *   - A free-form string is never the
 *     value of an error.
 *   - Every error has a retry classification.
 */
sealed class SupplierRegistryError(
    message: String,
    val code: String,
    val retryClassification: SupplierRegistryRetryClassification,
) : RuntimeException(message) {

    /**
     * The supplier id is already registered.
     * The client must use the existing
     * supplier record OR deregister first.
     */
    data class SupplierAlreadyRegistered(
        val supplierId: SupplierId,
    ) : SupplierRegistryError(
        message = "Supplier already registered: ${supplierId.value}",
        code = "SUPPLIER_ALREADY_REGISTERED",
        retryClassification = SupplierRegistryRetryClassification.NON_RETRYABLE,
    )

    /**
     * The supplier id is not registered.
     * The client must register the supplier
     * before adding a qualification.
     */
    data class SupplierNotFound(
        val supplierId: SupplierId,
    ) : SupplierRegistryError(
        message = "Supplier not found: ${supplierId.value}",
        code = "SUPPLIER_NOT_FOUND",
        retryClassification = SupplierRegistryRetryClassification.NON_RETRYABLE,
    )

    /**
     * The qualification id is already used.
     * The client must use a new id
     * (every qualification has a unique id).
     */
    data class DuplicateQualificationId(
        val qualificationId: QualificationId,
    ) : SupplierRegistryError(
        message = "Duplicate qualification id: ${qualificationId.value}",
        code = "DUPLICATE_QUALIFICATION_ID",
        retryClassification = SupplierRegistryRetryClassification.NON_RETRYABLE,
    )
}
