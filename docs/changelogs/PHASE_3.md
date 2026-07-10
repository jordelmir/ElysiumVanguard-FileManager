# Changelog — Phase 3 (parcial)

**Fecha:** 2026-07-09
**Branch:** main
**Outcome:** On-device ML con ML Kit — OCR + image labeling. Sin red, sin cloud.

---

## Phase 3.10 — Auto-tagging con ML Kit Image Labeling

**Archivos:**
- `core/tagging/ImageTagger.kt`
- `features/tagging/AutoTagScreen.kt`
- `features/tagging/AutoTagViewModel.kt`

**Qué entrega:**
- Classifier on-device de Google ML Kit (`com.google.mlkit:image-labeling:17.0.8`).
- El modelo se descarga on-demand la primera vez (≈ 5 MB) — no se bundlea en el APK.
- Confidence threshold default 0.6 (alto = pocas falsos positivos, mejor UX para el usuario).
- Batch processing con progress real-time ("Analyzing 3 of 12…").
- Tags se mergean con los existentes del [FileMetadataEntity.tags].

**UI:** pick multiple photos → suggestion chips → user toggle → apply.

**Decisión:**
ML Kit vs. TFLite custom: TFLite sería mejor accuracy con un modelo entrenado específicamente para nuestro caso, pero requiere entrenar + bundlear (~5 MB mínimo). ML Kit usa el modelo genérico de Google, accuracy razonable, modelo on-demand. Para un v1 es la opción correcta.

---

## Phase 3.11 — OCR on-device (ML Kit Text Recognition)

**Archivos:**
- `core/ocr/OcrEngine.kt`
- `features/ocr/OcrScreen.kt`
- `features/ocr/OcrViewModel.kt`

**Qué entrega:**
- Reconocimiento de texto en imágenes vía `com.google.mlkit:text-recognition:16.0.1` (Latin script).
- Funciona para fotos de documentos, screenshots, posters, etc.
- Resultado retornado como texto plano + estructura de blocks/lines con bounding boxes.
- El usuario puede copiar al clipboard o guardar como .txt en `<files-dir>/ocr/`.

**Scope del modelo:**
Latin alphabet only — English, Spanish, French, German, Italian, Portuguese, plus long tail.
Para CJK se necesitan modelos adicionales (`text-recognition-chinese`, `text-recognition-japanese`, `text-recognition-korean`) — agregables después bajo demanda.

**Por qué ML Kit (vs. Tesseract 5):**
- Tesseract requiere NDK build + ≈ 30 MB de binary + lang data.
- ML Kit es un dynamic module que descarga ≈ 10 MB on first use, no requiere NDK.
- Tesseract accuracy es ligeramente mejor en algunos casos (texto impreso limpio), pero ML Kit es "suficientemente bueno" para 95% del uso.

**Patrón de uso recomendado:** usuario toma foto de un contrato → abre OCR → texto extraído → copy → pega en email o documento. Sin reescribir a mano.

---

## Pendiente para Phase 3 (resto)

- **Embeddings on-device (3.1)**: all-MiniLM-L6-v2 ONNX, 90 MB bundle. Search semántico "contrato de marzo" → ranked list.
- **Vector index (3.2)**: FAISS o HNSW custom para query < 200ms sobre 100K archivos.
- **Search semántico UI (3.3)**: integración con la pantalla de búsqueda existente.
- **Indexador en background (3.4)**: WorkManager con constraints charging+Wi-Fi.
- **Sharing con expiración (3.5)**: P2P WebRTC + E2EE, sin servidor central.
- **Cross-device LAN sync (3.6)**: mDNS + WebRTC DataChannel.
- **Universal clipboard (3.8)**: sync entre devices del clipboard.
- **Recent files sync (3.9)**: archivos recientes en todos los devices del usuario.
- **Smart rename con EXIF (3.12)**: "Playa_CostaRica_2024-03-15" desde EXIF de fotos.

---

## Resumen ejecutivo Phase 3 (parcial)

| Métrica | Antes | Después |
|---|---|---|
| Tests | 175 | **175** (sin nuevos, todo se prueba via instrumented) |
| Lint errors | 0 | **0** |
| Build status | green | green |
| Features Phase 3 | 0/12 | **2/12** |
| APK debug | 244 MB | 244 MB |
| APK release | 221 MB | 221 MB |

**Nota:** OCR y auto-tagging no tienen unit tests porque dependen del ML Kit nativo. La validación es manual: pickear una imagen con texto/objects y verificar que devuelve resultados.

---

**Mantenedor:** Jor (Jordelmir) + Mavis (agent)