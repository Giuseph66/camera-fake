# Instalação, uso e remoção

## Requisitos de build
- Linux, Android SDK (platforms 36, build‑tools 35+), **JDK 17–21** (o script usa o JBR do Android Studio: `/opt/android-studio/jbr`). O JDK 25 padrão do sistema **não** funciona com AGP 8.13.
- Gradle wrapper 8.14 (baixa sozinho). Sem NDK.

## Compilar e instalar
```bash
scripts/build.sh          # testes unitários + APKs em out/
scripts/install.sh [serial]
```
Os APKs são assinados com a chave **debug** (desenvolvimento). Para distribuição, gere keystore próprio e troque `signingConfig` do `release` em `app/build.gradle.kts`.

## Primeiro uso
1. Abra **NEURELIX Câmera Virtual** → *Fontes*: escolha foto/vídeo (Photo Picker; a mídia é copiada para o armazenamento privado do app), câmera traseira/frontal (a permissão de câmera é pedida nesse momento) ou padrão de diagnóstico.
2. *Painel* → **Iniciar**. Aparece a notificação permanente "Fonte virtual ativa".
3. Para apps integrados: *Integração* → ligar **Permitir consumidores integrados**; quando um app pedir acesso aparece em *Pedidos pendentes* → **Permitir** (ou Recusar).

## Integrando um app consumidor (SDK)
Dependa de `:integration-api` (traz o `<queries>` para enxergar o NEURELIX).
```kotlin
val client = VirtualCameraClient(context)
val session = (client.openSession("Meu app") as ClientResult.Ok).value
val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 4)   // ou SurfaceView.holder.surface
session.attach(reader.surface, 1280, 720, fps = 30)
// ... reader.acquireLatestImage() ...
val jpeg = (session.captureJpeg() as ClientResult.Ok).value                  // captura = mesmo quadro processado
session.close(); client.disconnect()
```
Erros tipados: `NOT_INSTALLED`, `NOT_AUTHORIZED`, `INACTIVE`, `TIMEOUT`, `REMOTE_ERROR`. Contrato: **drene a Surface** (consumidor parado só trava a própria saída).

## Câmera por intent
Apps que usam `MediaStore.ACTION_IMAGE_CAPTURE` podem listar o **NEURELIX Câmera Virtual** como câmera. O usuário vê a pré‑visualização da fonte e confirma antes de a imagem ser entregue.

## Automação para testes (somente build debuggable)
`am start -n br.com.neurelix.virtualcamera/br.com.neurelix.vcam.MainActivity --es src pattern:NUMBERED --ez start true --ez ext true`  
extras: `src` (`pattern:*`, `image:/abs`, `video:/abs`, `camera_back`, `camera_front`), `start`, `stop`, `ext`, `out=1280x720@30`, `rot`, `mirror`, `fit`, filtros (`denoise bright contrast sat sharp ev temp gamma auto`), `reset`, `bench`, `lost` (simulação no motor), `lostpolicy`. **Não** concede autorização de consumidores.

## Remoção
```bash
adb uninstall br.com.neurelix.vcam.consumer
adb uninstall br.com.neurelix.virtualcamera     # remove também fontes copiadas e perfis
```
Nada é instalado fora do sandbox dos apps (sem partições, sem módulos, sem SELinux).
