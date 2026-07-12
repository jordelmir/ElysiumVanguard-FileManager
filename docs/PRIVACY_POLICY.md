# Elysium Vanguard — Privacy Policy

**Last updated:** 2026-07-12
**Effective:** 2026-07-12

Elysium Vanguard is a local file manager and Linux runtime. This policy
describes the current development build. It must be reverified against the
exact release APK before public distribution.

## TL;DR

- App-created files and metadata are stored on your device unless you
  explicitly publish, transfer or synchronize them.
- The app has **no analytics SDK, no advertising SDK, no crash reporting
  SDK, and no third-party telemetry**.
- Network features include optional LAN HTTP/SFTP sharing, explicit peer sync,
  and explicit Linux rootfs downloads from the Internet.
- The app's auto-backup to Google Drive is opt-in at the OS level and excludes
  sensitive paths (see "Backup" below).

## Data we handle

| Data | Where it lives | Who can see it |
|---|---|---|
| File metadata (name, size, type) | Your device, in our Room database | You, the app |
| Vault encrypted files (when you use Vault) | Your device, in `files/vault/` | You, the app |
| Vault metadata (which files are encrypted) | Your device, in Room | You, the app |
| SFTP server host key | Your device, in `files/sftp-hostkey/` | You, the app, anyone on your LAN with the password |
| OCR results (text extracted from images) | Your device, in `files/ocr/` | You, the app |
| Tags, colors, notes you set on files | Your device, in Room | You, the app |
| Smart folders you created | Your device, in Room | You, the app |
| Trashed files (files you deleted via the app) | Your device, in `files/trash/` | You, the app, until auto-purge |
| Your preferences (theme, view mode) | Your device, in SharedPreferences | You, the app |
| Linux rootfs and runtime metadata | App-private device storage | You, the app and processes launched in that runtime |
| Sync payloads you explicitly configure | Your device and the selected peer | You and that peer |

Linux processes execute under the same Android app identity. PRoot is not a VM
or a security boundary; treat packages installed inside a distro as software
that can access data exposed to the app identity.

## Local network servers (optional)

The app can run two servers on your device, both entirely under your control:

### Local HTTP server
- Listens on `0.0.0.0:8765` (configurable)
- Serves a single-page web UI for browsing/downloading/uploading files
- Authenticated by a one-time bearer token shown as a QR code
- Only accessible from devices on the same Wi-Fi network as your phone
- Does not initiate an Internet connection

### SFTP server
- Listens on `0.0.0.0:2222` (configurable)
- Standard SSH/SFTP protocol — connect from any SSH client (terminal,
  FileZilla, Cyberduck, etc.)
- Authenticated by username + auto-rotated password
- Only accessible from devices on the same Wi-Fi network
- Does not initiate an Internet connection

Both servers are off by default. You start them from the file manager's
tools menu. Their server classes default to `127.0.0.1`; the explicit sharing
flow publishes them to `0.0.0.0` so another LAN device can connect. Credentials
are required. They stop when you tap "Stop" or kill the app.

## Outbound network requests

- Installing a catalog distro downloads the pinned archive over HTTPS from the
  source shown in the runtime catalog and verifies its SHA-256 before
  extraction.
- Installing a custom rootfs contacts the URL you provide. Only use sources you
  trust; a signed Elysium manifest system is not implemented yet.
- Document synchronization contacts only the peer URL you configure.

These requests reveal normal network metadata, such as your IP address, time
and requested URL, to the destination and intervening network providers. The
app does not add analytics identifiers to them.

## Backup

Android's auto-backup feature (Settings → System → Backup) can upload app
data to Google Drive. The default behavior in modern Android is to back up
**only the data explicitly allow-listed** in our backup rules.

No app `files/` content is included. This excludes, including for future paths:
- SFTP host key (private key material)
- Vault encrypted payloads
- OCR output (extracted text from your documents)
- Trash contents, rootfs, terminal history and temporary downloads

We **include** the following so your settings carry across reinstalls:
- App preferences (theme, view mode, recent folders)
- Tags, colors, notes you set on files
- Smart folders you created
- Vault **metadata** (which files are encrypted, but not the ciphertext)

The exact allow-list is `titan_sovereign.db` plus palette, music, gallery and
trash-retention preferences. Persisted SAF grants are intentionally not backed
up because they are device-specific.

**You can disable auto-backup entirely** at the OS level (Settings → System
→ Backup → toggle off). We respect whatever you choose.

## Permissions

The app requests the following permissions, and only these:

| Permission | Why | What happens if you deny it |
|---|---|---|
| `INTERNET` | HTTP/SFTP, peer sync and explicit rootfs downloads | Those network features do not work; local file tools remain available |
| `READ_MEDIA_AUDIO/VIDEO/IMAGES` (Android 13+) | Find media files in the system gallery | The file manager can only see files you grant via the SAF picker |
| `READ_EXTERNAL_STORAGE` (Android ≤ 12) | Read user files on older Android | Same as above for old Android versions |
| `WRITE_EXTERNAL_STORAGE` (older Android) | Create, move and modify user files on older Android | Write operations outside app storage are unavailable |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | Optional direct whole-device file-manager access | The app falls back to Android-granted locations and SAF-capable paths |
| `POST_NOTIFICATIONS` | Show progress for long operations | Progress notifications don't appear; ops still complete |
| `WAKE_LOCK` | Keep the device awake during long ZIP operations | ZIPs may pause if the device sleeps |
| `FOREGROUND_SERVICE` | Run long operations in the background | ZIPs/HTTP server stop when you background the app |
| `FOREGROUND_SERVICE_DATA_SYNC` | Same as above, with the correct type for Android 14+ | Same as above |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Show connectivity and LAN addressing; adapt runtime networking | Network status and sharing diagnostics degrade |

We do **not** request:
- `READ_PHONE_STATE`
- `ACCESS_FINE_LOCATION` or any location permission
- `CAMERA` (the OCR feature accepts images you pick, not camera capture)
- `RECORD_AUDIO`
- `READ_CONTACTS`, `READ_SMS`, `READ_CALL_LOG`
- `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS` or `SYSTEM_ALERT_WINDOW`

## Children's privacy

The app is not directed to children under 13. We do not knowingly collect
any data from children.

## Changes to this policy

If we change this policy materially (e.g. we add a third-party SDK), we
will:
- Update the "Last updated" date above
- Surface a notice in the app's settings screen
- For changes that affect what data leaves the device, require explicit
  re-consent

## Contact

For privacy questions, contact the developer at the email address listed on
the app's Google Play listing (or F-Droid listing, GitHub releases, etc.).

## License

The app is distributed under [license TBD]. The source code is available
at the repository link in the app's settings.

---

**This document is part of the Elysium Vanguard app source. Last verified
against the Phase 0 tree on 2026-07-12; a release commit is not yet assigned.**
