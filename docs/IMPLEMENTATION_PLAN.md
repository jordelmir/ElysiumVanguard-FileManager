# ElysiumVanguard-FileManager — Plan de Implementación Maestro

**Última actualización:** 2026-07-08
**Estado actual:** `main` @ `1c634a6` (working tree clean)
**Versión:** 1.0.0-TITAN

---

## 0. Filosofía

Convertir ElysiumVanguard-FileManager en el **mejor administrador de archivos de la historia humana**, mediante tres ejes:

1. **Búsqueda semántica on-device** — encontrar por significado, no por nombre.
2. **Agente LLM local** — "organiza mis Downloads" ejecutado con un comando de voz.
3. **Privacidad por diseño** — cero datos salen del device, todo verificable.

Todo lo demás (vault, dual-pane, servidor local) son **tabla de la verdad** o **diferenciadores** sobre esos ejes.

---

## 1. Estado actual (snapshot 2026-07-08)

### Inventario

- **47 archivos Kotlin** en `app/src/main/java/com/elysium/vanguard/`
- **10,211 LOC** estimadas
- **Build:** `assembleDebug` ✅ | `lint` ❌ (35 errores / 36 warnings)
- **APK debug:** 142 MB (inflado por deps no usadas)
- **Tests:** 0 tests automatizados
- **Release signing:** No configurado
- **ProGuard rules:** Mínimo (default + `proguard-rules.pro`)

### Funcionalidad existente

| Feature | Estado | Calidad |
|---|---|---|
| Music Hub (player + EQ) | ✅ Implementado | 🟠 Funcional con bugs CRITICAL |
| Document Viewer (PDF/DOCX/TXT) | ✅ Implementado | 🟠 Funcional con bugs CRITICAL |
| File Browser (MediaStore) | ✅ Básico | 🟡 Necesita filtros, búsqueda |
| Recents / Favoritos | ✅ Implementado | 🟢 Sólido |
| Audio Auto-Discovery | ✅ Implementado | 🟢 Sólido |
| FileProvider | ✅ Configurado | 🟢 Bien hecho |

### Funcionalidad FALTANTE (brecha)

- ❌ Trash / Undo (pérdida de datos silenciosa)
- ❌ Búsqueda (cualquier tipo)
- ❌ Batch operations
- ❌ Duplicados detection
- ❌ Vault cifrado
- ❌ Cloud sync (SFTP, WebDAV, SMB)
- ❌ Servidor local
- ❌ Sharing con expiración
- ❌ On-device AI (search semántico, LLM agent)
- ❌ Editor de archivos
- ❌ Tests

---

## 2. Fases del proyecto

### Resumen ejecutivo

| Fase | Duración | Outcome | Estado |
|---|---|---|---|
| **Fase 0** | 1-2 semanas | Sin crashes, build limpio, deps mínimas | 🔴 Pendiente |
| **Fase 1** | 4-6 semanas | Tabla de la verdad (trash, búsqueda, batch, dupes) | ⏸ Bloqueada |
| **Fase 2** | 4-6 semanas | Diferenciador inicial (vault, server, editor) | ⏸ Bloqueada |
| **Fase 3** | 8-12 semanas | IA real (search semántico, LLM agent lite) | ⏸ Bloqueada |
| **Fase 4** | 12+ semanas | Moonshots (time-travel, cross-device, AR) | ⏸ Bloqueada |
| **Fase 5** | Ongoing | Polish (theming, accesibilidad, companions) | ⏸ Bloqueada |

---

## FASE 0 — Remediación crítica (1-2 semanas)

**Objetivo:** Eliminar crashes en runtime, dejar build limpio, APK < 80 MB.

### Tareas

| # | Tarea | Archivo | Esfuerzo | Estado |
|---|---|---|---|---|
| 0.1 | Fix `Uri.fromFile` en NativeMediaPlayer → FileProvider | `NativeMediaPlayer.kt:62` | 30 min | 🔴 |
| 0.2 | Fix `loadUrl("file://")` en DocumentViewer → `content://` | `IntegratedDocumentViewer.kt:175` | 30 min | 🔴 |
| 0.3 | Remover `MANAGE_EXTERNAL_STORAGE` del Manifest | `AndroidManifest.xml:8` | 5 min | 🔴 |
| 0.4 | Remover `requestLegacyExternalStorage` del Manifest | `AndroidManifest.xml:14` | 5 min | 🔴 |
| 0.5 | Fix `DynamicsProcessing` API 28 con SDK check | `MusicHubViewModel.kt:259` | 30 min | 🔴 |
| 0.6 | Eliminar dep `ffmpeg-kit-full` (~50 MB) | `build.gradle.kts` | 15 min | 🔴 |
| 0.7 | Eliminar dep `mediapipe-tasks-genai` (~30 MB) | `build.gradle.kts` | 15 min | 🔴 |
| 0.8 | ProGuard/R8 rules para Hilt, Room, Media3, Compose | `proguard-rules.pro` | 1 h | 🔴 |
| 0.9 | Release signing config (debug-signed temporal) | `build.gradle.kts` | 30 min | 🔴 |
| 0.10 | Tests mínimos (permission, delete, SAF) | `app/src/test/`, `androidTest/` | 2 h | 🔴 |
| 0.11 | `assembleDebug` + `assembleRelease` + `lint` verdes | — | 1 h | 🔴 |

**Criterio de éxito Fase 0:**
- APK debug < 80 MB
- `lint` 0 errores críticos
- 4 crashes CRITICAL eliminados
- `assembleRelease` produce APK firmado

---

## FASE 1 — Tabla de la verdad (4-6 semanas)

**Objetivo:** Cumplir el mínimo competitivo en 2026.

### Features

| # | Feature | Spec | Esfuerzo | Dependencias |
|---|---|---|---:|---|
| 1.1 | Trash con auto-purge | SAF-based, configurable 7/30/90 días | 1 sem | SAF |
| 1.2 | Undo multi-nivel | Stack de operaciones, no solo delete | 3 días | 1.1 |
| 1.3 | Búsqueda fuzzy | Fuse4j, tolerante a typos | 1 sem | — |
| 1.4 | Filtros combinables | `*.pdf AND modified:last_week` | 1 sem | 1.3 |
| 1.5 | Búsqueda por contenido | Tantivy Mobile o custom inverted index | 2 sem | 1.3 |
| 1.6 | Batch rename con patrones | `{date}_{counter}`, regex, EXIF, tags | 1 sem | — |
| 1.7 | Duplicados detection | SHA-256, side-by-side, smart-select | 1 sem | — |
| 1.8 | Quick Look universal | PdfRenderer, image decoder, audio waveform | 2 sem | Coil |
| 1.9 | Storage analyzer | Treemap (D3-style custom) | 1 sem | — |
| 1.10 | Conflict resolution UI | Mismo nombre / mismo hash | 3 días | — |
| 1.11 | Cola de operaciones con ETA | Progress + speed + ETA | 3 días | — |
| 1.12 | Cancellable real | CancellationException handled | 2 días | — |

**Criterio de éxito Fase 1:**
- "Borrar un archivo" es recuperable durante 30 días.
- "Buscar contrato" encuentra cualquier archivo con "contrato" en el nombre o contenido, con typos.
- "Renombrar 100 fotos" se hace en una operación con preview.
- "Tengo duplicados" detecta y permite cleanup visual.

---

## FASE 2 — Diferenciador inicial (4-6 semanas)

**Objetivo:** Lo que hace que la gente quiera migrar.

### Features

| # | Feature | Spec | Esfuerzo | Dependencias |
|---|---|---|---:|---|
| 2.1 | Vault cifrado AES-256-GCM | Con passphrase + biometric | 2 sem | Tink / BouncyCastle |
| 2.2 | Borrado seguro (DoD 5220.22-M) | Overwrite 3-pass | 3 días | 2.1 |
| 2.3 | Servidor local HTTP | NanoHTTPD + auth | 1 sem | — |
| 2.4 | Servidor SFTP | JSch + key gen | 1 sem | — |
| 2.5 | Servidor SMB (descubrimiento mDNS) | smbj + jmDNS | 2 sem | 2.3 |
| 2.6 | Markdown editor | con preview live | 1 sem | — |
| 2.7 | Text editor con syntax highlight | Prism4j | 1 sem | — |
| 2.8 | Image editor (crop/rotate/redact) | Compose Canvas | 1 sem | — |
| 2.9 | PDF editor básico (sign/fill) | PdfRenderer + Compose | 2 sem | — |
| 2.10 | Dual-pane + tabs | Layout configurable | 1 sem | — |
| 2.11 | Drag & drop entre panes | Con conflict resolution | 3 días | 2.10 |
| 2.12 | Tags + colores + notas | Schema en Room | 1 sem | — |
| 2.13 | Smart folders (saved searches) | Query DSL | 1 sem | 1.3, 1.4 |
| 2.14 | Templates de operación recurrentes | Macro grabber | 1 sem | — |

**Criterio de éxito Fase 2:**
- "Pasar archivos a mi laptop" es un tap en el menú + QR.
- "Carpeta privada" cifra con AES-256, abre con biometría.
- "Editar un PDF" no requiere app externa.
- "Dos carpetas lado a lado" es nativo, no plugins.

---

## FASE 3 — IA real (8-12 semanas)

**Objetivo:** El diferenciador que cambia la categoría.

### Features

| # | Feature | Spec | Esfuerzo | Dependencias |
|---|---|---|---:|---|
| 3.1 | Embeddings on-device | all-MiniLM-L6-v2 ONNX, 90 MB | 2 sem | ONNX Runtime |
| 3.2 | Vector index (FAISS / HNSW) | Build incremental, query <200ms | 2 sem | 3.1 |
| 3.3 | Búsqueda semántica UI | "contrato de marzo" → ranked list | 1 sem | 3.1, 3.2 |
| 3.4 | Indexador en background | WorkManager, charging+Wi-Fi | 1 sem | 3.2 |
| 3.5 | Sharing con expiración (serverless) | P2P WebRTC + E2EE | 3 sem | — |
| 3.6 | Cross-device LAN sync | mDNS + WebRTC DataChannel | 3 sem | — |
| 3.7 | QR transfer | Generar + escanear | 3 días | — |
| 3.8 | Universal clipboard | Sync de clipboard entre devices | 1 sem | 3.6 |
| 3.9 | Recent files sync | Cross-device | 3 días | 3.6 |
| 3.10 | Auto-tagging con ML | ML Kit Smart Reply lite | 2 sem | — |
| 3.11 | OCR on-device | Tesseract 5 / ML Kit | 1 sem | — |
| 3.12 | Smart rename con EXIF | "Playa_CostaRica_2024-03-15" | 1 sem | — |

**Criterio de éxito Fase 3:**
- "El contrato de marzo" funciona sin saber el nombre del archivo.
- "Pásame esto a la tablet" funciona sin servidor, sin cloud.
- "Compártele este PDF a Juan con link que expira en 24h" funciona E2EE.

---

## FASE 4 — Moonshots (12+ semanas)

**Objetivo:** Las ideas que vuelven esto legendary.

### Features

| # | Feature | Spec | Esfuerzo | Riesgo |
|---|---|---|---:|---|
| 4.1 | On-device LLM (Phi-3-mini Q4) | llama.cpp Android, 1.8 GB | 6 sem | 🔴 Alto |
| 4.2 | Function calling para file ops | Move/rename/copy/delete tipados | 2 sem | 4.1 |
| 4.3 | UI conversacional | "Organiza mis Downloads" | 2 sem | 4.1, 4.2 |
| 4.4 | Sandbox de aprobación | LLM propone, user aprueba | 1 sem | 4.2 |
| 4.5 | Time-travel UI | Snapshots de filesystem | 4 sem | 🟡 Medio |
| 4.6 | File versioning tipo git | Content-addressable storage | 3 sem | 4.5 |
| 4.7 | Voice commands | Vosk + intent parser | 2 sem | 🟢 Bajo |
| 4.8 | AR file finder | Geolocation + CLIP | 4 sem | 🟠 Medio |
| 4.9 | Collaborative annotations | CRDT lite + E2EE | 6 sem | 🔴 Alto |

**Criterio de éxito Fase 4:**
- "Mete todos los PDFs de Contratos en un solo PDF" funciona con un comando de voz.
- "¿Cuándo borré ese archivo?" se responde moviendo un slider.
- "Anota este PDF con María" funciona sin servidor central.

---

## FASE 5 — Polish (ongoing)

### Features

| # | Feature | Spec |
|---|---|---|
| 5.1 | Material You theming | Dynamic color |
| 5.2 | Accesibilidad completa | TalkBack, switch, font scaling |
| 5.3 | Wear OS companion | Quick access + voice |
| 5.4 | Android Auto (passenger) | Browse media safely |
| 5.5 | Android TV (leanback) | Browse on TV |
| 5.6 | Chrome OS optimization | ARC++ perf |
| 5.7 | Web companion | SPA al servidor local |
| 5.8 | Internacionalización | 10+ idiomas |
| 5.9 | Documentación | User guide, API, ADRs |

---

## 3. Dependencias a incorporar (por fase)

### Fase 1
- `com.github.lithops-jvm:lithops:fuzzy-search` (búsqueda fuzzy)
- `org.tukaani:xz:1.9` (compresión adicional)
- `io.github.azurite-libraries:tantivy-mobile` (full-text search)

### Fase 2
- `com.google.crypto.tink:tink-android:1.13.0` (cifrado)
- `org.nanohttpd:nanohttpd:2.3.1` (servidor HTTP)
- `com.jcraft:jsch:0.1.55` (SFTP)
- `com.hierynomus:smbj:0.11.0` (SMB)
- `org.jmdns:jmdns:3.5.8` (mDNS discovery)

### Fase 3
- `com.microsoft.onnxruntime:onnxruntime-android:1.17.0` (embeddings)
- `com.facebook.faiss:faiss-android:1.7.4` (vector search) o custom HNSW
- `com.google.mlkit:text-recognition:16.0.0` (OCR)
- `com.alphacephei:vosk-android:0.3.45` (STT)

### Fase 4
- llama.cpp Android port (custom build)
- Phi-3-mini Q4_0 model (1.8 GB descarga on-demand)
- Automerge o Yjs (CRDT)

---

## 4. Riesgos globales

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Scope creep | 🔴 Alta | 🔴 Alto | Fases estrictas, check-in al final |
| APK se infla con modelos IA | 🔴 Alta | 🟠 Medio | Descargar on-demand, no bundlear |
| Battery drain con indexado | 🟠 Media | 🟠 Medio | WorkManager constraints |
| Privacy backlash | 🟢 Baja | 🔴 Alto | Privacy stance público, on-device |
| Competencia copia features | 🟠 Media | 🟡 Bajo | Velocidad de ejecución |
| LLM on-device no cabe en low-end | 🔴 Alta | 🟠 Medio | Fallback a búsqueda keyword |

---

## 5. Decisiones pendientes (preguntas para Jor)

1. **¿Open source o closed source?** (default propuesto: Apache 2.0, premium features)
2. **¿Monetización?** (default propuesto: gratis + premium opcional $5)
3. **¿Min SDK?** Actual 26. ¿Subimos a 28 para tener más APIs?
4. **¿Target usuarios?** Power users vs general public
5. **¿Timeline realista?** 6 meses MVP, 12 meses diferenciador

---

## 6. Cómo retomar este plan

1. **Sesión nueva:** Decir "continúa plan ElysiumVanguard desde Fase X"
2. **Estado actual:** Última fase completada está marcada ✅
3. **Tracking:** Cada tarea tiene un ID (X.Y) — buscar en código por el ID
4. **Documentación:** `docs/changelogs/` tiene el historial de cada fase

---

## 7. Métricas de éxito del proyecto

| Métrica | Target | Cómo medir |
|---|---|---|
| Crashes en runtime | 0 en flujos principales | Play Console / Firebase Crashlytics |
| APK size | < 80 MB debug, < 50 MB release | `apkanalyzer` |
| Time to first action | < 500ms | Macrobenchmark |
| Search latency (semantic) | < 200ms en 100K archivos | Bench local |
| Battery drain indexado | < 2% por ciclo completo | Battery historian |
| User retention D7 | > 30% | Play Console |
| Play Store rating | > 4.5 ⭐ | Reviews |

---

**Mantenedor:** Jor (Jordelmir) + Mavis (agent)
**Última revisión:** 2026-07-08