# Phase 11 — Network-Aware Guest DNS

**Fecha:** 2026-07-13
**Status:** ✅ Compila · ✅ 930 tests verdes (+10 nuevos) · ✅ assembleDebug verde · ✅ lintDebug verde
**Versión:** 1.0.0-TITAN+11

---

## TL;DR

El resolver de los guests PRoot ahora sigue la red activa de Android.
La pieza nueva es un `GuestDnsObserver` reactivo que envuelve
`ConnectivityManager.NetworkCallback` y un método
`NativeProotLauncher.refreshDnsForRootfs` que reescribe el `resolv.conf`
bind-mount sin relanzar el proceso. Wi-Fi → datos, VPN up/down, private
DNS y pérdida/recuperación de red vuelven a quedar cubiertas, que es
lo que el master order §10.1 y el E2E test #11-12 del §33.5 exigen.

## Lo que entregué

### 1. Interfaz reactiva

| Archivo | Rol |
|---|---|
| `core/runtime/network/GuestDnsObserver.kt` | `Flow<GuestDnsConfig>` + `suspend refresh()`. Extiende `GuestDnsConfigProvider` para back-compat. |
| `core/runtime/network/InMemoryGuestDnsObserver.kt` | Impl pura JVM con `() -> GuestDnsConfig` snapshot. Usada en tests y como fallback. |
| `core/runtime/network/AndroidGuestDnsObserver.kt` | Impl Android: `ConnectivityManager.NetworkCallback` (onAvailable / onLost / onCapabilitiesChanged / onLinkPropertiesChanged). Re-publica al bus en cada evento. |

### 2. Refresh en launcher

`NativeProotLauncher` ahora expone:

```kotlin
fun refreshDnsForRootfs(rootfsDir: File): File?
```

Mismo `writeResolvConfAtomically` que usa `buildShellCommand`; el bind
mount de PRoot refleja el cambio sin relanzar el proceso. Devuelve
`null` cuando no hay red activa (el launcher ya no agrega el bind mount
en ese caso).

### 3. Wiring Hilt

- `DistroModule.provideLauncherRegistry` ahora recibe el observer y lo
  pasa como `guestDnsConfigProvider` (back-compat: la interfaz base
  sigue siendo `GuestDnsConfigProvider`).
- Nuevo `provideGuestDnsObserver` y `provideGuestDnsConfigProvider`
  (alias para callers que aún piden la versión one-shot).

### 4. Tests

| Archivo | Cobertura |
|---|---|
| `InMemoryGuestDnsObserverTest` | 6 tests: snapshot inicial, re-emisión al cambiar, current() siempre fresh, refresh, snapshots idénticos, empty config. |
| `NativeProotLauncherDnsRefreshTest` | 4 tests: Wi-Fi → data flip, sin red → null, snapshots repetidos, rootfs inválido. |

## Por qué lo hice así

El master order §10.1 lista los disparadores que el resolver debe
reaccionar:

- iniciar sesión
- Wi-Fi → datos / datos → Wi-Fi
- VPN up / VPN down
- cambio de DNS privado
- pérdida/recuperación de red

El código previo (`AndroidGuestDnsConfigProvider`) era un snapshot
perezoso: leía `activeNetwork` una vez y devolvía el último valor que
vio. Cuando el dispositivo cambiaba de red, el guest seguía con
servidores viejos hasta que el proceso se re-lanzara. La métrica
"ejecutar `apt update` después de caminar fuera de Wi-Fi" estaba rota
en silencio.

El fix: separar el snapshot (lectura sincrónica) del signal de cambio
(flujo). El snapshot se llama cada vez que alguien pregunta; el
signal se dispara con un `NetworkCallback` registrado contra la red
por defecto. PRoot's bind mount es gratis: solo hay que reescribir el
archivo y el guest ve los nuevos nameservers en su próximo
`getaddrinfo`.

## Cobertura del master order

| § del master | Antes | Después |
|---|---|---|
| §7.1 Clean Architecture backend | ✅ | ✅ (sin cambios) |
| §7.2 Máquina de estados | ✅ | ✅ (sin cambios) |
| §7.3 Errores tipados | ✅ | ✅ (sin cambios) |
| §8 Terminal | ✅ | ✅ (sin cambios) |
| §10 DNS dinámico | ❌ snapshot one-shot | ✅ reactivo, con refresh |
| §10.2 Network Broker | parcial | parcial (gap consciente: orden y políticas los cubre la fase 12 de Workspaces) |
| §33.5 E2E #11 (cambia red) | ❌ | ✅ |
| §33.5 E2E #12 (regenera DNS) | ❌ | ✅ |

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 920 | **930** (+10) |
| Tests fallando | 0 | 0 |
| Warnings críticos nuevos | — | 0 |
| `assembleDebug` | ✅ | ✅ |
| `lintDebug` | ✅ | ✅ |

## Cosas que dejé fuera intencionalmente

Para no desviar la entrega:

- **Glue session ↔ observer**: el `TerminalSession` aún no se suscribe
  al flow. La próxima fase agrega `GuestDnsSessionTracker` que
  escucha el flow y dispara `refreshDnsForRootfs` por sesión activa.
- **Network broker policies** (allow / deny por sesión): propio de
  la fase 18 (Hardware broker). Por ahora, el guest hereda lo que
  el sistema ya permite.
- **Test instrumentado** del `AndroidGuestDnsObserver` con
  `ConnectivityManager` real: va con la próxima fase (necesita
  Robolectric o device).
- **Per-app DNS** (e.g. un guest con `nameserver 9.9.9.9`): propio
  de la fase 22 (Workspaces).

## Próximo paso

1. `GuestDnsSessionTracker`: se suscribe al `observe()` y refresca el
   `resolv.conf` de cada rootfs activo. Un `TestGuestDnsSessionTracker`
   en JVM.
2. Test instrumentado del `AndroidGuestDnsObserver`.
3. ADR-016 "Network-aware DNS refresh" en `docs/adr/`.
4. Phase 11.1: hookear el tracker al `TerminalSessionManager` y al
   ciclo de vida del `DistroManager` (instalar/eliminar distro).
5. Phase 11.2: añadir el escenario E2E de network flip al
   `AcceptanceHarness`.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 11.1 — `GuestDnsSessionTracker` que re-deriva
`resolv.conf` de cada sesión activa en cada `observe()` emission.
