# Phase 11.3 — App-lifecycle hook for the DNS tracker

**Fecha:** 2026-07-13
**Status:** ✅ Compila · ✅ 967 tests verdes (+5 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+11.3

---

## TL;DR

El `GuestDnsSessionTracker` ya no espera a que alguien lo llame
manualmente. Lo ata un `DefaultLifecycleObserver` al
`ProcessLifecycleOwner` y arranca en `ON_START`, se detiene en
`ON_STOP`. Resultado: el guest sigue la red **mientras la app
está visible**, sin malgastar un `NetworkCallback` mientras el
usuario está en otra app.

## Lo que entregué

### 1. Binder

| Archivo | Rol |
|---|---|
| `core/runtime/network/GuestDnsLifecycleBinder.kt` | `DefaultLifecycleObserver`. `onStart → tracker.start()`, `onStop → tracker.stop()`. Idempotente (el tracker's start/stop ya lo son). |

### 2. App hook

`TitanApp.kt` ahora inyecta el binder vía Hilt y lo registra en
`ProcessLifecycleOwner.get().lifecycle` durante `onCreate()`.
Una sola línea:

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(guestDnsLifecycleBinder)
```

### 3. Dep

`app/build.gradle.kts` añade `androidx.lifecycle:lifecycle-process:2.6.2`
alineado con el resto del stack de lifecycle. Aporta
`ProcessLifecycleOwner`.

### 4. Tests

`GuestDnsLifecycleBinderTest` (5 tests) sobre un `LifecycleRegistry`
hecho a mano. JVM puro, sin Robolectric.

| Test | Verifica |
|---|---|
| `ON_START starts the tracker` | El binder arranca el tracker en la transición correcta (después de `ON_CREATE`). |
| `ON_STOP stops the tracker` | El binder detiene el tracker en `ON_STOP`. |
| `foreground-to-background-to-foreground cycle is clean` | Foreground refresca, background no, re-foreground re-sincroniza. |
| `binder does not double-register or leak observers` | `addObserver` doble no produce un start doble. |
| `binder survives multiple start events without extra refreshes` | 3 `ON_START` consecutivos pagan solo 1 initial sync. |

El setup usa `ArchTaskExecutor` con un delegate no-op que
pretende ser main-thread y corre los `postToMainThread` inline
— sin esto, `LifecycleRegistry` lanza NPE porque la JVM de
tests no tiene `Looper.getMainLooper()`.

## Por qué `ProcessLifecycleOwner` y no `ActivityLifecycleCallbacks`

- `ProcessLifecycleOwner` agrega eventos a través de toda la
  app: `ON_START` cuando la **primera** activity se vuelve
  visible, `ON_STOP` cuando la **última** se oculta. No nos
  importa qué activity específica está al frente.
- `ActivityLifecycleCallbacks` haría lo mismo con más boilerplate
  y un hook por activity. Peor para probar.
- `ProcessLifecycleOwner` también throttle: si la app entra y
  sale del foreground en menos de 700ms, no emite eventos. Eso
  es justo lo que queremos — no queremos un refresh por un
  switch rápido de activity.

## Comportamiento en producción

1. Usuario abre la app. Primera activity se vuelve visible.
   `ON_START` → `tracker.start()` → initial sync → el guest
   tiene el resolver actual.
2. Usuario minimiza la app. Última activity se oculta.
   `ON_STOP` → `tracker.stop()` → el `NetworkCallback` se libera.
3. Usuario vuelve. `ON_START` → `tracker.start()` → initial sync
   otra vez (cubre el caso "red cambió mientras estábamos en
   background").
4. Cambia de Wi-Fi a datos dentro de la app: `ON_START` ya
   está fired, el tracker está subscrito, el flow emite, el
   registry refresca, el launcher reescribe el `resolv.conf`.

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 962 | **967** (+5) |
| Tests fallando | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |
| `NetworkCallback` activos en background | 1 permanente | 0 |

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §10.1 reactivo | completo | completo + lifecycle-aware |
| §31 presupuestos de rendimiento | parcial | "NetworkCallback solo en foreground" agregado al budget |
| §30 observabilidad (lifecycle) | sin observabilidad de lifecycle | `isRunning()` disponible para diagnósticos |

## Próximo paso

1. Phase 11.4 — `DistroManager` registra el rootfs en el registry
   cuando una sesión se lanza y lo desregistra cuando la última
   sesión del rootfs muere. Hasta que esto se haga, el tracker
   está subscrito al observer pero no tiene nadie a quien
   notificar — los refrescos son no-ops.
2. Phase 12 — `WorkManager`/`ProcessLifecycleOwner` integration
   audit: el resto de los singletons (network broker, hardware
   broker, audio broker) que viven como foreground services
   deben tener el mismo tratamiento.
3. ADR-016 ya está escrito. Pendientes: ADR-001, 002, 003, 004, 005.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 11.4 — `DistroManager` ↔ `ActiveRootfsRegistry`.
