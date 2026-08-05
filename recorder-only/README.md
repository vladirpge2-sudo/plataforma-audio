# Gravador Interno

Aplicativo Android nativo e independente para capturar o áudio de reprodução permitido pelo Android 10 ou superior.

## Fluxo

1. Iniciar gravação.
2. Aceitar o diálogo oficial de captura do Android.
3. Reproduzir o áudio em outro aplicativo.
4. Encerrar pelo aplicativo ou pela notificação.
5. O M4A é salvo em `Downloads/Voz em Camadas`.
6. Abrir o Voz em Camadas e enviar o arquivo.

## Limitações do Android

A captura depende da política do aplicativo que reproduz o áudio. Chamadas, DRM e aplicativos que proíbem `AudioPlaybackCapture` podem resultar em silêncio.

## Build

```bash
gradle :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
