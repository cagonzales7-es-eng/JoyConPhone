# Joy-Con Phone

App Android (Kotlin + Jetpack Compose) que convierte el teléfono en un Joy-Con individual
(izquierdo o derecho): botones táctiles, stick virtual y giroscopio/acelerómetro.

Dos modos de conexión:
- **Wi-Fi (servidor PC)**: recomendado. Usa `server/joycon_server.py` en la PC (DSU/Cemuhook + mando Xbox virtual opcional).
- **Bluetooth HID**: el teléfono se anuncia como «Joy-Con». Experimental (Android no deja fijar el VID/PID).

## 1. Compilar la app (APK)

### Opción A: Android Studio
1. Instala Android Studio (https://developer.android.com/studio). Incluye JDK y SDK.
2. File > Open > elige la carpeta `JoyConPhone` (la que tiene `settings.gradle.kts`).
3. Si pide instalar "Android SDK Platform 34" o aceptar licencias, acepta. Espera a que termine el Gradle sync (barra inferior).
4. Menú Build > Build Bundle(s) / APK(s) > Build APK(s). Al terminar, pulsa "locate" en la notificación.
5. El archivo es `app/build/outputs/apk/debug/app-debug.apk`.

### Opción B: GitHub Actions (sin instalar nada)
1. Crea un repositorio en github.com y sube TODO el contenido de esta carpeta (rama `main`; la carpeta `.github` debe subirse).
2. Pestaña Actions > "Build APK" (se ejecuta sola al subir; si no, "Run workflow").
3. Al terminar (verde), entra a la ejecución y descarga el artefacto `JoyConPhone-debug-apk`.
4. Descomprime y obtendrás `app-debug.apk`.

### Instalar
Pasa el APK al teléfono (cable, Drive, etc.), ábrelo y permite "Instalar apps desconocidas".

## 2. Servidor en la PC (modo Wi-Fi)

Requisitos: Python 3.8+.

    cd server
    python joycon_server.py            # solo DSU (no necesita instalar nada)
    python joycon_server.py --vpad     # + mando Xbox 360 virtual (Windows)
    python joycon_server.py -v         # muestra lo que llega del teléfono

Para `--vpad`: instala ViGEmBus (https://github.com/nefarius/ViGEmBus/releases) y `pip install vgamepad`.

Pasos:
1. PC y teléfono en la misma red Wi-Fi (mejor 5 GHz). Permite UDP 26800 en el firewall de Windows si pregunta.
2. Ejecuta el servidor; muestra la IP de la PC.
3. En la app: modo "Wi-Fi", escribe esa IP, elige Joy-Con izquierdo o derecho.
4. En Citron/Yuzu: Configurar > Controles > (Jugador) > configuración de Movimiento/Táctil (Motion/Touch):
   activa el servidor UDP en 127.0.0.1 puerto 26760 y prueba el movimiento.
   Los botones llegan por DSU (aparece como dispositivo "DSU"/Cemuhook) o por el mando Xbox virtual si usas `--vpad`.

## 3. Ajustar el movimiento
Si el movimiento sale invertido, usa en el servidor `--flip-gyro` (p, y, r) y/o `--flip-acc` (x, y, z),
por ejemplo `--flip-gyro yr`. Para el stick invertido: `--invert-y`.
