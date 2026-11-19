---
name: probar-movil
description: Compilar, firmar e instalar la APK phone de Oto en el Nothing de Iago, o probar el enlace PC↔móvil sin tocar sus datos. Usar con «instálalo en el móvil», «prueba en el Nothing», «APK phone».
---

# Probar Oto en el móvil

Contexto completo en `docs/agents/android-movil.md`.

## Compilar `phone`

```powershell
. ..\env.ps1
$env:ANDROID_HOME = (Resolve-Path ..\tools\android-sdk).Path
$env:OTO_PHONE_KEYSTORE = (Resolve-Path ..\tools\signing\oto-mobile-linux.keystore).Path
.\gradlew.bat :app:assemblePhone :app:testDebugUnitTest --offline --no-configuration-cache
```

APK: `app/build/outputs/apk/phone/app-phone.apk`.

## Antes de instalar

Con `apksigner` y `aapt2`: paquete `io.github.iaguito22.oto.debug`, certificado SHA-256 `717d8f89…cbbc`, **no** depurable.

## Instalar

- `adb -s <dispositivo> install -r`. Nunca desinstalar la app habitual para arreglar una firma.
- La cola en memoria se pierde al reinstalar. Si importa, guardarla antes con la APK `.linktest` (`capture-habitual` / `restore-habitual`).
- Tras instalar, abrir primero la `MainActivity` habitual.

## Probar sin tocar datos reales

- `-PotoLinkTest` crea la variante `.linktest`. Nada de `connectedAndroidTest` en el móvil real.
- Integración real opcional: `OTO_LINK_TEST_ADB` y `OTO_LINK_TEST_SERIAL` (`MobileLikeIntegrationTest`, `MobileQueueIntegrationTest`).
- La IP y el puerto del móvil cambian. No escanear la red ni tocar otros dispositivos.

## Informar

SHA-256 de la APK instalada, firma verificada, qué se probó en el teléfono y qué queda «sin oír» o sin probar.
