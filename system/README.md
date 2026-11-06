# Componentes de sistema (privilegiados) — **projeto de intervenção, não implementado**

Esta pasta existe para registrar o bloqueio exato e a menor intervenção necessária. **Nenhum código aqui é instalado ou executado**, pois nenhum pré‑requisito existe no aparelho:

| Pré‑requisito | Estado medido |
|---|---|
| root | ausente (`which su` rc=1) |
| bootloader | travado (`ro.boot.flash.locked=1`), OEM unlock não liberado (`sys.oem_unlock_allowed=0`) |
| build | `user`, `release-keys`, SELinux enforcing, verity enforcing |

## Por que um APK não consegue
`CameraService` só enxerga câmeras vindas de **providers HAL** registrados no `hwservicemanager` (aqui `sec-camera-provider-4-0`). Registrar outro provider exige: processo de domínio SELinux próprio com permissão de `add` no `hwservicemanager`, declaração no **VINTF manifest** do vendor e instalação em partição protegida. Nada disso é possível com uid de app ou `adb shell` (uid 2000).

## Menor intervenção que poderia funcionar (hipótese **não testada**)
1. Desbloquear bootloader (**autorização específica; apaga dados; aciona Knox**).
2. Root sistêmico (ex.: Magisk) para sobrepor `/vendor/etc/vintf` e injetar regras sepolicy no boot.
3. Provider HIDL `android.hardware.camera.provider@2.4` próprio ("virtual/0", baseado no *external camera provider* do AOSP) que entregue buffers recebidos do app NEURELIX por memória compartilhada/AHardwareBuffer, mais `CameraDeviceSession` mínima (preview + JPEG).
4. Módulo com procedimento de instalação/remoção reversível e verificação por `dumpsys media.camera`.

Mesmo assim, apps com atestação (Play Integrity, KYC, biometria) recusariam o aparelho e a fonte **não deve** ser usada para burlá‑los. Antes de qualquer passo: ler `docs/RECOVERY.md` e dar autorização por escrito para cada etapa.

## O que o app faz hoje para este eixo
`PrivilegedBackend` coleta evidências em somente‑leitura e devolve *Indisponível* com os motivos; exibe isso nas telas Painel, Integração e no relatório exportado.
