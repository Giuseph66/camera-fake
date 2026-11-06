# Arquitetura

```
 Fontes                       Motor GPU (thread GL própria)                      Saídas (1 thread + contexto EGL cada)
 ┌───────────────┐   OES      ┌──────────────────────────────────────────┐      ┌───────────────────────────┐
 │ PhysicalCamera│──Surface──▶│ entrada: espelho/rotação/enquadramento   │      │ preview (SurfaceView)     │
 │ VideoFile     │──Surface──▶│   ↓                                      │ glFin│ consumidor 1 (ImageReader)│
 │ StaticImage   │──Bitmap───▶│ denoise bilateral 5×5/3×3 + temporal     │─────▶│ consumidor 2 (SurfaceView)│
 │ TestPattern   │─procedural▶│   ↓                                      │ master│ captura (glReadPixels)    │
 └───────────────┘            │ grade: nitidez, EV/auto‑luz, brilho,     │ ring 3└───────────────────────────┘
                              │        contraste, gama, temperatura, sat │
                              └──────────────────────────────────────────┘
```

## Decisões técnicas
- **Flow não está no caminho crítico de pixels.** `FrameSource` expõe `StateFlow<SourceState>` para UI; os quadros viajam por `SurfaceTexture`/`Surface` (GPU→GPU, zero cópia para CPU). Backpressure = o consumidor GL sempre pega o quadro **mais novo**; os intermediários são descartados e contados (`DropPolicy.NEWEST` limita ao FPS‑alvo; `NONE` não limita).
- **Uma saída = um worker.** Cada Surface (preview, ImageReader de consumidor) tem thread e contexto EGL próprios, compartilhando as texturas do master (ring de 3). Consumidor lento só bloqueia o próprio worker; o motor sobrescreve o quadro pendente e conta o descarte.
- **Imagem estática**: decodificada uma vez (ImageDecoder, EXIF aplicado, lado ≤ 3072), enviada à GPU, bitmap liberado. Só preview local → 2 Hz de re‑render; com consumidor externo → FPS‑alvo (só GPU).
- **Vídeo**: Media3/ExoPlayer → MediaCodec → Surface OES; áudio desabilitado; rotação por metadado e mudança de resolução chegam via `VideoSize` + matriz do SurfaceTexture.
- **Câmera**: Camera2 → Surface OES; `SENSOR_ORIENTATION` aplicado no shader; quadros originais nunca chegam às saídas sem passar pelo pipeline; reabertura automática (até 5×) se perdida.
- **Padrões de diagnóstico** 100% em shader: barras, gradiente, barras com ruído σ≈0,08, quadros numerados (dígitos 7‑segmentos + código binário de 32 bits do contador + 16 bits de tempo + "F" de orientação).
- **Sem NDK/C++**: nenhum gargalo comprovado; GLSL cobre o processamento contínuo. (A regra do projeto é só usar C++ com necessidade comprovada.)
- **Luz automática / exposição**: ganho digital (EV em espaço aproximadamente linear). Não recupera detalhe de imagem subexposta; apenas reescala. Isto está escrito na UI.

## Integração (camada independente da UI e do motor)
`CameraIntegrationBackend { checkAvailability(); start(source); stop() }` com três backends:

| Backend | O que faz de verdade |
|---|---|
| `LocalBackend` | preview/captura dentro do app |
| `ConsumerSessionManager` (SDK + Binder) | serviço `VirtualCameraService` exportado; consumidor autorizado envia **sua Surface**, o motor desenha nela. Também `captureJpeg` (mesmo master) |
| `PrivilegedBackend` | **sonda honesta**: coleta evidências (su, app de sistema, bootloader, SELinux) e responde *Indisponível*. `start()` devolve Failure. Nenhum sucesso fabricado |

### Protocolo do consumidor (AIDL `integration-api`)
1. `bindService(ACTION_BIND, ComponentName(br.com.neurelix.virtualcamera, …VirtualCameraService))` — exige `<queries>` (já mesclado pelo módulo; sua ausência foi a causa do *"Failed to find provider info"* do xCam).
2. `openSession(...)`: o serviço identifica o chamador por `Binder.getCallingUid()` → pacote + SHA‑256 do certificado. Não autorizado ⇒ `SecurityException` + pedido pendente + notificação; o usuário decide em **Integração**.
3. `attachSurface(surface, StreamRequest)`; a resolução negociada é a **da Surface real** (consultada via EGL), `fps ≤ FPS do motor`.
4. `captureJpeg()` entrega o quadro processado atual por pipe (mesma fonte do preview ⇒ consistência preview/captura).
5. Morte do consumidor (`linkToDeath`) ou desligamento pelo usuário ⇒ saídas removidas, `onSessionClosed`.

### Desligamento e transparência
Banner "FONTE VIRTUAL ATIVA", notificação permanente com **PARAR TUDO**, botão "Desligar tudo" (fecha sessões, desliga integração, para o motor e libera a câmera). Ao parar, nenhuma câmera física fica retida (verificado em `dumpsys media.camera`).

## Persistência
DataStore (JSON): saída, filtros, fonte ativa, recentes (cópias privadas em `filesDir/sources`), perfis, consumidores autorizados/pendentes, histórico de sessões. Estado de UI é otimista e síncrono; gravação ordenada em segundo plano.

## Diagnóstico
`AppLog` (canais app/engine/camera2/codec/binder/privileged), métricas por segundo (FPS fonte/saída, mín/máx/σ em janela de 60 s, descartes, latência média/p95, CPU e memória do processo via `/proc/self/stat`), exportação TXT/ZIP com redação de caminhos, e‑mails e URIs.
