# PureLock

**Protect Your Mind & Life** — a voluntary self-control app for Android that helps you stay away from
adult websites, distracting apps and excessive phone use, using a lock that survives app closure,
process death and reboot.

> **Status — please read.** All of the code was written and statically cross-checked (resources,
> view bindings, string translations, placeholders), but **it has not been compiled or run on a device
> yet**: the environment it was written in has no Android SDK and no network. Expect to fix a few
> compile errors on the first build. The manual test plan at the end lists what still has to be
> verified on a real phone.

- Android 8.0+ (API 26), compile/target SDK 36, Kotlin, Material Design 3 (XML views), MVVM
- Room, DataStore, WorkManager, Fused Location + Geofencing, `VpnService`, `AccessibilityService`
- English and বাংলা, Premium Dark and Premium White themes
- No analytics, no ads, no accounts, no PureLock server

## Build

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 36.

```bash
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on a device, or open the folder in Android Studio and press Run.

A ready-made GitHub Actions workflow that builds the APK and runs the tests is in
`docs/build-apk.yml`. Copy it to `.github/workflows/build-apk.yml` (GitHub needs the *workflows*
permission for that path) and the APK appears as a downloadable artifact on every push.

No Google Maps API key is needed: distances are calculated with `Location.distanceBetween`, and no
map is shown, so the Maps SDK is deliberately not included.

## What it does

### Smart Lock (Protection tab)
| Mode | Behaviour |
|---|---|
| **Time** | 30 min, 1/3/6/12/24 h, 3/7/15/30 days or custom (5 min to 90 days). |
| **Distance** | Saves the starting point; unlocks after you travel 1/3/5/10/20 km or a custom distance. |
| **Combined** | Both, with a clearly labelled rule: **ANY** condition or **ALL** conditions. |

How the timer stays correct:
- The lock is stored in Room as a *checkpoint*: time remaining at a moment on the **monotonic clock**
  (`elapsedRealtime`) plus the wall-clock time and boot counter.
- Same boot: the monotonic clock is used, so changing the date/time on the phone neither shortens nor
  lengthens the lock.
- After a reboot the wall clock is used for the time the phone was off; a clock set backwards never
  adds time. Boot recovery, `TIME_SET` and `TIMEZONE_CHANGED` receivers re-anchor the checkpoint.
- A one-shot WorkManager job ends the lock near its end time even if the app is never opened;
  a 15-minute periodic worker re-checks everything.

While a lock is active, anything that would **weaken** it is refused (removing a block, allowing a
site or app, lowering protection settings, restoring a backup, clearing data). Making it stricter is
always allowed.

### Adult content blocker
- Local DNS filtering with `VpnService`. Only the virtual DNS address is routed into the tunnel, so
  your other traffic never passes through the app. Blocked names get an `NXDOMAIN` answer; allowed
  names are forwarded to the network's own DNS, CleanBrowsing Adult Filter or Cloudflare for Families.
- Bundled starter list, optional full list download (public hosts-format file, plain HTTPS GET, no
  data sent), your own blacklist and allowlist (allowlist cannot be extended during a lock).
- SafeSearch for Google, Bing, DuckDuckGo and YouTube Restricted Mode via DNS rewriting.
- HTTPS is never inspected and no certificate is installed.
- A plain explanation dialog is shown before Android's VPN consent screen.

### App blocker
- Lists launchable apps with icons, search, per-app rules: block, block only during locks, daily
  limit in minutes, always-allow.
- Blocking uses an `AccessibilityService` that only learns *which app is in front*. It is enabled
  by the user in system settings after a disclosure dialog.
- Usage monitoring and daily limits use Android's *Usage access* permission.
- Phone, dialer, emergency, launcher, system UI and PureLock itself can **never** be blocked.
- The block screen always offers *Go home*, *Emergency call* (opens the dialer) and, if set, *Call
  trusted contact*.

### Location tab
Current position and accuracy, permission and GPS status, distance-lock progress, safe zones
(geofences) that can automatically start a time lock on arrival, and a button to delete location
history. Location data stays on the device and only the last 30 days are kept.

### Anti-bypass (honest by design)
`IntegrityChecker` verifies, every 15 minutes and live on the Home screen, whether what the lock needs
is really working: VPN running, accessibility service enabled, location permission / background
permission / GPS / recent fixes, usage access, Private DNS in strict mode, notifications, battery
optimization. Problems are shown on Home, logged, counted as interruptions and notified.

### Statistics
Computed only from stored events: protection time per day (split correctly across midnight),
week/month totals, focus streak, website and app blocks, interruptions, 7-day chart, achievements.
An empty database shows zeros.

### Emergency access
- Emergency override ends any lock at once after typing a confirmation word; it is always recorded.
- The optional trusted contact (stored encrypted) is only *offered* a pre-written SMS after an
  override, and nothing is sent unless you press send in your messaging app.

## Setup and permissions

| Feature | What to do |
|---|---|
| Website filter | Allow the VPN dialog. Set Android *Private DNS* to Automatic/Off and disable "Secure DNS" in browsers. |
| App blocking | Settings → Accessibility → PureLock → On. |
| Daily app limits | Settings → Special access → Usage access → PureLock. |
| Distance lock | Allow location, then "Allow all the time" for background updates. |
| Reliable background work | Battery → PureLock → Unrestricted (some vendors kill background apps aggressively; see dontkillmyapp.com). |

### Device Owner (optional, stronger protection)
While a lock is active PureLock can then block its own uninstall and force-stop. It is **never**
enabled by the app. You do it once, from a computer:

1. Use a device with no accounts added (or factory reset), enable USB debugging.
2. Run: `adb shell dpm set-device-owner com.purelock.app/com.purelock.app.admin.PureLockAdminReceiver`
   (debug builds use the package `com.purelock.app.debug`).
3. To remove it later: factory reset, or clear Device Owner via the device vendor's tools.

Emergency calls always keep working.

## Honest limitations

- An ordinary Android app **cannot** stop you from force-stopping it, revoking permissions,
  uninstalling it or turning off the VPN. PureLock detects this and tells you; it does not pretend
  otherwise. "Uninstall protection" is a deterrent that sends you Home from the relevant Settings
  screens, not a guarantee.
- DNS filtering can be bypassed by browsers' own encrypted DNS, other VPNs and proxies, and it cannot
  know about every adult site. No claim of complete protection is made.
- A blocked website shows the browser's own "site can't be reached" page. A custom block *page*
  would require intercepting HTTPS, which PureLock deliberately never does; a motivational
  notification is shown instead.
- Background location is best-effort: Android and device makers may pause it. When it cannot run, the
  Home and Location screens say so.
- Geofences are not kept across reboot by Android; PureLock re-registers them after boot.

## Privacy
Nothing leaves the device except the optional filter-list download (a public file, plain GET) and, if
you pick a family DNS provider, your DNS lookups going to that provider. No analytics SDKs, no
accounts, `allowBackup=false`, trusted contact stored with `EncryptedSharedPreferences`. Backups are
JSON files you save yourself and never contain location history, the contact or an active lock.

## Project layout
```
app/src/main/java/com/purelock/app/
  data/        Room entities/DAOs, DataStore, secure store, blocklist, backup, stats
  domain/      LockManager (engine), LockEvaluator (pure rules), models
  vpn/         VpnService, DNS + IP/UDP packet code, DNS filter, SafeSearch
  service/     AccessibilityService, app-block policy, WorkManager workers, boot receiver
  location/    Fused location helper, geofences
  security/    IntegrityChecker
  admin/       Optional Device Owner helpers
  ui/          MainActivity + 5 fragments, app list, block screen, custom views, theme manager
app/src/test/  JVM unit tests (lock rules, time handling, DNS/IP packets, domains, stats, formatting)
```

## Manual test plan (not yet done on a device)
1. All five tabs open without crashing, in Dark and White, in English and বাংলা.
2. Time lock: start 30 min lock, close app, swipe away, reboot, change the phone clock: remaining time is right.
3. Combined lock ANY / ALL behaves as labelled; the countdown ring shows the correct progress.
4. VPN: adult domain from the list fails to load, ordinary sites load, SafeSearch is applied, stopping the VPN shows the warning and a notification.
5. App blocking: a selected app is replaced by the block screen; Phone and dialer stay available.
6. Daily limit: app is blocked once usage reaches the limit (needs usage access).
7. Distance lock: walk or simulate location; background update, GPS off, permission revoked all show honest warnings.
8. Safe zone auto-lock after a reboot (geofence re-registered).
9. Emergency override ends the lock and is listed under security events; trusted-contact SMS is only drafted.
10. Backup export/import round trip; "Clear all data" refused during a lock and works after.
11. Theme choice and language persist after restarting the app.
