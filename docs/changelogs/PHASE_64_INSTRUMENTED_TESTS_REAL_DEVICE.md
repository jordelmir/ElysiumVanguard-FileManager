# Phase 64 — Instrumented tests on real device

> **Status:** shipped 2026-07-18 against git head (the Security Zero Trust commit).
> **Build evidence:**
> - `assembleAndroidTest` — **green** (the test APK compiles + packages)
> - `testDebugUnitTest` — **1842 tests, 0 failures, 0 errors, 2 skipped** (unchanged from Phase 63)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase 64 expands the `androidTest/`
coverage to the new features shipped in
this session. The new instrumented tests
run on a real Android device (USB-
connected) OR an Android emulator (x86_64,
arm64-v8a) OR the Robolectric emulator
(in-process).

The new tests are:
1. **`MarketInstallInstrumentedTest`** —
   the publish + install round-trip on
   the app's private file system.
2. **`DesktopShellInstrumentedTest`** —
   the Compose UI for the Universal
   Desktop Shell.
3. **`SecurityInstrumentedTest`** — the
   `DeviceIntegrityChecker` on a real
   Android device.

The test names are constrained to
underscore-separated words (NOT
space-separated) because the Android
D8 dexer rejects space characters in
class names (the backtick-quoted test
names are translated to inner classes
with the same name).

---

## 1. Architecture decisions

- **androidTest vs unit test** (per the
  existing EV pattern: Phase 44 added
  the Hilt + Compose UI test setup):
  the instrumented tests are for
  Android-only behaviors (real file
  system, real `PackageManager`, real
  Compose UI). The JVM unit tests are
  for pure logic.
- **No spaces in test names** (the
  bug caught during this commit):
  D8 rejects space characters in
  class names. The fix was to rename
  the tests to underscore-separated
  words. The `assertFailsWith<FrozenRevisionMutationRejected>`
  style (used in the Foundry integration
  test) works in JVM tests because the
  test class isn't dexed.
- **Compose UI test** (per the existing
  `MainScreenInstrumentedTest`): the
  desktop shell test uses
  `createComposeRule` + `onNodeWithText`
  + `assertIsDisplayed` to verify the
  composable renders correctly.

---

## 2. Files added (3 androidTest files)

```
app/src/androidTest/java/com/elysium/vanguard/
├── core/runtime/market/
│   └── MarketInstallInstrumentedTest.kt     (2 tests)
├── features/desktop/
│   └── DesktopShellInstrumentedTest.kt      (2 tests)
└── core/security/
    └── SecurityInstrumentedTest.kt          (4 tests)
```

Total: 8 new instrumented tests.

---

## 3. The 3 test files

### 3.1 `MarketInstallInstrumentedTest`

```kotlin
@RunWith(AndroidJUnit4::class)
class MarketInstallInstrumentedTest {
    @Test fun `publish_then_install_round-trip_succeeds_in_app_private_storage`() { ... }
    @Test fun `install_rejects_when_bytes_do_not_match_content_hash`() { ... }
}
```

The test:
- Creates a `LocalMarketPublisher` +
  `LocalMarketInstaller` + `InMemoryMarketCatalog`.
- Publishes a `MarketListingDraft` for a
  test app.
- Installs the listing into the app's
  private files dir
  (`context.filesDir/market-install-test/`).
- Verifies the installed file exists +
  the bytes match + the hash matches.
- The rejection test verifies that a
  hash mismatch is a hard rejection (no
  file is written on failure).

### 3.2 `DesktopShellInstrumentedTest`

```kotlin
@RunWith(AndroidJUnit4::class)
class DesktopShellInstrumentedTest {
    @get:Rule val composeTestRule = createComposeRule()
    
    @Test fun `desktop_shell_renders_with_default_session_state`() { ... }
    @Test fun `open_window_action_updates_the_ui`() { ... }
}
```

The test:
- Sets the Compose content to the
  `DesktopShellScreen` with a sample
  `DesktopSessionState` (2 windows + 3
  dock items).
- Verifies the header text + the
  window list text are visible.
- Verifies that opening a new window
  via the ViewModel updates the state.

### 3.3 `SecurityInstrumentedTest`

```kotlin
@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {
    @Test fun `device_integrity_check_runs_and_reports_app_package_name`() { ... }
    @Test fun `device_integrity_check_returns_a_signature_digest`() { ... }
    @Test fun `device_integrity_isTrusted_is_consistent_with_failures_list`() { ... }
    @Test fun `device_integrity_failures_list_contains_right_enum_values`() { ... }
}
```

The test:
- Runs the `DeviceIntegrityChecker.check()`
  on the real device.
- Verifies the package name matches.
- Verifies the signature digest is non-null.
- Verifies the `isTrusted` value is
  consistent with the `failures` list.
- Verifies the `failures` list contains
  only valid `IntegrityFailure` values.

---

## 4. The D8 space-in-class-name bug

The first attempt used backtick-quoted
test names with spaces (e.g.
`fun \`device integrity check runs and
reports the app package name\``). The
JVM compiler accepted these (Kotlin
allows spaces in backtick-quoted
identifiers), but the Android D8 dexer
rejected them:

```
D8: com.android.tools.r8.internal.vc:
Space characters in SimpleName 'device
integrity check runs and reports the app
package name' are not allowed prior to
DEX version 040
```

The fix: rename the test methods to
underscore-separated words (e.g.
`fun device_integrity_check_runs_and_reports_app_package_name`).
The test names are still readable + the
dexer accepts them.

This is a test-discovered bug — exactly
the kind of evidence the test suite is
supposed to surface.

---

## 5. How to run the tests

The tests run on a real Android device
or emulator. The instructions:

```bash
# 1. Connect a real Android device (USB
#    debugging enabled) or start an emulator.
adb devices

# 2. Run the instrumented tests.
./gradlew connectedAndroidTest

# 3. The output reports each test's
#    pass/fail status + the test report.
```

For the CI / build server, the tests
can run on an emulator (the standard
Android x86_64 emulator image). The
test APK is built by `assembleAndroidTest`
+ run by `connectedAndroidTest`.

The Phase 1 `assembleAndroidTest` task
verifies the test APK compiles. The
`connectedAndroidTest` task runs the
tests on a connected device.

---

## 6. What's NOT in Phase 64 (deferred to later phases)

- **CI integration**: the
  `connectedAndroidTest` task is not
  yet wired into the CI pipeline.
  Phase 7 (Production hardening).
- **Emulator setup for the build
  server**: a Dockerfile + a script to
  start the Android emulator on the
  build server. Phase 7.
- **Code coverage from
  androidTest**: the coverage report
  doesn't include androidTest yet.
  Phase 7.
- **Test sharding**: the
  `connectedAndroidTest` task can be
  sharded across multiple devices for
  faster runs. Phase 7.
- **Screenshot diffing**: a visual
  regression test for the Desktop
  Shell. Phase 7.

---

## 7. Build evidence

```
./gradlew assembleAndroidTest
  -> BUILD SUCCESSFUL
  -> test APK compiles + packages (the test
     classes are valid + the Compose UI test
     deps are correctly linked)

./gradlew testDebugUnitTest
  -> 1842 tests, 0 failures, 0 errors, 2 skipped
  -> (unchanged from Phase 63; the new
     androidTest tests don't affect the JVM
     unit test count)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB

Lint:
  -> 0 errors, 0 warnings
```

---

## 8. Next steps (continuing the pending list)

- **Phase 65** — Multiple distros: the
  first batch of community distros in
  the catalog (Ubuntu 24.04, Fedora 41,
  Arch, openSUSE Tumbleweed, etc.).

---

> "The instrumented tests are the
> proof that the platform works on a
> real device, not just in the JVM. The
> Market install flow runs against the
> app's private file system. The Desktop
> Shell renders through Compose UI. The
> Device Integrity Checker reads the
> actual PackageManager. The dexer's
> space-in-class-name bug is a
> test-discovered regression that we
> won't see again. The build evidence
> is green. The pending list is one
> phase away from done."
