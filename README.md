# TikTok TV (Sleppify)

Wrapper no oficial de TikTok para **Android TV / Google TV**, optimizado para control remoto. Nace porque TikTok descontinuó su app oficial de TV en junio de 2025.

## Características
- **Navegación D-Pad**: arriba/abajo para el feed
- **Puntero virtual**: mantén **OK** pulsado para activar un cursor con inercia y clicar cualquier elemento.
- **Arranque ligero**: bloqueo de telemetría/anuncios y pantalla de carga que espera a que el vídeo esté listo.

## Requisitos
Android TV / Google TV con **Android 8.0 (API 26)** o superior.

## Instalación
- **Rápida**: descarga el APK desde [Releases](../../releases) e instálalo (activa "orígenes desconocidos").
- **Compilar**: clona el repositorio, ábrelo en Android Studio y despliega en tu dispositivo Android TV.


## Estructura
- `app/src/main/java/.../MainActivity.kt` — WebView, control remoto, puntero virtual y bloqueo de trackers.
- `app/src/main/assets/tiktok_tv_bridge.js` — puente JS: navegación, cierre de modales y ajuste del vídeo.
- `app/src/main/java/.../TikTokLoaderView.kt` — animación de la pantalla de carga.

## Aviso
Proyecto personal no oficial, sin afiliación con TikTok. Úsalo bajo tu responsabilidad.

