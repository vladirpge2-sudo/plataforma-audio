# Voz em Camadas — Android

Aplicativo Android nativo/híbrido que abre o Voz em Camadas e adiciona captura real de:

- áudio interno do aparelho;
- microfone;
- áudio interno + microfone.

## Como funciona

A interface, a conta, o histórico, a transcrição estrita e as exportações continuam no aplicativo web. O Android fornece uma ponte nativa para `MediaProjection` e `AudioPlaybackCapture`, grava em AAC/M4A e entrega o arquivo ao fluxo de transcrição.

## Requisitos

- Android 10 ou superior para áudio interno;
- autorização do usuário para projeção de mídia;
- permissão de microfone;
- internet para abrir o Voz em Camadas e processar o áudio.

## Limites do Android

O Android só permite capturar reprodução classificada como mídia, jogo ou uso desconhecido. O aplicativo que produz o áudio pode bloquear a captura. Chamadas e conteúdos protegidos podem resultar em silêncio; o Voz em Camadas informa isso em vez de enviar um arquivo vazio.

## Compilar

```bash
gradle :app:assembleDebug
```

O APK fica em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

O workflow `Build Android APK` também compila e publica o APK como artefato do GitHub Actions.
