# ShieldLock

**Capas de seguridad extra para tu Android, después del bloqueo del sistema.**

ShieldLock agrega verificaciones adicionales *después* de que desbloqueas tu teléfono con el PIN, patrón o huella de Android. Si alguien consigue pasar tu bloqueo normal, todavía tiene que superar las capas de ShieldLock.

- **Paquete:** `com.shieldlock.auth`
- **Lenguaje:** Kotlin · Material 3
- **minSdk:** 24 (Android 7.0) · **targetSdk:** 37
- **Idiomas:** Español, English, Português, 中文
- **Sin conexión a internet:** la app no solicita el permiso `INTERNET`; todo se procesa y guarda en tu dispositivo.

---

## ¿Cómo funciona?

ShieldLock corre un servicio en primer plano que detecta cuándo desbloqueas el teléfono. En ese momento muestra su propia pantalla de bloqueo por encima de todo y no te deja usar el teléfono hasta que superes las capas que configuraste.

```
Pantalla de bloqueo de Android (1ª)
        ↓
PIN de la hora actual (2ª)
        ↓
Tu contraseña propia (3ª, opcional)
        ↓
Calculadora secreta (4ª, solo con Device Owner)
        ↓
Acceso al teléfono
```

Cuando la pantalla se apaga, la verificación se invalida y todo vuelve a empezar al siguiente desbloqueo.

---

## Las capas de contraseña

### 1ª — Bloqueo del sistema
el PIN, patrón, contraseña o huella dactilar. ShieldLock no gestiona este Bloqueo, el Sistema si.

### 2ª — PIN de la hora
El PIN es **la hora actual** escrita sin ceros a la izquierda. Cambia cada minuto, así que no hay una clave fija que alguien pueda espiar.

| Hora | Formato | PIN |
|------|---------|-----|
| 1:20 | 12 h | `120` |
| 12:30 | 12 h | `1230` |
| 13:05 | 24 h | `1305` |

También se acepta el **minuto anterior**, para que no falles si el reloj cambia mientras escribes.

### 3ª — Contraseña propia (opcional)
- Entre **6 y 15 caracteres**.
- Se guarda como **hash PBKDF2 con sal aleatoria**; nunca en texto plano.
- Para **cambiarla o quitarla** hay que confirmar la actual.
- Tras **3 intentos fallidos** el teléfono se bloquea (`lockNow()`).

#### PIN de recuperación
Al guardar la 3ª contraseña se genera un **PIN de recuperación de 12 caracteres aleatorios** (el alfabeto excluye `0`, `O`, `1`, `I`, `L` para evitar confusiones). 

> ⚠️ Se muestra **una sola vez**. Anótalo en papel o guárdalo en un lugar seguro. Si olvidas tu contraseña y pierdes este PIN, no hay forma de recuperarla.

### 4ª — Calculadora
Ver [Contraseña de calculadora](#-contraseña-de-calculadora-4ª-capa).

### Modos de combinación

| Modo | Capas activas |
|------|---------------|
| **Doble** | Sistema + PIN de la hora |
| **Triple** | Sistema + PIN de la hora + contraseña propia |
| **Con calculadora** | Lo anterior + calculadora (antes o después del PIN) |

---

## Modos de protección

Al abrir la app eliges entre dos modos. además calcula en tiempo real tu **nivel de protección** según los permisos que tengas concedidos.

### Modo Estándar
Usa Administrador de dispositivo, Accesibilidad y Superposición. Más simple, sin necesidad de herramientas avanzadas.

### Modo Máximo
Para usuarios avanzados. Usa **Device Owner**, y opcionalmente root o Shizuku para reforzar la protección y reparar permisos automáticamente.

### Refuerzos adaptativos

- **Lanzar con privilegios:** si Android bloquea abrir la pantalla de ShieldLock desde segundo plano, la app la abre con `am start` usando Shizuku o root. Activo en `ESTANDAR_SHIZUKU`, `MAXIMO_OWNER_ROOT` y `MAXIMO_ADMIN_ROOT`.
- **Ocultar navegación** *(experimental)*: oculta botones y gestos de navegación mientras el bloqueo está activo. Solo en `MAXIMO_ADMIN_ROOT` y solo si lo activas tú.

---

## Permisos que necesita y para qué

| Permiso | Para qué se usa |
|---------|-----------------|
| **Administrador de dispositivo** | Bloquear la pantalla (`lockNow()`) tras un intento incorrecto. |
| **Accesibilidad** | Cerrar el panel de notificaciones y volver a abrir ShieldLock si el usuario intenta salirse. |
| **Superposición (`SYSTEM_ALERT_WINDOW`)** | Mostrar la pantalla de PIN sobre otras apps. |
| **Ignorar optimización de batería** | Evitar que el sistema cierre el servicio en segundo plano. |
| **Notificaciones (`POST_NOTIFICATIONS`)** | Mostrar la notificación del servicio en primer plano. |
| **Servicio en primer plano** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`) | Mantener la detección de desbloqueo activa. |
| **Iniciar al arrancar (`RECEIVE_BOOT_COMPLETED`)** | Reactivar la protección al reiniciar el teléfono. |

La app **no pide** acceso a internet. Primero, porque **no recopila ningún dato personal**; segundo, porque **funciona 100% offline** y nunca necesita contactar un servidor.

---

## Shizuku y root

Ambos son **opcionales** y sirven para que ShieldLock se mantenga sano por sí solo.

### Qué hacen
- **Completar permisos automáticamente:** concede lo que falte (administrador, accesibilidad, ahorro de batería, etc.) mediante comandos `dpm`, `settings`, `appops` y `pm`. Se ejecuta al desbloquear, al arrancar y al cambiar la accesibilidad.
- **Autorreparación:** si alguien desactiva el servicio de accesibilidad, ShieldLock lo vuelve a activar.
- **Lanzamiento con privilegios** de la pantalla de bloqueo.
- **Activar o quitar Device Owner sin PC.**

### Shizuku
Se detectan las variantes compatibles:
- Shizuku (`moe.shizuku.privileged.api`)
- Shizuku+ (`af.shizuku.plus.api`)
- **Shizuku Next** (fork que mantiene el paquete oficial, se detecta igual)

> Instala **solo una** de las variantes.

La app te guía: si Shizuku no está instalado ofrece descargarlo; si está detenido, te lleva a abrirlo; si falta el permiso, lo solicita.

### Root
Si tienes root, ShieldLock lo detecta y lo verifica cuando pulsas **Verificar**. Tu gestor de root (Magisk, KernelSU, etc.) te pedirá confirmación.

---

## Device Owner

Es el nivel más alto de integración con Android y habilita funciones exclusivas (como la calculadora).

### Activar desde la app (sin PC)
En la fila de Device Owner aparece el botón **"Activar con Shizuku"** o **"Activar con root"**.

**Requisitos del teléfono:**
- No debe haber **cuentas** configuradas (Google, Samsung, etc.) en el momento de activarlo.
- Un solo usuario en el dispositivo.
- Ningún otro Device Owner activo.

La app traduce los errores comunes (cuentas, varios usuarios, otro Device Owner) a mensajes claros.

### Activar con ADB (alternativa)
```bash
adb shell dpm set-device-owner com.shieldlock.auth/.AdminReceiver
```

### Quitarlo
Botón **"Quitar"** en la misma fila (pide la 3ª contraseña si existe). Mientras no lo vuelvas a activar, las funciones que lo requieren quedan deshabilitadas.

> ⚠️ Device Owner es un permiso poderoso. Actívalo solo si entiendes lo que implica y tienes un método para quitarlo.

---

## Contraseña de calculadora (4ª capa)

Una calculadora retro (pantalla LCD verde-grisácea, teclas con relieve) que funciona como contraseña. **Solo disponible con Device Owner.** Usa su propio teclado: no aparece el teclado de Android.

**Teclas:** dígitos, `.`, `±`, `%`, `√`, `MC`, `MR`, `MS`, `⌫`, `C`, `+`, `−`, `×`, `÷`, `=`

### Dos modos de contraseña

**Secuencia de teclas**
Debes pulsar exactamente la secuencia elegida. Puedes escribirla tú o generarla al azar (8 caracteres).

**Operación con resultado exacto**
Eliges un número objetivo y cualquier cálculo cuyo resultado sea **exactamente** ese número es válido (mínimo 1 operación). Por ejemplo, si el objetivo es `42`: `6×7=`, `50−8=` o `84÷2=`. El cálculo usa `BigDecimal`, así que no hay errores de redondeo.

### Comportamiento
- Un solo `=` incorrecto en uso real bloquea el teléfono.
- Puede ir **antes o después** del PIN de la hora.
- Hay un **modo de prueba** (no bloquea ante un error) para que practiques sin riesgo.
- Para cambiarla hay que confirmar la actual.

---

## Funciones adicionales

- ** Canción de alerta:** elige una canción de tu dispositivo que suena en bucle en la pantalla de bloqueo. El acceso al archivo se conserva de forma persistente.
- ** Selector de idioma:** botón siempre visible arriba. Al primer inicio la app te pregunta el idioma antes de nada.
- ** Guía del usuario:** 12 secciones dentro de la app explicando cada función.
- ** Panel de notificaciones bloqueado:** se cierra automáticamente (Android 12+ con acción global de accesibilidad; Android 11 o anterior con `ACTION_CLOSE_SYSTEM_DIALOGS`).
- ** Reinicio automático:** el servicio vuelve a arrancar al reiniciar el teléfono.
- ** Donar:** muestra un código de donación dentro de la app.

---

## Seguridad y privacidad

| Medida | Detalle |
|--------|---------|
| **Hashes PBKDF2** | Las contraseñas se guardan con sal aleatoria; nunca en texto plano. |
| **Sin internet** | No hay permiso `INTERNET`; nada sale de tu teléfono. |
| **Sin copias de seguridad** | `android:allowBackup="false"`: tus datos no se suben a la nube ni se restauran en otro equipo. |
| **Anti-capturas** | `FLAG_SECURE` en todas las pantallas y diálogos: no se pueden hacer capturas ni grabar pantalla. |
| **Bloqueo por intentos** | 3 fallos en la 3ª contraseña, o un `=` incorrecto en la calculadora, bloquean el teléfono. |
| **Autorreparación** | Si alguien quita un permiso clave, la app intenta restaurarlo (con Shizuku/root). |

> ℹ️ ShieldLock añade una barrera, pero ningún software es infalible. Un atacante con acceso físico, herramientas de depuración o modo recovery puede tener más opciones. Úsala como complemento, no como única defensa.

---

## Instalación y uso

1. Descarga e instala el APK.
2. Abre la app y **elige tu idioma**.
3. **Elige el modo** (Estándar o Máximo).
4. Concede los permisos que indique la app (puedes seguir la lista; se actualiza sola).
5. Configura tus contraseñas en **"Contraseñas"**:
   - Doble (solo PIN de la hora) o Triple (añade tu contraseña).
   - Si eliges Triple, **anota el PIN de recuperación**.
6. (Opcional) Elige una **canción de alerta**.
7. Usa **"Probar pantalla de bloqueo"** para ver cómo funciona sin riesgo.
8. Activa la protección y bloquea/desbloquea el teléfono para comprobarlo.

### Recomendaciones antes de usarla a diario
- Prueba la pantalla de bloqueo varias veces.
- Anota el PIN de recuperación en papel.
- Verifica que la protección sobrevive a un reinicio.
- Desactiva la optimización de batería para ShieldLock.

---

## Compilar el proyecto

### Requisitos
- Android Studio (versión reciente)
- JDK 17 o superior
- SDK de Android 37

### Dependencias clave
```kotlin
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

### Ajustes importantes de `build.gradle.kts`
```kotlin
android {
    namespace = "com.shieldlock.auth"
    buildFeatures { aidl = true }   // OBLIGATORIO para IShellService
    defaultConfig { minSdk = 24; targetSdk = 37 }
}
```
> `buildFeatures { aidl = true }` va **directamente dentro de `android { }`**, no dentro de `compileSdk { }`.

### Ubicación correcta de los recursos

| Archivo | Carpeta | Nombre final |
|---------|---------|--------------|
| `activity_main.xml` | `res/layout/` | igual |
| `activity_lock.xml` | `res/layout/` | igual |
| `device_admin.xml` | `res/xml/` | igual |
| `accessibility_config.xml` | `res/xml/` | igual |
| `locales_config.xml` | `res/xml/` | igual |
| Textos en inglés (por defecto) | `res/values/` | `strings.xml` |
| Textos en español | `res/values-es/` | `strings.xml` |
| Textos en portugués | `res/values-pt/` | `strings.xml` |
| Textos en chino | `res/values-zh/` | `strings.xml` |
| `IShellService.aidl` | `app/src/main/aidl/com/shieldlock/auth/` | igual |
| `dona.png` | `res/drawable/` | igual |

**Errores comunes de compilación**

| Error | Causa | Solución |
|-------|-------|----------|
| `Can't determine type for tag '<locale ...>'` | `locales_config.xml` está en `res/values/` | Moverlo a `res/xml/` |
| `Duplicate resources` | Varios `strings_*.xml` dentro de `res/values/` | Un `strings.xml` por carpeta `values-<idioma>` |
| Falla `ShellService` / `ShizukuShell` | Falta el AIDL o `aidl = true` | Añadir el `.aidl` y activar `buildFeatures` |

---

## Limitaciones conocidas

Para ser transparentes con quien use la app:

- **La calculadora está en su primera etapa.** Funciona y se puede configurar y probar, pero aún no está conectada al flujo de desbloqueo real del servicio de protección.
- **No hay modo kiosco todavía** (`setLockTaskPackages` / `startLockTask`).
- **No Molestar** durante la calculadora aún no está implementado.
- **La guía del usuario** (`GuiaActivity`) tiene sus textos solo en español.
- **Ocultar navegación** es experimental y solo funciona con root.
- **Algunos fabricantes** (Xiaomi, Huawei, Samsung, etc.) tienen ahorradores de batería agresivos que pueden cerrar el servicio. Desactiva la optimización de batería y permite el inicio automático para ShieldLock.
- **Android puede restringir abrir pantallas desde segundo plano.** Sin Shizuku o root, ShieldLock depende de la superposición y la accesibilidad.
- **Compilaciones release:** faltan reglas ProGuard/R8 para `ShellService`.
- **Dhizuku** no está soportado.
- Probar en tu dispositivo: reinicio con servicio reactivado, panel de notificaciones en Android 12+, y PIN de recuperación en la pantalla de bloqueo.

---

## Donaciones

puedes apoyar su desarrollo desde el botón **"Donar"** dentro de la app.

---

## 📄 Licencia

ShieldLock se distribuye bajo la **Licencia ShieldLock v1.0** (texto completo en el archivo [`LICENSE`](LICENSE)). Resumen:

| ✅ Permitido | ❌ No permitido |
|-------------|----------------|
| Usar, copiar y modificar el código | Publicar el código o una app derivada en **Google Play Store** |
| Usarlo en otros proyectos, incluso comerciales | Presentar el código como creación propia |
| Distribuir en F-Droid, IzzyOnDroid, otras tiendas y páginas web | Quitar el aviso de licencia de las copias del código fuente |

**Atribución obligatoria:** todo proyecto que use código de ShieldLock debe mencionar explícitamente que ese código proviene de este proyecto y no es obra de quien lo distribuye, con un enlace al repositorio original, en un lugar visible (README, pantalla "Acerca de" o descripción de la app en la tienda o sitio web).

> Ejemplo: *"Parte del código de esta app proviene de ShieldLock https://github.com/designer8GT9/Android-ShieldLock, creado por designer8GT9."*

Esta es una licencia personalizada y **no es una licencia de código abierto aprobada por la OSI**, por la restricción de distribución en Google Play. Las dependencias de terceros mantienen sus propias licencias (por ejemplo, [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)).

---

<sub>⚠️ ShieldLock es una herramienta de seguridad personal. no me hago responsable por pérdida de acceso al dispositivo, pérdida de datos o mal uso. Úsala bajo tu propia responsabilidad y prueba siempre antes de depender de ella.</sub>
