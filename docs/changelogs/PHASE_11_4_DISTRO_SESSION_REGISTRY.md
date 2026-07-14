# Phase 11.4 — DistroSessionRegistry + TerminalSessionManager wiring

**Fecha:** 2026-07-13
**Status:** ✅ Compila · ✅ 974 tests verdes (+7 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+11.4

---

## TL;DR

Cerramos el último gap del master order §10.1. Ahora hay un
puente entre "una sesión de terminal está viva en este rootfs" y
"el DNS refresh pipeline tiene a quién notificar":

`TerminalSessionManager` (que ya trackea el ciclo de vida de las
sesiones) cuenta las sesiones activas por rootfs y registra /
desregistra en `ActiveRootfsRegistry` según el contador. La
primera sesión en un rootfs lo registra; la última lo libera.

Si dos sesiones viven en el mismo rootfs, el rootfs se queda
registrado hasta que ambas mueren. Un cambio de red mientras
hay al menos una sesión activa reescribe el `resolv.conf`
bind-mount en menos de un tick del tracker.

## Lo que entregué

### 1. Bridge class

| Archivo | Rol |
|---|---|
| `core/runtime/network/DistroSessionRegistry.kt` | Hilt `@Singleton` con `onSessionStarted(rootfs)` / `onSessionStopped(rootfs)`. Encapsula `registry.register(rootfs, launcher::refreshDnsForRootfs)`. |

### 2. Registry overload simplificado

`ActiveRootfsRegistry.register(rootfs, refresher: (File) -> Unit)`
absorbe el viejo `() -> Unit` overload. Una sola firma, sin
ambigüedad para el call site, y permite `register(rootfs, launcher::refreshDnsForRootfs)`
sin boilerplate.

### 3. Hilt provider para el launcher

`NativeProotLauncher` ahora es un `@Singleton` provisto por
`DistroModule.provideNativeProotLauncher`, separado del
`provideLauncherRegistry`. La razón: el DNS pipeline y el
builder de comandos deben compartir la misma instancia, o dos
escritores compiten por el mismo `resolv.conf` en el mismo
`runtimeTmpDir`. Antes el launcher vivía solo dentro de
`DistroLauncherRegistry.production(...)` y no era inyectable.

### 4. Session manager con ref-counting

| Archivo | Cambio |
|---|---|
| `core/runtime/terminal/session/TerminalSession.kt` | `Config` gana `rootfsDir: File?`. `forDistro` y `forDistroScript` lo setean. |
| `core/runtime/terminal/session/TerminalSessionManager.kt` | `@Inject` de `DistroSessionRegistry`. `activeRootfsCounts: ConcurrentHashMap<File, AtomicInteger>`. `register(session)` incrementa y llama `onSessionStarted` cuando el contador llega a 1. `close(id)` decrementa y llama `onSessionStopped` cuando llega a 0. |

### 5. Tests

`DistroSessionRegistryTest` (7 tests) en JVM puro, end-to-end
hasta el byte escrito en el `resolv.conf`:

| Test | Verifica |
|---|---|
| `onSessionStarted makes the rootfs a refresh target` | El rootfs aparece en `registry.activeRootfses()`. |
| `onSessionStopped removes the rootfs from refresh targets` | El rootfs desaparece. |
| `network change while a session is active refreshes the bind mount` | El archivo cambia con la nueva red. |
| `network change after onSessionStopped does not refresh the file` | El archivo queda congelado. |
| `onSessionStopped is a no-op when the rootfs was never registered` | Sin excepción. |
| `re-registering the same rootfs replaces the previous closure` | Idempotente. |
| `end-to-end - register, network flip, unregister, network flip again` | El ciclo completo. |

## Por qué ref-counting y no "registrar al crear, desregistrar al cerrar"

Si dos sesiones viven en el mismo rootfs (e.g. un usuario abre
dos terminales en Debian), la primera registra, la segunda
"sustituye" la closure (idempotente), y al cerrar la primera
la desregistramos. Pero la segunda sigue viva y ahora no tiene
DNS refresh.

El ref-counting resuelve eso: cada `close` decrementa, y
solo desregistramos cuando el contador llega a 0. La única
incómodidad es un `ConcurrentHashMap<File, AtomicInteger>` y
dos helpers privados en el manager.

## Comportamiento en producción

1. Usuario instala Debian. `DistroManager.installBlocking`
   baja + extrae el rootfs. El rootfs vive en
   `filesDir/distros/debian/rootfs`.
2. Usuario abre un terminal en Debian. `DistroManager.launcherFor`
   devuelve el `LauncherPick` con `NativeProotLauncher`.
3. `TerminalViewModel` llama `TerminalSessionManager.create(...)`
   con un config que tiene `rootfsDir = <debian>`.
4. `TerminalSessionManager.register`:
   - `activeRootfsCounts[debian] = 1` (incrementa desde 0)
   - `distroSessionRegistry.onSessionStarted(debian)` →
     `registry.register(debian, launcher::refreshDnsForRootfs)`
   - `session.start()` lanza el proot
5. Usuario abre un segundo terminal en el mismo Debian.
   - `activeRootfsCounts[debian] = 2`
   - `onSessionStarted` no se llama (count > 1, no es "primera")
6. Usuario cambia de Wi-Fi a datos.
   - `AndroidGuestDnsObserver.NetworkCallback.onCapabilitiesChanged`
     dispara `publish()`.
   - `GuestDnsSessionTracker` recibe el cambio, llama
     `registry.refreshAll()`.
   - `refreshAll` itera todos los rootfses registrados
     (sólo `debian`), llama cada closure.
   - `launcher.refreshDnsForRootfs(debian)` reescribe el
     `resolv.conf` atómicamente. PRoot's bind mount refleja
     el cambio en el guest.
7. apt update dentro de la sesión usa los nuevos nameservers.
8. Usuario cierra el segundo terminal.
   - `activeRootfsCounts[debian] = 1` (decrementa, no llega a 0)
   - `onSessionStopped` no se llama
9. Usuario cierra el primer terminal.
   - `activeRootfsCounts[debian] = 0` (decrementa a 0)
   - `distroSessionRegistry.onSessionStopped(debian)` →
     `registry.unregister(debian)`
10. Si cambia la red otra vez, `refreshAll` no hace nada
    (registry vacío).

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 967 | **974** (+7) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |
| Cobertura del §10.1 | "tracker subscrito pero solo" | "tracker subscrito y con clientes" |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §10.1 reactivo, end-to-end | parcial (tracker sin clientes) | ✅ completo |
| §33.5 E2E #11-12 | parcial | ✅ end-to-end |
| §9 lifecycle (sessions) | sin ref-counting | con ref-counting |

## Próximo paso

1. **Phase 11.5** — Instrumented test del `AndroidGuestDnsObserver`
   con `ConnectivityManager` real (Robolectric o device).
2. **Phase 12** — Empezar a cerrar el §36 de ADRs pendientes
   (001, 002, 003, 004). El 016 ya está.
3. **Phase 13** — `Network broker` del master order §10.2:
   policies allow/deny por sesión, loopback-only, etc. El
   registry actual es "all or nothing"; el broker lo convierte
   en "este rootfs solo puede hablar con 8.8.8.8".

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 11.5 — instrumented test del
`AndroidGuestDnsObserver`.
