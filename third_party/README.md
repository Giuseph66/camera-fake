# third_party

## opencamera/ — fork de teste do Open Camera 1.56.2 (GPL v3)
Upstream: https://sourceforge.net/p/opencamera/code/ — tag `v1.56.2` (commit `0dd4cbe`). Licença: GPL v3 (`opencamera/gpl-3.0.txt`);
este fork **é GPL v3** e o diretório permanece separado do restante do projeto (que consome só o SDK AIDL).

Objetivo: demonstrar a integração externa em um **app de câmera real** (o Open Camera que você já usa), sem root:
uma 5ª "câmera" — **NEURELIX Câmera Virtual** — aparece depois das câmeras reais; ao selecioná-la, o Open Camera
abre uma sessão no NEURELIX (SDK/Binder, autorização do usuário exigida) e o preview/foto passam a vir da fonte virtual.

Alterações em relação ao upstream (`git -C third_party/opencamera diff` / `third_party/opencamera-neurelix.patch`):
| Arquivo | Mudança |
|---|---|
| `app/build.gradle` | `applicationId = net.sourceforge.opencamera.neurelix` (**pacote distinto: o seu Open Camera original não é tocado**), minSdk 29, dependência `:integration-api`, Java 17 |
| `AndroidManifest.xml` | rótulo "Open Camera (NEURELIX)", `screenOrientation=portrait` |
| `cameracontroller/CameraControllerVirtual.java` | **novo** 3º controlador (122 métodos; preview via `Surface` do TextureView, foto via `captureJpeg`) |
| `cameracontroller/CameraControllerManager2.java` | +1 câmera (índice após as reais, `FACING_EXTERNAL`) se o NEURELIX estiver instalado |
| `preview/Preview.java` | instancia `CameraControllerVirtual` para o índice virtual |
| `MainActivity.java` | extra `neurelix_virtual=true` abre direto na câmera virtual |

Limitações declaradas: UI só em retrato; **sem vídeo** (a lista de tamanhos de vídeo é vazia); sem zoom/foco/flash/ISO (sem sentido numa fonte virtual);
foto sai no tamanho do master do NEURELIX; EXIF de orientação não é gerado (o JPEG já sai "em pé").

Build: `scripts/build.sh` (módulo `:opencamera`). Teste e2e: `scripts/test_opencamera.sh`.

## Como reconstruir o fork (o clone não é versionado aqui)
```bash
git clone --depth 1 --branch v1.56.2 https://git.code.sf.net/p/opencamera/code third_party/opencamera
git -C third_party/opencamera apply ../opencamera-neurelix.patch
./gradlew :opencamera:assembleDebug
```
