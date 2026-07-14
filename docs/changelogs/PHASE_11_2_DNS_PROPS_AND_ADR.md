# Phase 11.2 — Property tests + ADR-016 + observer refactor

**Fecha:** 2026-07-13
**Status:** ✅ Compila · ✅ 962 tests verdes (+16 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+11.2

---

## TL;DR

Tres entregas, una fase:

1. **ADR-016** documenta la arquitectura reactiva del DNS que
   construimos en 11.0–11.1.
2. **Property tests** del pipeline `observe → distinctUntilChanged
   → drop(1) → collect`. 200 secuencias aleatorias por propiedad,
   siete propiedades, una invariante real que pilla la clase de
   bugs que el orden §33.2 llama "protocol decoders".
3. **Refactor del observer**: extraje `buildGuestDnsConfig` a una
   función pura JVM-testable y descubrí (vía tests) que `current()`
   tenía que re-snapshottar siempre, no devolver cache. Bug real
   que el test pilló en el primer run.

## Lo que entregué

### 1. ADR-016 — Network-aware DNS refresh

`docs/adr/ADR-016-network-aware-dns.md`. Cubre contexto, decisión
(tres capas con contratos estrechos), consecuencias, alternativas
descartadas, rollback, riesgos, status. Justifica la elección de
`distinctUntilChanged().drop(1)` sobre `drop(1).distinctUntilChanged()`
con un ejemplo concreto de por qué el orden importa.

### 2. Property tests

`GuestDnsTrackerPropertyTest`. 7 propiedades, 200 iteraciones cada
una. Total: **1300+ escenarios aleatorios** sobre el pipeline.

| Propiedad | Invariante |
|---|---|
| **P1** | `start()` dispara exactamente un refresh, sin importar el estado inicial. |
| **P2** | N cambios todos distintos → counter = 1 + N. |
| **P3** | Duplicados consecutivos se filtran; no consecutivos pasan. counter = 1 + número de runs. (Pilla el bug que tuvo la versión inicial del pipeline.) |
| **P4** | `stop()` previene refreshes posteriores, incluso si el observer sigue emitiendo. |
| **P5** | `start()` después de `stop()` re-aplica el sync inicial y reanuda el tracking. |
| **P6** | El pipeline es invariante a permutaciones que preserven la estructura de runs. |
| **P7** | Adversarial duplicates (100 cambios idénticos) colapsan a un único refresh; counter = 2. |

### 3. Snapshot builder

| Archivo | Rol |
|---|---|
| `core/runtime/network/GuestDnsSnapshotBuilder.kt` | `buildGuestDnsConfig(rawHostAddresses, domains)` puro JVM, sin imports de Android. |
| `core/runtime/network/AndroidGuestDnsObserver.kt` | Ahora delega al builder; menos código Android-only. |
| `test/.../GuestDnsSnapshotBuilderTest.kt` | 8 tests + 1 property. Cubre IPv6 zone suffix, dedupe, orden estable, split por whitespace, null/empty. |

### 4. Refactor del observer

| Archivo | Cambio |
|---|---|
| `InMemoryGuestDnsObserver` | Sustituido `MutableSharedFlow` por `MutableStateFlow` (conflated, no necesita `yield()` entre emissions en tests). `current()` re-snapshottea siempre. |
| `AndroidGuestDnsObserver` | `current()` re-snapshottea siempre (vía `snapshot()` directo), no devuelve `lastPublished`. Esto alinea con el `AndroidGuestDnsConfigProvider` original que el launcher ya esperaba. |

## Bug real descubierto por los tests

**El de mayor impacto**: `current()` devolvía cache. Si el test cambiaba
el `config.current` y llamaba `launcher.refreshDnsForRootfs` sin
`signalChange`, el launcher leía el valor cacheado viejo.

Síntoma: `NativeProotLauncherDnsRefreshTest.refresh writes the
latest snapshot to the bind-mounted resolv-conf` falló en el primer
re-run con "expected '198.51.100.7' but file still had '192.0.2.1'".

**Fixed**: `current()` ahora siempre llama `snapshot()`. La caché
(`lastPublished` / `state.value`) queda solo para el replay del
flow. Esto es coherente con el `AndroidGuestDnsConfigProvider`
original que ya hacía `current()` síncrono contra
`ConnectivityManager.getLinkProperties`.

**El segundo bug, aún más bonito**: las property tests originales
tenían la **invariante mal**. Decían "counter == 1 + distinctCount"
donde `distinctCount = changes.distinct().size`. Pero
`distinctUntilChanged` no es "filtro de set global" — es "filtro
de duplicado **consecutivo**". Una secuencia `[A, B, A]` tiene
`distinct() = 2` pero dispara 2 cambios, no 1. El test
esperaba 3 (1 + 2), la realidad era 4 (1 + 2 + 1 extra). Lo
corregí a `countRuns` (cuenta cambios de valor en la secuencia),
que sí es la métrica que `distinctUntilChanged` computa.

Este es exactamente el tipo de bug que el master order §33.2 quiere
que las property tests pillen. Y lo pillaron.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 946 | **962** (+16) |
| Tests fallando | 0 | 0 |
| ADRs | 2 | **3** |
| Property-test properties | 0 | **7** |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §10.1 DNS dinámico | completo | completo |
| §33.2 Property tests (DNS) | ❌ | ✅ (7 properties, 1300+ escenarios) |
| §33.4 Protocol decoders (DNS) | parcial | ✅ (snapshot builder + 8 tests) |
| §36 ADR-016 | ❌ | ✅ |

## Próximo paso

1. Phase 11.3 — `Application.onCreate` (o Hilt entry point) llama
   `tracker.start()`. Un `BroadcastReceiver` para
   `ConnectivityManager.CONNECTIVITY_ACTION` lo para en background
   y lo reanuda en foreground.
2. Phase 11.4 — `DistroManager` registra el rootfs en el registry
   cuando una sesión se lanza y lo desregistra cuando la última
   sesión del rootfs muere. Reusa `TerminalSessionManager` para
   saber cuándo un rootfs ya no tiene sesiones vivas.
3. Phase 12 — empezar con ADR-001 (runtime backend abstraction)
   que es el §36 más foundational pendiente.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 11.3 — wire `tracker.start()` into
`Application.onCreate`.
