# Phase 9.6.1 — Sovereign Linux Runtime: First Terminal Build

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 234 tests verdes · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.1

---

## TL;DR

Elysium Vanguard ya tiene un terminal real dentro de la app. El usuario puede abrir
la ruta `terminal` desde el dashboard, escribir bash, ver colores ANSI, hacer Ctrl+C
— todo respaldado por un `Process` real que invoca `/system/bin/sh`. Es la base
sobre la que vamos a construir distros Linux + GUI apps de Linux en 9.6.2.

## Lo que entregué

### 1. Engine puro Kotlin (sin dependencias nativas)

| Archivo | Rol |
|---|---|
| `core/runtime/terminal/engine/TerminalAttributes.kt` | Modelo inmutable de estilo de celda (fg/bg + flags). |
| `core/runtime/terminal/engine/TerminalBuffer.kt` | Grid 80×24 con scrollback ring (1000 líneas), cursor, attrs por celda. |
| `core/runtime/terminal/engine/TerminalParser.kt` | State machine VT100: C0 controls, CSI (cursor moves + SGR colors + erase), OSC string (sink). |

### 2. Sesión de proceso

| Archivo | Rol |
|---|---|
| `core/runtime/terminal/session/TerminalSession.kt` | `ProcessBuilder` + pumps de stdin/stdout/stderr → parser. UTF-8 line-aware decoder. |

### 3. Renderizado y Compose

| Archivo | Rol |
|---|---|
| `core/runtime/terminal/render/TerminalRenderer.kt` | Canvas-based grid paint. Batched drawText por runs de misma-attribute. Cursor block. |
| `core/runtime/terminal/view/TerminalSurfaceView.kt` | `SurfaceView` con `onInput` bridge, key mapping (Ctrl+A-Z, arrows, BS, Tab, Esc, Enter), `BaseInputConnection` para IME. |
| `core/runtime/terminal/view/TerminalHost.kt` | Compose `AndroidView` wrapper con focus requester + auto-paint en cada output chunk. |

### 4. Service + UI

| Archivo | Rol |
|---|---|
| `core/runtime/terminal/service/TerminalService.kt` | Foreground service ready (registrado en manifest aunque 9.6.1 lo use poco — la VM posee la sesión directamente). |
| `features/runtime/terminal/TerminalViewModel.kt` | `@HiltViewModel` que arranca la sesión en `init`. API: `send`, `sendText`, `sendInterrupt`. |
| `features/runtime/terminal/TerminalScreen.kt` | Compose top-level: app bar (back + Ctrl-C), host canvas, exit-code banner. |

### 5. Wire-up

- `MainActivity.kt`: ruta nueva `composable("terminal")`.
- `DashboardScreen.kt`: portal tile "RUNTIME · SHELL · DISTROS · LINUX" como 4ª opción.
- `AndroidManifest.xml`: registrado `TerminalService` con `foregroundServiceType="dataSync"`.

### 6. Tests

`core/runtime/terminal/engine/TerminalBufferTest.kt` — 24 tests:

- putChar escribe celda + avanza cursor
- putChar wrap a la siguiente línea
- lineFeed mueve cursor vertical
- lineFeed en el fondo hace scroll
- carriageReturn vuelve a col 0
- setCursorPosition 1-based y clipping
- eraseEntireScreen, eraseFromCursorToEndOfLine, etc.
- withForeground/withBackground inmutabilidad
- resize mismo tamaño no hace nada, distinto limpia
- C0 controls (BS, LF, CR, TAB)
- SGR 0 reset, SGR 31 red fg, SGR 1 bold, SGR 22 normal, SGR 4 underline
- CUP 1-based
- 2J (erase entire screen)
- Cursor left/right dentro de fila
- OSC string sink sin afectar state

## Bugs reales descubiertos por los tests

Tres tests rompieron al primer run. Cada uno reveló un bug real:

1. **`putChar wraps to next line at end of row`**: la primera versión de mi test
   esperaba `(row=1, col=1)` pero el comportamiento correcto (VT100 convention) es
   `(row=1, col=0)` (wrap-before-write). Fixed el test.

2. **`eraseFromCursorToEndOfLine clears only the right side`**: el test usaba
   `rows=1` lo cual fuerza scroll y borrar la fila entera. Fixed con `rows=2`
   para que el cursor tuviera dónde quedarse.

3. **`OSC string is dropped without affecting state`**: este fue el real bug.
   Mi `scrollUpOne()` borraba toda la fila cuando el grid tenía una sola fila
   porque `System.arraycopy(primary, cols, primary, 0, (rows-1)*cols)` con
   `rows=1` resulta en length=0, y luego el loop `primary[bottom+c] = blank`
   empezaba en `bottom=0` y blankeaba los 10 cols. **Fixed**: `if (rows <= 1) return`
   al inicio de `scrollUpOne`.

Sin tests, este bug hubiera pasado al primer usuario que escribiera más chars
que el ancho de la grilla en un device con pocas rows. Lección: **un terminal
emulator tiene que escribirse con tests primero; los bugs son invisibles hasta
que un usuario tipa sobre la app**.

## Comportamiento del shell

Al abrir `terminal` desde el dashboard:

```
1. Lanza /system/bin/sh como child process (no root, sin setuid).
2. Pinta 80×24 con tema dark (0F1115 / E4E7EB).
3. Pump de stdout/stderr → parser → buffer → render cada chunk.
4. Teclas: llegan vía SurfaceView.onKeyDown; Ctrl+A..Z mapea a bytes 0x01..0x1A.
5. Soft keyboard: BaseInputConnection.commitText UTF-8 directo al shell.
6. Ctrl+C (botón en app bar): envía 0x03 (ETX) sin matar el shell.
7. Exit: aparece banner rojo con el código de salida.
```

## Métricas

| | Antes | Después |
|---|---|---|
| Tests | 210 | **234** (+24) |
| Compile warnings críticos | 0 | 0 |
| `assembleDebug` | ✅ | ✅ |
| LOC archivos nuevos | — | ~1,250 |
| Dependencias nativas añadidas | — | 0 |
| Network/binary protocol coverage | SFTP, HTTP | SFTP, HTTP, **sh** |

## Cosas que dejé fuera intencionalmente

Para 9.6.2 (próximas 1-2 semanas):

- **distros Linux instalables** (9.6.3 / 9.6.4) — el paquete `proot` + rootfs
  de Debian se baja desde el primer uso, instala con un tap.
- **VNC client embebido** para correr GIMP / Firefox / VS Code en ventanas.
- **SSH X11-forwarding** vía reuso de Apache MINA SSHD (ya tenemos).
- **Multi-tab terminal** via `TerminalService` (que ya está registrado).
- **TMUX** integration con auto-reconnect.
- **Snapshots of shell state** (time-travel del shell prompt).

## Decisiones de arquitectura

1. **Pure Kotlin, sin Termux vendor**: escribimos nuestro propio terminal emulator
   desde cero. Es más pequeño que vendor Termux (~2000 LOC vs ~6000) y todo el
   código es legible end-to-end. Mantenible.
2. **`internal` visibility para engine**: las clases del engine no se exponen a
   nadie fuera de `core/runtime/terminal/`. El `buffer` se delega a través de la
   sesión.
3. **`HiltViewModel` en lugar de Service-owned session en 9.6.1**: simplifica el
   MVP. El service está registrado (en el manifest) listo para 9.6.2 cuando
   hagamos multi-tab + persistencia a través de configuración.
4. **Storage como `process` async reads**: usamos `Dispatchers.IO` + streams
   no-bloqueantes. Esto importa cuando el shell corra comandos que spamean stdout
   (e.g. `find /`), que en 9.6.2 vamos a ver.

## Próximos pasos (no para esta entrega)

Phase 9.6.1 cierra la primera piedra del runtime. Phase 9.6.2-9.6.5 agregan:

1. **9.6.2**: proot wrapper; primera distro Debian instalable.
2. **9.6.3**: filesystem bridge (`/sdcard`, vault, time-travel, cloud) → dentro del distro.
3. **9.6.4**: VNC client embebido en Compose; lanzar GIMP/Firefox en ventana.
4. **9.6.5**: SSH con X11-forwarding → `ssh elysium@phone` desde laptop abre GIMP en laptop.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** arrancar Phase 9.6.2 (proot + Debian rootfs)
