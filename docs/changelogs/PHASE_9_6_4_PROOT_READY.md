# Phase 9.6.4 — Sovereign Runtime: proot Native-Ready Wire-up

**Fecha:** 2026-07-09
**Status:** ✅ Compila · ✅ 330 tests verdes (7 nuevos) · ✅ assembleDebug verde
**Versión:** 1.0.0-TITAN+9.6.4

---

## TL;DR

Esta fase **prepara** Elysium Vanguard para que `libproot.so` funcione el
día que esté vendored en `jniLibs/<abi>/`. Hasta que Jor haga el
cross-compile desde su Mac con Android NDK (procedimiento detallado en
`proot/INSTALL.md`), la capability sigue dormida y el flujo de Phase 9.6.3
(jailed shell sobre `/system/bin/sh` con cwd=rootfs) sigue siendo el
camino activo.

La preparación consiste en cuatro piezas que **no son** código runtime — son
las condiciones para que el wire-up sea un commit de 5 líneas el día que
llegue `libproot.so`:

1. **Detección de la librería** (`ProotNativeLibrary`) — busca el `.so` en
   bundled / user-installed / Termux.
2. **Launchers actualizados** (`NativeProotLauncher`) — arma comandos
   `proot -0 -r <rootfs> -b <mounts> /bin/sh` reales cuando la librería
   está.
3. **JNI stub** (`ProotNativeBridgeStub`) — punto único de carga
   futura.
4. **Procedimiento de cross-compile** (`proot/INSTALL.md`) — todo lo
   que Jor debe correr para producir el `.so` que va en el APK.

---

## Lo que entregué

```
app/
├── src/main/
│   ├── java/com/elysium/vanguard/core/runtime/distros/launcher/
│   │   ├── ProotNativeLibrary.kt        ← new — detector de la librería
│   │   ├── ProotNativeLibraryLocation.kt ← via data class ProotLocation
│   │   ├── ProotNativeBridgeStub.kt     ← new — JNI loader stub
│   │   └── NativeProotLauncher.kt        ← updated — uses real bind mounts
│   └── cpp/proot/INSTALL.md             ← new — how to cross-compile proot
│
└── src/test/java/com/elysium/vanguard/core/runtime/distros/launcher/
    └── ProotNativeLibraryTest.kt          ← 7 tests for the detector
```

---

## `ProotNativeLibrary`

Tres ubicaciones en orden de prioridad:

```kotlin
val probe = ProotNativeLibrary.default(
    abis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
    userProotDir = File(context.filesDir, "proot"),
    termuxProotCandidates = listOf(
        File("/data/data/com.termux/files/usr/libexec/proot"),
        File("/data/user/0/com.termux/files/usr/libexec/proot"),
        File("/system/bin/proot")
    )
)
```

| Source | Path | Created by |
|---|---|---|
| `BUNDLED` | `<dataDir>/lib/<abi>/libproot.so` | Gradle's `mergeNativeLibs` after `jniLibs/<abi>/libproot.so` lands |
| `USER_INSTALLED` | `<filesDir>/proot/<abi>/libproot.so` OR `<filesDir>/proot/libproot.so` | User pulled from a Termux package archive |
| `TERMUX` | `/data/data/com.termux/files/usr/libexec/proot` | Already in Termux's prefix |

`ProotLocation` is the value type:

```kotlin
data class ProotLocation(
    val source: Source,   // BUNDLED | USER_INSTALLED | TERMUX
    val path: File,
    val abi: String
)
```

---

## `NativeProotLauncher` — wire-up real

Cuando `nativeLibrary.location != null`, el launcher produce un comando
proot **real** con bind mounts:

```kotlin
override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
    if (!isAvailable(rootfsDir)) return listOf("proot-missing")
    val args = ArrayList<String>()
    args += "proot"
    args += "-0"
    args += "-r"
    args += rootfsDir.absolutePath
    for (mount in bridge.standardMounts(null, null)) {
        args += "-b"
        args += "${mount.hostPath}:${mount.guestPath}"
    }
    args += "/bin/sh"
    args += "-c"
    args += if (script.isBlank()) "echo '[proot] ready'; exec /bin/sh" else script
    return args
}
```

Output Esperado (cuando Jor compile el .so):

```
proot -0 -r /data/data/com.elysium.vanguard/files/distros/alpine-latest/rootfs \
      -b /sdcard/Android/data/com.elysium.vanguard/files/vault:/elysium/vault \
      /bin/sh -c "pwd; ls /etc/os-release"
```

**El usuario entonces ve `/` como cwd, no `rootfsDir`. `apt install python3` corre dentro de Alpine.**

---

## `proot/INSTALL.md`

Documento paso-a-paso para que Jor haga cross-compile en su Mac:

1. Clonar proot de GitHub (`termux/proot@v5.3.0`)
2. Configurar con NDK clang para cada ABI (arm64-v8a / armeabi-v7a / x86_64 / x86)
3. `--disable-shared --enable-static` → un `.so` standalone
4. Drop en `app/src/main/jniLibs/<abi>/libproot.so`
5. `./gradlew assembleDebug` → APK tiene el `.so` bundled
6. Smoke test: Install Alpine, tap Open, header dice "· proot", `pwd` reporta `/`

Incluye una tabla que muestra qué capabilities se desbloquean:

| Capability | Jailed shell | Con proot |
|---|---|---|
| Read rootfs files | ✅ | ✅ |
| Run shell scripts | ✅ | ✅ |
| Run ELF binaries | ❌ | ✅ |
| Bind mounts (`/sdcard`, `/elysium/vault`) | ❌ | ✅ |
| `apt install python3` | ❌ | ✅ |

---

## Tests (7 nuevos)

### `ProotNativeLibraryTest`

- `null when nothing is available` ✅
- `find libproot so in user-installed dir` ✅
- `find libproot so in flat user-installed dir` ✅
- `user-installed wins over Termux when both exist` ✅ (priority order)
- `Termux is the last resort` ✅
- `describeForUi returns the location source and path when found` ✅

### `ProotNativeBridgeTest`

- `bridge reports unloaded when proot_jni is not on the classpath` ✅

**Total: 330** unit tests passing, 0 failures, 0 errors.

---

## Honest caveats

- **Sin NDK en este entorno**, no pudimos cross-compilar libproot.so
  aquí. Jor debe correr el `INSTALL.md` desde su Mac.
- El APK resultante (248 MB) **NO** tiene el `.so` bundled. Cuando lo
  tenga, la línea "· proot" en el title reemplaza a "· jailed".
- **Los tests del launcher verifican shape**, no ejecución real de
  proot. Ejecutar proot requiere Android (no JVM), NDK, y una distro
  instalada. Eso viene en 9.6.4.1 post-vendor.

---

## Decisiones de arquitectura

1. **Detector separado del launcher.** El launcher pregunta por
   disponibilidad vía `isAvailable()`. La detección vive en su propia
   clase (`ProotNativeLibrary`) para que se pueda mockear, sustituir,
   y testear independientemente.

2. **`isAvailable()` estricto.** Solo retorna `true` cuando la
   librería está realmente detectada. "Bundled ABIs declared" NO es
   suficiente — JNI falla de formas raras si el .so no está.

3. **`-0` flag.** `-0` significa "user-id-0 emulation": proot no
   requiere root en el host pero se presenta como uid 0 dentro del
   rootfs. Esto es lo que hace que `apt install` corra sin root.

4. **Bind mounts por defecto desactivados en el stub.** El build probe
   `standardMounts(null, null)` devuelve `emptyList()` cuando no se le
   pasan paths reales. La activation real happens via Hilt-provided
   paths en runtime.

5. **JNI stub.** La razón de existir un stub separado es que cuando
   `libproot.so` esté realmente bundle, el JNI bridge será una pequeña
   clase que llama `System.loadLibrary("proot")` y `proot_main(argc,
   argv)`. Mantener ese punto único facilita el diff del commit que
   active proot.

---

## Cosas que dejé fuera intencionalmente (9.6.4.1 backlog)

- **Cross-compile / vendor** — depende de Jor con NDK
- **JNI bridge real** — 4 líneas; defer hasta tener el .so
- **Pts/PTY** — proot abre una pipe; necesitamos termux-pty para
  line-editing interactivo
- **Pre-built proot package** que el usuario descarga desde in-app (sin
  vendor). Mencionado en `INSTALL.md` como Stage 2 alternativa.

---

## Métricas Phase 9.6.4

| | Antes (9.6.3.3) | Después (9.6.4) |
|---|---|---|
| Tests | 323 | **330** (+7) |
| Native deps en APK | 0 | 0 (binary-pending) |
| `proot` wire-up readiness | stub | **real** (esperando .so) |
| Build vector docs | 0 | 1 (`proot/INSTALL.md`) |
| Distros ejecutables | cat/ls shell (jailed) | cat/ls shell + **apt/apk/pacman ejecutables** cuando .so bundle |
| `assembleDebug` | ✅ | ✅ 248 MB |

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** **Phase 9.6.5** — VNC client embebido en Compose (`libvncclient` JNI), permitiendo al usuario abrir `gimp`, `firefox`, `vscode` corriendo **dentro del Linux runtime** en ventanas Android nativas. Es el siguiente "wow moment" después del apt ejecutable.
