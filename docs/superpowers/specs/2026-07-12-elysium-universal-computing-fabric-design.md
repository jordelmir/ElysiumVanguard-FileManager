# Elysium Vanguard — Universal Computing Fabric for Android

**Estado:** aprobado por el propietario del proyecto  
**Fecha:** 2026-07-12  
**Rama de ejecución:** `codex/elysium-universal-runtime`  
**Orden rectora SHA-256:** `34dbd1f87e267b4db3a24de8395681ac4c659f9b879161f3d62c141af392e486`

## 1. Autoridad y alcance

Esta especificación registra la orden maestra aprobada para transformar el
proyecto existente en **Elysium Vanguard — Universal Computing Fabric for
Android**. La ejecución cubrirá, en incrementos verificables, Android, Linux
ARM64, escritorios Linux, servicios headless, aplicaciones Linux x86-64 cuando
sean viables, WinLayer, virtualización, cargas remotas y brokers seguros para
archivos, red, audio, entrada, portapapeles y hardware.

La orden se ejecutará desde la Fase 0 hasta la Fase 13. No se sustituirá por una
reescritura especulativa, una demostración visual o una integración que dependa
de aplicaciones externas. Cada capacidad deberá existir realmente, exponer sus
límites y aportar evidencia ejecutable.

La prioridad de verdad es:

1. código ejecutable;
2. pruebas automatizadas;
3. configuración de compilación;
4. manifiestos y permisos;
5. JNI, NDK y Rust;
6. CI/CD;
7. ADR vigentes;
8. documentación;
9. comentarios;
10. conversaciones históricas.

## 2. Baseline confirmado

El baseline del 2026-07-12 es:

- proyecto Android de un único módulo Gradle, `:app`;
- Kotlin 1.9.20, AGP 8.2.0, Gradle Wrapper 8.10.2 y Java 17;
- `assembleDebug` exitoso;
- 840 pruebas unitarias exitosas, 2 omitidas y 0 fallos;
- `lintDebug` bloqueado por 1 error y 64 advertencias;
- error principal de lint: uso de `QUERY_ALL_PACKAGES`;
- APK debug existente de aproximadamente 96 MB;
- cuatro rootfs ARM64 y PRoot empaquetado ya fueron validados previamente en
  el Honor Magic V2;
- `TerminalSession` usa `ProcessBuilder` con pipes de stdin, stdout y stderr;
- `PtyFactory` devuelve un `PipePty`; no existe PTY real;
- el resize actual limpia una cuadrícula de dimensiones inmutables y no cambia
  el tamaño real del buffer;
- el parser terminal actual es parcial y recibe `String` en lugar de bytes
  UTF-8 incrementales;
- el renderer repinta la cuadrícula completa y centra el grid;
- el supuesto escritorio Linux genera un bitmap VNC simulado;
- existen servidores cuyo valor por defecto es `0.0.0.0`;
- existen `runBlocking`, aserciones `!!`, catches genéricos y al menos un catch
  vacío;
- no hay NDK instalado bajo el SDK local;
- `rustup` requiere reparación antes de compilar Clippy y targets Android;
- ADB no tenía un dispositivo conectado durante el último baseline.

Estos defectos no se ocultarán mediante baselines de lint, mensajes cosméticos
o estados falsos. Se corregirán por prioridad y con pruebas de regresión.

## 3. Principios no negociables

- No modificar ni hacer push directo a `main`.
- Preservar el trabajo funcional existente y migrarlo incrementalmente.
- No eliminar una función hasta demostrar que su reemplazo cubre su contrato.
- No mostrar capacidades simuladas como disponibles.
- No usar PRoot como sinónimo de VM o aislamiento de seguridad fuerte.
- No descargar ni redistribuir Windows.
- No concatenar entrada del usuario en comandos de shell.
- No abrir puertos en todas las interfaces sin consentimiento explícito.
- No montar indiscriminadamente el almacenamiento Android.
- No guardar secretos en texto plano, logs, rootfs o preferencias sin cifrar.
- No avanzar a un escritorio gráfico hasta que PTY, terminal, DNS y lifecycle
  sean correctos.
- Cada cambio deberá compilar, probarse, documentarse y quedar en un commit
  atómico.

## 4. Arquitectura objetivo

```text
Elysium Vanguard Shell
Compose UI · Launcher · Workspaces · Settings · Diagnostics · AI
                              │
Universal Application Layer
Android · Linux · Windows compatible · VM · Remote
                              │
Runtime Orchestrator
Sessions · Capabilities · Policies · Recovery · Resources
                              │
Runtime Backends
PRoot Linux · Linux VM · WinLayer · Windows VM · Remote
                              │
Platform Bridges
Display · PTY · Files · Audio · Input · USB · Network · Clipboard
                              │
Android Framework · App Sandbox · Android Kernel
```

El dominio no dependerá de Android, Compose, JNI, PRoot, Wine ni un backend
concreto. La frontera principal será `RuntimeBackend`, acompañada por contratos
tipados para sesión, capabilities, display, filesystem, red y diagnóstico.

La modularización final seguirá la familia `:core:*`, `:feature:*` y
`:native:*` descrita en la orden. La migración será progresiva: primero se
crearán fronteras comprobables dentro de `:app`; un paquete solo se extraerá a
un módulo cuando su API y sus pruebas sean estables.

## 5. Programa de ejecución

Las fases y puertas de avance son:

| Fase | Entrega | Puerta obligatoria |
|---|---|---|
| 0 | Auditoría y estabilización | baseline reproducible, ADR-001/002, lint y riesgos inventariados |
| 1 | Terminal real | PTY, parser, buffer, renderer, resize, Unicode, `vim`, `htop`, `tmux` |
| 2 | Runtime Linux Direct | PRoot, state machine, DNS dinámico, cleanup, servicio y diagnóstico |
| 3 | Elysium Vanguard Linux | rootfs reproducible, identidad, firma, SBOM, update y rollback |
| 4 | Display mínimo | X11 embebido, Surface, Openbox, entrada, clipboard y audio básico |
| 5 | Desktop productivo | XFCE/LXQt validado, monitor externo, recuperación y tabs |
| 6 | Filesystem universal | backends, SAF sync, journaling, conflictos y drag-and-drop |
| 7 | Application Capsules | manifiestos, shortcuts, permisos y modo seamless inicial |
| 8 | Hardware Fabric | protocolo y brokers USB, Bluetooth, serial, audio, sensores y OBD/CAN |
| 9 | WinLayer | Wine, Box64, prefixes aislados, renderers y registro de compatibilidad |
| 10 | Virtualización | capability probe, AVF, VM lifecycle y fallbacks legales |
| 11 | Workspaces | definición reproducible, DAG, health checks, puertos y restore |
| 12 | AI Operator | herramientas tipadas, diff, confirmación, snapshot y auditoría |
| 13 | Ecosistema | repositorio, canales, marketplace, firma y documentación |

No se ejecutarán fases posteriores para esconder una puerta incumplida. Una
fase puede preparar interfaces futuras, pero no declarar la capacidad como
funcional.

## 6. Primer incremento vertical: terminal real

La primera implementación funcional seguirá exactamente este flujo:

```text
Compose TerminalView
        ↓
SessionManager
        ↓
Native PTY
        ↓
PRoot + Debian/Elysium shell
        ↓
Incremental VT parser
        ↓
Screen buffer + dirty regions
        ↓
Glyph renderer sobre Android surface
```

### 6.1 Dominio y lifecycle

La sesión utilizará una máquina de estados validada:

```text
Created → Validating → Preparing → Starting → Running
Running → Suspending → Suspended → Recovering → Running
Running/Suspended/Failed → Stopping → Stopped
Any active state → Failed
```

Cada sesión registrará `SessionId`, `RuntimeId`, `DistroId`, PID, process group,
PTY master FD, directorio, entorno, tamaño, timestamps, estado, salida, sockets,
puertos, temporales y capability profile.

`stop` será idempotente y ejecutará: bloquear operaciones nuevas, cerrar input,
señal controlada, timeout, terminar el process group, cerrar FDs y sockets,
desmontar bridges, limpiar temporales, comprobar huérfanos y persistir un
`ExitReport`.

### 6.2 Núcleo nativo

Rust será la implementación preferida para ownership, event loop, errores y
estado nativo. C o C++ se limitará a APIs o bibliotecas que lo requieran y a una
frontera post-`fork` mínima cuando resulte necesaria para respetar operaciones
async-signal-safe.

El PTY deberá usar `/dev/ptmx` o `posix_openpt`, `grantpt`, `unlockpt`,
`ptsname`, `setsid`, terminal de control, `dup2`, `execve`, process group,
`TIOCSWINSZ`, `SIGWINCH`, FDs non-blocking y `epoll`.

JNI expondrá handles opacos y una API pequeña y versionada. No conservará
referencias a `Activity`, no bloqueará el main thread, validará longitudes y no
permitirá que un panic cruce la frontera.

### 6.3 Parser, buffer y renderer

El parser consumirá bytes incrementales y mantendrá estado entre fragmentos.
Cubrirá VT100/VT220 relevante, CSI, OSC, SGR 16/256/truecolor, cursor, alternate
buffer, scroll regions, tabs, erase, insert/delete, bracketed paste, UTF-8,
combining marks, caracteres anchos, emoji soportado, OSC 8, título, clipboard
bajo política, resize y scrollback acotado.

El buffer tendrá dimensiones mutables, reflow definido, main/alternate screen,
scrollback limitado y snapshot coherente. El renderer invalidará regiones
modificadas por frame; no habrá recomposición Compose por byte, carácter o
celda. El cursor pertenecerá a la misma geometría del grid.

### 6.4 Input

IME, teclado físico, ratón y touch se traducirán mediante una única capa de
input. Se soportarán modificadores, teclas de función, navigation keys,
bracketed paste y mouse reporting cuando el proceso lo active. Enter y
backspace respetarán la disciplina del PTY, no reglas especiales de pipes.

## 7. Runtime Linux, DNS y proceso

El backend PRoot recibirá argv y environment estructurados. El binario seguirá
empaquetado en una ubicación ejecutable permitida por Android; los rootfs y
artefactos se verificarán antes de arrancar.

`ConnectivityManager` y `LinkProperties` producirán DNS dinámico. Cada cambio
de red, Wi-Fi, datos, VPN o DNS privado regenerará `resolv.conf` mediante
staging, flush, rename atómico, validación y rollback. No se hardcodearán DNS
públicos como solución predeterminada.

Un foreground service poseerá las sesiones activas, reflejará su estado real y
permitirá recuperar la UI tras rotación, fold/unfold, recreación de Activity o
process death dentro de los límites de Android.

## 8. Modelo de errores y diagnóstico

Los errores serán tipos estables, no `null` ni cadenas sueltas. Cada error
incluirá código, causa, evidencia saneada, recoverability y acción sugerida.
Como mínimo existirán errores para rootfs, arquitectura, PTY, spawn, DNS,
display, capabilities, permisos, storage, page size, renderer, Wine, VM,
timeout y salida inesperada.

La UI mostrará código, evidencia y acciones como reparar, ver cambios, copiar
reporte o abrir terminal. Nunca mostrará únicamente un stack trace o un mensaje
genérico.

## 9. Seguridad y licencias

La Fase 0 producirá `docs/security/THREAT_MODEL.md`,
`docs/legal/THIRD_PARTY_COMPONENTS.md` y
`docs/legal/LICENSE_COMPATIBILITY_MATRIX.md`.

Cada dependencia nativa tendrá versión fijada, hash, licencia, origen y
obligaciones. La extracción de rootfs se hará en staging con límites de bytes y
archivos, canonicalización, bloqueo de traversal, symlinks, hardlinks, device
files y bombas de descompresión.

Los componentes Android serán `exported=false` salvo el launcher. Los sockets
serán privados, los puertos necesitarán política explícita y los brokers usarán
capability tokens con alcance y expiración.

## 10. UX y adaptación

La home expondrá aplicaciones, workspaces, distribuciones, servicios, archivos,
compatibilidad, diagnóstico y ajustes. Una distro mostrará estado, CPU, RAM,
disco, sesiones, renderer, red y uptime, además de acciones reales para
desktop, terminal, archivos, servicios, suspend, stop y diagnóstico.

Las pantallas se adaptarán a teléfonos pequeños, teléfonos medianos, Honor
Magic V2, tablets, landscape, split-screen, monitor externo y desktop mode.
Los grids derivarán su número de columnas del ancho disponible y cada
componente tendrá constraints propios; no se usarán dimensiones rígidas que
generen bloques superpuestos.

Donde el diseño requiera negro, se usará negro puro `#000000`. Las superficies
de color tendrán relleno uniforme; no contendrán rectángulos negros internos
accidentales. Los acentos podrán ser neón o fosforescentes, con contraste,
estado de foco y reducción de motion/glow cuando accesibilidad lo solicite.

El escritorio VNC simulado no se presentará como real. Mientras no exista un
backend gráfico validado, la UI mostrará la capability como no disponible y
ofrecerá la terminal real.

## 11. Display y escritorio

Después de las puertas de terminal, DNS y lifecycle se integrará un display X11
embebido sobre un socket Unix privado y una Surface Android. Openbox será el
primer window manager de aceptación; XFCE/LXQt llegará en la fase productiva.

Display Service administrará resolución, DPI, escala, orientación, refresh,
frame pacing, cursor, Surface lifecycle y monitor externo sin depender de una
Activity. Touch, trackpad, teclado, ratón y stylus compartirán políticas de
entrada explícitas.

## 12. Verificación

La estrategia incluye pruebas unitarias, property-based, fuzzing, integración,
instrumentación y E2E. La prueba decisiva del parser fragmentará cada secuencia
ANSI válida en todas las posiciones posibles y exigirá el mismo estado final
que la secuencia recibida en un único bloque.

La primera aceptación física deberá ejecutar:

```sh
uname -a
cat /etc/os-release
printf '\033[31mROJO\033[0m\n'
stty size
vim
htop
tmux
apt update
```

También deberá verificar resize, rotación, fold/unfold, cambio de red, DNS,
salida masiva, cierre abrupto y stop. Al finalizar habrá cero process groups,
zombis, sockets, FDs y temporales huérfanos.

El build y las pruebas JVM no sustituyen la instalación física. Cada incremento
Android que cambie comportamiento se compilará, instalará mediante ADB, abrirá
con `am start -W`, comprobará con `dumpsys`/`pidof` y revisará en logcat.

## 13. Presupuestos iniciales

- input terminal p95 menor a 20 ms;
- al menos 10 MB/s de salida sin recomposición por byte;
- cero ANR bajo salida masiva;
- shell warm start menor a 1 s en gama alta;
- cold start con rootfs preparado menor a 3 s como objetivo medido;
- desktop a 60 FPS cuando carga y temperatura lo permitan;
- input-to-display desktop p95 menor a 60 ms;
- I/O de archivos siempre streaming, cancelable e incremental.

Los presupuestos se reportarán por tier y solo se declararán cumplidos con
benchmarks.

## 14. Estrategia de commits y rollback

El WIP heredado se clasificará y conservará antes de la implementación nueva.
Cada commit resolverá una unidad coherente, compilará y pasará las pruebas
relacionadas. Refactors, funcionalidad, tests y documentación se separarán
cuando puedan revisarse de forma independiente.

Los cambios nativos se mantendrán detrás de una capability flag hasta superar
la aceptación física. El backend anterior permanecerá únicamente como fallback
marcado y no como PTY. Si una migración falla, se revertirá el commit atómico o
se desactivará el backend sin alterar rootfs ni datos de usuario.

## 15. Definition of Done

Una tarea solo estará terminada con implementación real, build, pruebas,
errores, cancelación, lifecycle, seguridad, observabilidad, documentación,
commit y evidencia de ejecución.

El MVP Linux requiere: arranque interno, DNS tras cambios de red, terminal real
con `vim`/`htop`/`tmux`, render correcto, Openbox/XFCE dentro de la APK, input,
clipboard, transferencia segura, supervivencia a rotación, foreground service
fiel, cleanup completo, rootfs verificado y diagnósticos accionables.

El WinLayer requiere prefixes aislados, detección de arquitectura, fallback de
renderer, registro de compatibilidad, contención de fallos y cleanup completo.
Windows VM seguirá siendo opcional, condicionado a capability real y a una
imagen legal suministrada por el usuario.

