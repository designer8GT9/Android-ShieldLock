# ShieldLockAuth

**Extra security layers for your Android, after the system lock.**

ShieldLock adds additional verifications *after* you unlock your phone with your Android PIN, pattern or fingerprint. If someone manages to get past your normal lock, they still have to get through ShieldLock's layers.

- **Package:** `com.shieldlock.auth`
- **Language:** Kotlin · Material 3
- **minSdk:** 24 (Android 7.0) · **targetSdk:** 37
- **Languages:** Español, English, Português, 中文
- **No internet connection:** the app does not request the `INTERNET` permission; everything is processed and stored on your device.

---

## How does it work?

ShieldLock runs a foreground service that detects when you unlock the phone. At that moment it shows its own lock screen on top of everything and does not let you use the phone until you get past the layers you configured.

```
Android lock screen (1st)
        ↓
Current time PIN (2nd)
        ↓
Your own password (3rd, optional)
        ↓
Secret calculator (4th, Device Owner only)
        ↓
Access to the phone
```

When the screen turns off, the verification is invalidated and everything starts over at the next unlock.

---

## The password layers

### 1st — System lock
The PIN, pattern, password or fingerprint. ShieldLock does not manage this lock, the system does.

### 2nd — Time PIN
The PIN is **the current time** written without leading zeros. It changes every minute, so there is no fixed key that someone can spy on.

| Time | Format | PIN |
|------|---------|-----|
| 1:20 | 12 h | `120` |
| 12:30 | 12 h | `1230` |
| 13:05 | 24 h | `1305` |

The **previous minute** is also accepted, so you don't fail if the clock changes while you are typing.

### 3rd — Own password (optional)
- Between **6 and 15 characters**.
- It is stored as a **PBKDF2 hash with a random salt**; never in plain text.
- To **change or remove it** you must confirm the current one.
- After **3 failed attempts** the phone locks (`lockNow()`).

#### Recovery PIN
When you save the 3rd password, a **12-character random recovery PIN** is generated (the alphabet excludes `0`, `O`, `1`, `I`, `L` to avoid confusion). 

> ⚠️ It is shown **only once**. Write it down on paper or keep it in a safe place. If you forget your password and lose this PIN, there is no way to recover it.

### 4th — Calculator
See [Calculator password](#calculator-password-4th-layer).

### Combination modes

| Mode | Active layers |
|------|---------------|
| **Double** | System + time PIN |
| **Triple** | System + time PIN + own password |
| **With calculator** | The above + calculator (before or after the PIN) |

---

## Protection modes

When you open the app you choose between two modes. It also calculates in real time your **protection level** based on the permissions you have granted.

### Standard mode
Uses Device Administrator, Accessibility and Overlay. Simpler, no advanced tools needed.

### Maximum mode
For advanced users. Uses **Device Owner**, and optionally root or Shizuku to reinforce protection and repair permissions automatically.

### Adaptive reinforcements

- **Launch with privileges:** if Android blocks opening the ShieldLock screen from the background, the app opens it with `am start` using Shizuku or root. Active in `ESTANDAR_SHIZUKU`, `MAXIMO_OWNER_ROOT` and `MAXIMO_ADMIN_ROOT`.
- **Hide navigation** *(experimental)*: hides navigation buttons and gestures while the lock is active. Only in `MAXIMO_ADMIN_ROOT` and only if you enable it.

---

## Permissions it needs and what for

| Permission | What it is used for |
|---------|-----------------|
| **Device Administrator** | Lock the screen (`lockNow()`) after an incorrect attempt. |
| **Accessibility** | Close the notification panel and reopen ShieldLock if the user tries to get out. |
| **Overlay (`SYSTEM_ALERT_WINDOW`)** | Show the PIN screen over other apps. |
| **Ignore battery optimization** | Prevent the system from closing the service in the background. |
| **Notifications (`POST_NOTIFICATIONS`)** | Show the foreground service notification. |
| **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Keep unlock detection active. |
| **Start on boot (`RECEIVE_BOOT_COMPLETED`)** | Reactivate protection when the phone restarts. |

The app **does not ask for** internet access. First, because it **does not collect any personal data**; second, because it **works 100% offline** and never needs to contact a server.

---

## Shizuku and root

Both are **optional** and serve to keep ShieldLock healthy on its own.

### What they do
- **Complete permissions automatically:** grants whatever is missing (administrator, accessibility, battery saver, etc.) using `dpm`, `settings`, `appops` and `pm` commands. It runs on unlock, on boot and when accessibility changes.
- **Self-repair:** if someone disables the accessibility service, ShieldLock enables it again.
- **Launch with privileges** of the lock screen.
- **Enable or remove Device Owner without a PC.**

### Shizuku
Compatible variants are detected:
- Shizuku (`moe.shizuku.privileged.api`)
- Shizuku+ (`af.shizuku.plus.api`)
- **Shizuku Next** (fork that keeps the official package, detected the same way)

> Install **only one** of the variants.

The app guides you: if Shizuku is not installed it offers to download it; if it is stopped, it takes you to open it; if the permission is missing, it requests it.

### Root
If you have root, ShieldLock detects it and verifies it when you press **Verify**. Your root manager (Magisk, KernelSU, etc.) will ask you for confirmation.

---

## Device Owner

It is the highest level of integration with Android and enables exclusive features (such as the calculator).

### Enable from the app (no PC)
In the Device Owner row, the **"Enable with Shizuku"** or **"Enable with root"** button appears.

**Phone requirements:**
- There must be no **accounts** set up (Google, Samsung, etc.) at the time of enabling it.
- A single user on the device.
- No other Device Owner active.

The app translates common errors (accounts, multiple users, another Device Owner) into clear messages.

### Enable with ADB (alternative)
```bash
adb shell dpm set-device-owner com.shieldlock.auth/.AdminReceiver
```

### Remove it
**"Remove"** button in the same row (asks for the 3rd password if there is one). Until you enable it again, the features that require it remain disabled.

> ⚠️ Device Owner is a powerful permission. Enable it only if you understand what it implies and have a way to remove it.

---

## Calculator password (4th layer)

A retro calculator (greenish-gray LCD screen, raised keys) that works as a password. **Only available with Device Owner.** It uses its own keyboard: the Android keyboard does not appear.

**Keys:** digits, `.`, `±`, `%`, `√`, `MC`, `MR`, `MS`, `⌫`, `C`, `+`, `−`, `×`, `÷`, `=`

### Two password modes

**Key sequence**
You must press exactly the chosen sequence. You can type it yourself or generate it randomly (8 characters).

**Operation with exact result**
You choose a target number and any calculation whose result is **exactly** that number is valid (minimum 1 operation). For example, if the target is `42`: `6×7=`, `50−8=` or `84÷2=`. The calculation uses `BigDecimal`, so there are no rounding errors.

### Behavior
- A single incorrect `=` in real use locks the phone.
- It can go **before or after** the time PIN.
- There is a **test mode** (does not lock on a mistake) so you can practice without risk.
- To change it you must confirm the current one.

---

## Additional features

- ** Alert song:** choose a song from your device that plays in a loop on the lock screen. Access to the file is kept persistently.
- ** Language selector:** button always visible at the top. On first launch the app asks you for the language before anything else.
- ** User guide:** 12 sections inside the app explaining each feature.
- ** Notification panel blocked:** it closes automatically (Android 12+ with accessibility global action; Android 11 or earlier with `ACTION_CLOSE_SYSTEM_DIALOGS`).
- ** Automatic restart:** the service starts again when the phone restarts.
- ** Donate:** shows a donation code inside the app.

---

## Security and privacy

| Measure | Detail |
|--------|---------|
| **PBKDF2 hashes** | Passwords are stored with a random salt; never in plain text. |
| **No internet** | There is no `INTERNET` permission; nothing leaves your phone. |
| **No backups** | `android:allowBackup="false"`: your data is not uploaded to the cloud or restored on another device. |
| **Anti-screenshot** | `FLAG_SECURE` on all screens and dialogs: screenshots and screen recording are not possible. |
| **Attempt lock** | 3 failures on the 3rd password, or one incorrect `=` on the calculator, lock the phone. |
| **Self-repair** | If someone removes a key permission, the app tries to restore it (with Shizuku/root). |

> ℹ️ ShieldLock adds a barrier, but no software is infallible. An attacker with physical access, debugging tools or recovery mode may have more options. Use it as a complement, not as your only defense.

---

## Installation and use

1. Download and install the APK.
2. Open the app and **choose your language**.
3. **Choose the mode** (Standard or Maximum).
4. Grant the permissions the app indicates (you can follow the list; it updates itself).
5. Set up your passwords in **"Passwords"**:
   - Double (time PIN only) or Triple (adds your password).
   - If you choose Triple, **write down the recovery PIN**.
6. (Optional) Choose an **alert song**.
7. Use **"Test lock screen"** to see how it works without risk.
8. Enable the protection and lock/unlock the phone to check it.

### Recommendations before using it daily
- Test the lock screen several times.
- Write down the recovery PIN on paper.
- Verify that the protection survives a restart.
- Disable battery optimization for ShieldLock.

---

## Building the project

### Requirements
- Android Studio (recent version)
- JDK 17 or higher
- Android SDK 37

### Key dependencies
```kotlin
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

### Important `build.gradle.kts` settings
```kotlin
android {
    namespace = "com.shieldlock.auth"
    buildFeatures { aidl = true }   // REQUIRED for IShellService
    defaultConfig { minSdk = 24; targetSdk = 37 }
}
```
> `buildFeatures { aidl = true }` goes **directly inside `android { }`**, not inside `compileSdk { }`.

### Correct location of resources

| File | Folder | Final name |
|---------|---------|--------------|
| `activity_main.xml` | `res/layout/` | same |
| `activity_lock.xml` | `res/layout/` | same |
| `device_admin.xml` | `res/xml/` | same |
| `accessibility_config.xml` | `res/xml/` | same |
| `locales_config.xml` | `res/xml/` | same |
| Texts in English (default) | `res/values/` | `strings.xml` |
| Texts in Spanish | `res/values-es/` | `strings.xml` |
| Texts in Portuguese | `res/values-pt/` | `strings.xml` |
| Texts in Chinese | `res/values-zh/` | `strings.xml` |
| `IShellService.aidl` | `app/src/main/aidl/com/shieldlock/auth/` | same |
| `dona.png` | `res/drawable/` | same |

**Common build errors**

| Error | Cause | Solution |
|-------|-------|----------|
| `Can't determine type for tag '<locale ...>'` | `locales_config.xml` is in `res/values/` | Move it to `res/xml/` |
| `Duplicate resources` | Several `strings_*.xml` inside `res/values/` | One `strings.xml` per `values-<language>` folder |
| `ShellService` / `ShizukuShell` fails | The AIDL is missing or `aidl = true` is not set | Add the `.aidl` and enable `buildFeatures` |

---

## Known limitations

To be transparent with whoever uses the app:

- **The calculator is in its first stage.** It works and can be configured and tested, but it is not yet connected to the real unlock flow of the protection service.
- **There is no kiosk mode yet** (`setLockTaskPackages` / `startLockTask`).
- **Do Not Disturb** during the calculator is not implemented yet.
- **The user guide** (`GuiaActivity`) has its texts in Spanish only.
- **Hide navigation** is experimental and only works with root.
- **Some manufacturers** (Xiaomi, Huawei, Samsung, etc.) have aggressive battery savers that can close the service. Disable battery optimization and allow auto-start for ShieldLock.
- **Android may restrict opening screens from the background.** Without Shizuku or root, ShieldLock depends on overlay and accessibility.
- **Release builds:** ProGuard/R8 rules for `ShellService` are missing.
- **Dhizuku** is not supported.
- To test on your device: restart with the service reactivated, notification panel on Android 12+, and recovery PIN on the lock screen.

---

## Donations

you can support its development from the **"Donate"** button inside the app.

---

## 📄 License

ShieldLock is distributed under the **ShieldLock License v1.0** (full text in the [`LICENSE`](LICENSE) file). Summary:

| ✅ Allowed | ❌ Not allowed |
|-------------|----------------|
| Use, copy and modify the code | Publish the code or a derived app on **Google Play Store** |
| Use it in other projects, including commercial ones | Present the code as your own creation |
| Distribute on F-Droid, IzzyOnDroid, other stores and websites | Remove the license notice from copies of the source code |

**Mandatory attribution:** every project that uses ShieldLock code must explicitly mention that this code comes from this project and is not the work of whoever distributes it, with a link to the original repository, in a visible place (README, "About" screen or the app's description on the store or website).

> Example: *"Part of this app's code comes from ShieldLock https://github.com/designer8GT9/Android-ShieldLock, created by designer8GT9."*

This is a custom license and **is not an OSI-approved open source license**, because of the Google Play distribution restriction. Third-party dependencies keep their own licenses (for example, [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)).

---

<sub>⚠️ ShieldLock is a personal security tool. I am not responsible for loss of access to the device, data loss or misuse. Use it at your own risk and always test before relying on it.</sub>
