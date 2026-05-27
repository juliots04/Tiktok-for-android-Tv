# 📱 TikTok TV (Sleppify)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20TV-3DDC84?logo=android&logoColor=white&style=for-the-badge" alt="Android TV Badge" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%2F%20JavaScript-7F52FF?logo=kotlin&logoColor=white&style=for-the-badge" alt="Kotlin & JS Badge" />
  <img src="https://img.shields.io/badge/Gradle-9.3.1-02303A?logo=gradle&logoColor=white&style=for-the-badge" alt="Gradle Badge" />
  <img src="https://img.shields.io/badge/Tech-WebView%20Wrapper-FF5722?logo=googlechrome&logoColor=white&style=for-the-badge" alt="WebView Badge" />
</p>

Un wrapper de **TikTok** optimizado para **Android TV** que lleva la experiencia completa de escritorio a la pantalla grande. Diseñado específicamente para resolver las limitaciones de navegación, la falta de soporte de control remoto, las restricciones de inicio de sesión y los problemas de renderizado en Smart TVs.

---

## 🚀 Características Principales

### 🎮 Soporte Avanzado para Control Remoto (D-Pad)
- **Navegación Fluida del Feed**: Permite desplazarse verticalmente por los vídeos (Arriba/Abajo) simulando las acciones de reproducción nativas de TikTok con tu control de tv!.

### 🖱️ Mouse Virtual de Alta Precisión (Estilo Magic Remote)
- **Activación por Pulsación Larga**: Mantén presionado el botón **OK/Enter** durante **800 ms** para alternar el cursor virtual en pantalla.
- **Física de Movimiento Avanzada**: Implementación en Kotlin con aceleración progresiva, fricción realista y parada suave para un control preciso usando el D-Pad del control remoto.
- **Simulación de Pulsaciones de Pantalla**: Al presionar el botón **OK**, se despachan eventos táctiles nativos del sistema en las coordenadas actuales del cursor para interactuar con botones no enfocables.
- **Feedback Visual premium**: Cursor en color rosa LG (`#FA1859`) con sombreado y animación de escala al hacer clic.

### 🖼️ Optimizaciones de Diseño y Pantalla (CSS Shield)
- **Forzado de Interfaz de Escritorio (Desktop UI)**: Modifica el `UserAgent` para simular un navegador de escritorio, cargando el reproductor completo y esquivando la versión web móvil recortada.
- **Protección contra Pantallas Negras**: Inyecciones CSS que evitan fallos de aceleración de hardware en Android TV al remover bordes redondeados y controlar las proporciones de visualización (`object-fit: contain`).
- **Control de Dimensiones Viewport**: Asegura un ancho de pantalla virtual consistente (`width=1280`) para evitar que la barra lateral y los botones de control se corten en pantallas con bajas densidades de píxeles (DPI).

### 🔐 Gestión del Flujo de Autenticación
- **Solución al Bloqueo de Google OAuth**: Google bloquea los inicios de sesión desde WebViews incrustados por seguridad. Sleppify intercepta estos intentos y ofrece un flujo de diálogo amigable que redirige al usuario a métodos alternativos seguros y compatibles (correo/contraseña, número telefónico, o redes sociales asociadas).

### 📊 Diagnóstico y Rendimiento Automatizado
- **DOM Dumper integrado**: El puente de JavaScript recopila métricas críticas (tiempo de carga de página, interactividad del DOM, uso del Heap de JS) y propiedades CSS del layout para guardarlas localmente (`dom_dump.html`) permitiendo el debug a través de comandos ADB.

---

## 🎮 Mapeo del Control Remoto

| Botón del Control | Modo Normal | Modo Mouse Virtual (Cursor) |
| :--- | :--- | :--- |
| **D-Pad Arriba** | Siguiente Vídeo (Scroll Up) | Mover cursor hacia arriba (con aceleración) |
| **D-Pad Abajo** | Vídeo Anterior (Scroll Down) | Mover cursor hacia abajo (con aceleración) |
| **OK / Enter (Pulsación Corta)** | Reproducir / Pausar vídeo | Simular clic táctil en las coordenadas del cursor |
| **OK / Enter (Pulsación Larga)** | Activar / Desactivar Mouse Virtual | Activar / Desactivar Mouse Virtual |
| **Atrás (Back)** | Cerrar modales activos (Ventanas emergentes) / Salir | Desactivar cursor virtual / Volver |

---

## 🛠️ Arquitectura del Proyecto

El proyecto está diseñado bajo una arquitectura limpia que combina las APIs nativas de Android con inyecciones de código en el cliente web de TikTok:

```
TikTok TV (Sleppify)
│
├── app/src/main/java/.../MainActivity.kt ────> Maneja el ciclo de vida de WebView, eventos físicos del D-Pad y la simulación del Mouse Virtual en Kotlin.
│
├── app/src/main/assets/tiktok_tv_bridge.js ──> Inyecta reglas CSS correctivas, intercepta el scroll del feed y automatiza métricas de rendimiento.
│
└── app/src/main/res/layout/activity_main.xml ──> Define el contenedor a pantalla completa con las capas de carga animada (Dots) y pantalla de error de red.
```

---

## ⚙️ Requisitos e Instalación

### Requisitos de Desarrollo
- **Android Studio** Jellyfish o posterior.
- **JDK 11** configurado en el sistema.
- **Android SDK** compatible con API 26 (Android 8.0 Oreo) en adelante.
- Dispositivo Android TV o Emulador con la tienda de Google Play (para actualizaciones de WebView).

### Compilación y Despliegue
1. Clona este repositorio:
   ```bash
   git clone https://github.com/juliots21/TikTok-for-android-tv.git
   ```
2. Abre el proyecto en **Android Studio**.
3. Sincroniza los archivos de Gradle (`build.gradle.kts`).
4. Conecta tu dispositivo Android TV mediante ADB (ajustes de desarrollador -> depuración por red/USB).
5. Selecciona el dispositivo en la barra superior y presiona **Run** (`Shift + F10`).

---

## 💡 Preguntas Frecuentes (FAQ)

### ❓ ¿Por qué no puedo iniciar sesión con mi cuenta de Google?
Google no permite iniciar sesión mediante OAuth dentro de componentes de tipo `WebView` en aplicaciones no oficiales para prevenir el phishing. Para entrar a tu cuenta de TikTok en Sleppify, por favor utiliza las opciones de **correo electrónico y contraseña**, **código de teléfono**, o inicia sesión vinculando tu cuenta con **Facebook, Twitter o Apple**.

### ❓ El vídeo se muestra en negro pero se escucha el audio, ¿cómo se soluciona?
Este es un problema común en algunos dongles o decodificadores Android TV de gama baja con decodificación de vídeo acelerada por hardware defectuosa. Sleppify incluye reglas CSS inyectadas para remover los bordes redondeados (`border-radius: 0`) y forzar traducción Z (`transform: translateZ(0)`) en las etiquetas de vídeo, solucionando este fallo en el 95% de los dispositivos. Si persiste, asegúrate de tener actualizada la aplicación **Android System WebView** desde Google Play Store.

### ❓ ¿Cómo extraigo los reportes de rendimiento generados?
El wrapper genera un reporte de rendimiento a los 7 segundos de iniciar sesión o cargar el feed. Puedes extraer este archivo de diagnóstico a tu PC usando el siguiente comando de ADB:
```bash
adb pull /sdcard/Android/data/com.example.tiktokxsleppify/files/dom_dump.html
```

---

Creado con ❤️ por [juliots21](https://github.com/juliots21)
