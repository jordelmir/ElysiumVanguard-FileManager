# Phase 11.1 — DNS Session Tracker

**Fecha:** 2026-07-13
**Status:** ✅ Compila · ✅ 946 tests verdes (+16 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+11.1

---

## TL;DR

Cerramos el bucle abierto en Phase 11. Ahora hay un `GuestDnsSessionTracker`
que se suscribe una sola vez al `GuestDnsObserver` y refresca el
`resolv.conf` de cada rootfs vivo en cada cambio de red. La integración
JVM cubre el Wi-Fi → data flip y el cambio de search domains de
private DNS hasta el byte exacto escrito en el archivo bind-mount.

## Lo que entregué

### 1. Registry

| Archivo | Rol |
|---|---|
| `core/runtime/network/ActiveRootfsRegistry.kt` | `Map<File, () -> Unit>` thread-safe. `register` reemplaza la closure del mismo rootfs. `refreshAll` itera con snapshot atómico, aísla fallos, devuelve la lista de rootfses que fallaron. |

`refreshAll` está pensado para que una distro corrupta no pueda
bloquear a las demás: la closure se llama en un bloque try/catch y
los fallos se acumulan en el resultado.

### 2. Tracker

| Archivo | Rol |
|---|---|
| `core/runtime/network/GuestDnsSessionTracker.kt` | `@Singleton`. Suscribe `observer.observe()` con `distinctUntilChanged().drop(1)`. Llama `registry.refreshAll()` en cada cambio real. `start()`/`stop()` idempotentes. Dispatcher inyectable para tests (`Dispatchers.Default` en prod). |

Pipeline:

```
observer.observe()
  ↓ distinctUntilChanged   (descarta re-emisiones idénticas)
  ↓ drop(1)                (descarta el replay inicial;
                            start() ya aplicó el snapshot)
  ↓ registry.refreshAll()  (refresh de cada rootfs vivo)
```

`start()` hace una sincronización inicial síncrona: llama
`registry.refreshAll()` una vez con el snapshot actual del observer.
Esto cubre el caso "la red cambió entre el `buildShellCommand` del
launcher y el `start()` del tracker".

### 3. Dependencia de test

`app/build.gradle.kts` ahora trae:

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

alineado con el `kotlinx-coroutines-core:1.7.3` que ya estaba en
runtime. Da `runTest`, `UnconfinedTestDispatcher` y `advanceUntilIdle`,
que son lo único que hace deterministas los tests del flow.

### 4. Tests

| Archivo | Cobertura |
|---|---|
| `ActiveRootfsRegistryTest` | 7 tests: add/remove, idempotente, replace de closure, refresh ejecuta cada una, aísla fallos, rechaza rootfs inválido, concurrencia (4 register + 4 refresh, barrier 2 fases). |
| `GuestDnsSessionTrackerTest` | 7 tests: `start` idempotente, `stop` idempotente, sync inicial, re-attach tras stop, cambio de contenido, duplicados filtrados, rootfs registrado tarde. |
| `DnsRefreshIntegrationTest` | 2 tests: Wi-Fi → data flip (archivo cambia) y cambio de private DNS search domain (mismo nameserver, distinto search). |

Total Phase 11.1: **+16 tests**, todos verdes.

## Por qué `drop(1)` después de `distinctUntilChanged`

El `replay=1` del `SharedFlow` significa que un suscriptor tardío
recibe el último valor publicado como si fuera una emisión normal.
Con el orden `drop(1) → distinctUntilChanged`, la primera emisión
post-drop se compara contra "nada" y siempre pasa, así que un
`signalChange()` con el mismo valor se cuela. Con el orden
`distinctUntilChanged → drop(1)`, la primera emisión post-replay se
compara contra el valor de replay y se filtra correctamente cuando
es idéntica.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 930 | **946** (+16) |
| Tests fallando | 0 | 0 |
| Warnings críticos nuevos | — | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §10.1 DNS dinámico reactivo | parcial (sin dispatcher) | completo (observer + tracker + registry) |
| §33.5 E2E #11 (cambia red) | parcial | ✅ |
| §33.5 E2E #12 (regenera DNS) | parcial | ✅ |

## Lo que dejé fuera intencionalmente

- **App-level hook**: el tracker no se arranca automáticamente.
  Hilt lo provee, pero ningún `Application.onCreate` lo invoca
  todavía. La integración real con el ciclo de vida de la app
  llega en Phase 11.3.
- **DistroManager ↔ registry**: el `DistroManager` aún no llama
  `registry.register` cuando lanza un rootfs. Eso es Phase 11.4.
- **Lifecycle awareness** (pausar cuando la app está background):
  no es del master order, va con un `LifecycleObserver` futuro.
- **Per-session overrides**: hoy todas las sesiones comparten el
  mismo DNS. El override por sesión (ApplicationCapsule) es
  master order §14 y va con Workspaces (Phase 22+).
- **ADR-016**: pendiente. Phase 11.2.

## Próximo paso

1. Phase 11.2 — ADR-016 "Network-aware DNS refresh" + test
   instrumentado de `AndroidGuestDnsObserver` con Robolectric o
   device real.
2. Phase 11.3 — `Application.onCreate` (o Hilt entry point) llama
   `tracker.start()`. Un `BroadcastReceiver` para
   `ConnectivityManager.CONNECTIVITY_ACTION` lo para en background
   y lo reanuda en foreground.
3. Phase 11.4 — `DistroManager` registra el rootfs en el registry
   cuando una sesión se lanza y lo desregistra cuando la última
   sesión del rootfs muere. Reusa `TerminalSessionManager` para
   saber cuándo un rootfs ya no tiene sesiones vivas.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 11.2 — ADR-016 + instrumented test.
