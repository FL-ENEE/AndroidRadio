# Civic Launcher

Launcher Android (Kotlin + WebView) para la radio WJ_01 (Android 13).

## Compilar el APK
**Opción A, sin instalar nada (GitHub):** crea un repositorio, sube todo este proyecto,
abre la pestaña Actions > "Build APK" > Run workflow. Al terminar, descarga el artefacto
`civic-launcher-apk` (contiene app-debug.apk).

**Opción B, Android Studio:** File > Open (esta carpeta) > Build > Build APK(s).
Resultado: app/build/outputs/apk/debug/app-debug.apk

## Instalar en la radio
1. Copia el APK a una memoria USB e instálalo desde el explorador de archivos de la radio.
2. Abre la app, acepta el permiso de ubicación (GPS).
3. Ajustes de Android > Apps > Civic Launcher > menú de tres puntos > "Permitir ajustes restringidos".
4. Pulsa "Dar acceso" en el launcher y activa Civic Launcher en acceso a notificaciones.
5. Al pulsar Home, elige Civic Launcher como pantalla de inicio.

## Pendiente
- Poner el paquete de BT Music en MainActivity.kt (Sources.BT_MUSIC_PKG).
- La señal de "luces encendidas" se toma del modo noche del sistema (uiMode). Si tu radio
  no lo cambia al encender luces, hay que escuchar el aviso propio del fabricante.
