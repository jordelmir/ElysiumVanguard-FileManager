# Changelog — Phase 8 (SAF-First + Play Store Ready)

**Fecha:** 2026-07-09
**Branch:** main
**Outcome:** SAF-first migration, trash integration, ZIP bomb protection, Privacy Policy, UI split. 208 tests pasando, 0 lint errors.

---

## Resumen ejecutivo

Phase 8 ataca las dos caras del "listo para Play Store":
1. **Storage moderno** — reemplazar el flujo roto de `MANAGE_EXTERNAL_STORAGE` con SAF tree picker.
2. **UX/UI** — split de los sub-Composables más grandes de `FileManagerScreen` y un estado "Connect a folder" bien diseñado.

**Métricas finales:**

| Métrica | Antes (Phase 7) | Después (Phase 8) |
|---|---|---|
| Tests | 190 | **208** |
| Lint errors | 0 | 0 |
| APK release | 148 MB | 148 MB |
| Bloqueador Play Store C-3 (MANAGE_EXTERNAL_STORAGE no declarado) | 🔴 activo | 🟢 **resuelto** (SAF flow) |
| Trash integration en deleteSelected | ❌ hard-delete | ✅ **movido a trash por default** |
| ZIP bomb protection | ❌ | ✅ **1 GB total + 512 MB per-entry caps** |
| Privacy Policy | ❌ | ✅ **publicada en `docs/PRIVACY_POLICY.md`** |

---

## Phase 8.1 — Lectura de FileManagerScreen (no entregado como código)

**Hallazgo:** `FileManagerScreen.kt` tiene 1867 líneas. Concentra: top bar, breadcrumbs, file list, file grid, search bar, AI dialog, compression progress, batch actions, storage metrics, options dialog, terminal mode, layout theming.

**Decisión:** no intento un split completo (refactor de 5-6 archivos) — es trabajo de varias sesiones. Hago un split pragmático:
- Extraje `FileManagerToolsDropdown` (10+ items del menú) a archivo propio.
- Extraje `ConnectFolderPrompt` (el estado "no hay SAF tree") a archivo propio.
- El main `FileManagerScreen` se mantiene; los sub-Composables que ya tenía (TopBar, Breadcrumb, etc.) siguen dentro pero ahora mejor organizados.

**Razón:** un split de 1867 líneas en 5-6 archivos toca 50+ referencias; hacerlo bien toma 1-2 sesiones dedicadas de UI design. La extracción de los dos Componentes grandes arriba es el mayor impacto inmediato con el menor riesgo de regresión.

---

## Phase 8.2 — SafTreeManager (foundation)

**Archivo:** `core/saf/SafTreeManager.kt` (nuevo)

**Responsabilidad:** ciclo de vida del URI de árbol SAF.
- `onTreePicked(uri)`: toma persistable permission, persiste URI en SharedPreferences.
- `refresh()`: re-verifica que el permiso sigue vigente (el sistema puede revocarlo sin avisar).
- `disconnect()`: libera permission + limpia persistencia.
- `listChildren(folderUri)`, `resolveChild(relativePath)`: walk del árbol via `DocumentFile`.

**Decisiones técnicas:**
- `context` nullable para unit-testable.
- `releaseUriPermissionCompat` usa reflection para la 1-arg form en API 26-32 (la 2-arg solo existe desde API 33). Esto es necesario porque el compiler con `compileSdk = 34` solo ve la firma nueva.
- Estado expuesto via `StateFlow<Uri?>` para que el ViewModel pueda observarlo reactivamente.

---

## Phase 8.3 — SAF picker UI en MainActivity

**Archivo:** `MainActivity.kt`

**Cambio:** el viejo `checkAndRequestStorageRoot` que enviaba al usuario a `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (que no funcionaba porque el permiso no estaba declarado) es reemplazado por:

```kotlin
private val safPickerLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri: Uri? ->
    if (uri != null) safTreeManager.onTreePicked(uri)
}
```

Y un `showSafPickerPrompt()` con `AlertDialog` que explica al usuario por qué necesitamos acceso. El VM observa `safTreeManager.currentTreeUri` y recarga automáticamente.

**Decisión:** el picker aparece solo si el usuario no tiene árbol. En API < 30 (legacy) mantenemos el flujo de permisos estándar.

---

## Phase 8.4 — FileManagerRepositoryDual

**Archivo:** `features/filemanager/FileManagerRepositoryDual.kt` (nuevo)

**Patrón dual-mode:** paths con prefijo `saf:` van via `DocumentFile`; paths absolutos van via `File` API.

| Path prefix | Backend | Casos de uso |
|---|---|---|
| `saf:foo/bar.pdf` | `DocumentFile.fromTreeUri` | Archivos del usuario en árbol granted |
| `/sdcard/...` o `/data/...` | `java.io.File` | App-private dirs, fallback |

**Métodos:** listOnce, delete, rename, copy, move, folderSize, storageStats. Todos dispatchados a `Dispatchers.IO` por el caller (ViewModel).

---

## Phase 8.5 — Trash integration en deleteSelected

**Archivos:** `FileManagerViewModel.kt`, `core/trash/TrashRepository.kt`

**Cambio:** `deleteSelected()` ahora llama `trashRepository.moveToTrash()` por cada archivo seleccionado, en vez de `file.delete()`. El usuario va a Trash (existente) y puede restaurar o purgar.

**Bug encontrado durante el test:** el branch `FromFile` de `moveToTrash` solo copiaba al trash pero NO borraba el source. Fixed.

**Nuevo método:** `deleteSelectedPermanently()` para el caso "Delete forever" (que debe ser explícito, no el default).

**Event:**
```kotlin
sealed class FileManagerEvent {
    ...
    data class Snackbar(val message: String) : FileManagerEvent()
}
```

---

## Phase 8.6 — ZIP bomb protection

**Archivo:** `core/util/CompressionEngine.kt`

**Cambios:**
- `MAX_DECOMPRESSED_BYTES = 1 GB` (total cap)
- `MAX_ENTRY_BYTES = 512 MB` (per-entry cap)
- Tracking de bytes escritos durante el streaming; aborta con `SecurityException` si excede cualquiera de los dos.
- Zip Slip (canonical path check) ya existía — preservado.
- Ratio check eliminado: `ZipEntry.size` se actualiza durante el stream, no es declarado up-front, así que la check es flaky. Los size caps son la defensa principal y suficiente.

**Tests:** 5 nuevos tests cubren round-trip, total cap, per-entry cap, Zip Slip.

---

## Phase 8.7 — Privacy Policy

**Archivo:** `docs/PRIVACY_POLICY.md` (nuevo)

Documento completo para Play Store Data Safety form. Cubre:
- Lista de datos que la app maneja (todo on-device)
- Permisos y qué pasa si el usuario los deniega
- Backup behavior (qué se incluye / excluye)
- Local network servers (HTTP, SFTP) — no internet
- Children's privacy, contacto, cambios

**Estructura:** secciones claras, una tabla de "data → location → who can see" para el reviewer de Play Console.

---

## Phase 8.8 — UI Split

**Archivos nuevos:** `features/filemanager/components/FileManagerToolsDropdown.kt`, `ConnectFolderPrompt.kt`.

**FileManagerToolsDropdown** — 13 items del menú centralizados en un componente testeable. La lista `tools: List<ToolItem>` hace que "qué hace este menú" sea legible de un vistazo.

**ConnectFolderPrompt** — estado vacío que muestra icono + copy + botón "Pick folder". Reemplaza el viejo `SovereignAccessDeniedScreen`.

---

## Phase 8.9 — Tests nuevos (18)

| Test file | Tests | Cubre |
|---|---|---|
| `core/util/CompressionEngineTest.kt` (nuevo) | 5 | Round-trip, total cap, per-entry cap, Zip Slip |
| `core/trash/TrashRepositoryTest.kt` (nuevo) | 3 | move-to-trash bytes, delete + db row, distinct rows |
| `features/filemanager/FileManagerRepositoryDualTest.kt` (nuevo) | 9 | List/delete/copy/move/rename/size/stats |
| (los 1 restantes son de FileManagerRepository original) | 1 | Re-test del path safety |

**Bug encontrado por los tests:** `moveToTrash` para `FromFile` no borraba el source. Fixed.

---

## Privacidad del APK

| | Phase 7 | Phase 8 |
|---|---|---|
| Tests | 190 | **208** |
| APK debug | 243 MB | 243 MB |
| APK release (firmado) | 148 MB | 148 MB |
| Lint errors | 0 | 0 |
| Play Store blockers | 3 (C-3 crítico) | 0 |

---

## Pendiente para Play Store (Phase 9)

- Data Safety form completado en Play Console con la info de Privacy Policy.
- Privacy Policy URL pública (alojada en GitHub Pages o sitio del dev).
- Instrumented tests reales de SAF (requieren Robolectric o device).
- Splash screen + app icon adaptive compliant con Android 12+.
- Store listing con screenshots en 5+ idiomas.
- CI con GitHub Actions: build + lint + test + APK size budget.
- Crash reporting (Sentry o Firebase) con redacción de paths.

---

## Lecciones aprendidas

1. **`returnDefaultValues = true` en `testOptions.unitTests` es esencial** para tests que tocan `StatFs`, `Environment`, etc. sin Robolectric. Sin esto, todo test que llame a un Android API no-mocked explota.

2. **Tests revelan bugs reales.** El bug de `moveToTrash` que no borraba el source llevaba ahí desde Phase 1.1; solo se descubrió porque escribí el test en Phase 8. **Test the delete path explicitly.**

3. **Reflection para compatibilidad cross-API.** `releasePersistableUriPermission` cambió de firma en API 33. La opción "1-arg deprecated" no compila contra `compileSdk = 34`. Reflection es la única forma limpia.

4. **Hilt + `@HiltViewModel` no se mezcla con `@Inject` en Activity.** Hilt rechaza la inyección de ViewModels via field. La solución: el ViewModel observa un `StateFlow` que la Activity muta. Patrón reactivo > acoplamiento directo.

5. **SAF es la respuesta correcta para file managers.** El modelo de `Environment.getExternalStorageDirectory()` está roto en Android 11+. SAF tree picker es el patrón oficial y Play-Store-friendly.

---

**Mantenedor:** Jor (Jordelmir) + Mavis (agent)