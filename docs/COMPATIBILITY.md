# Compatibilidade e viabilidade (Galaxy S20 FE, Android 12, One UI 4.1)

## Fatos medidos (scripts/diagnose.sh, 2026‑11‑06)
| Item | Valor |
|---|---|
| Modelo / build | SM‑G781B, `samsung/r8qxxx/r8q:12/SP1A.210812.016/G781BXXS4FVK3:user/release-keys`, patch 2022‑11‑01 |
| ABI | arm64‑v8a |
| Usuário do `adb shell` | `uid=2000(shell)`; `which su` → rc=1 (**sem root**) |
| Bootloader | `ro.boot.flash.locked=1`, `verifiedbootstate=green`, `warranty_bit=0`, `sys.oem_unlock_allowed=0` |
| SELinux | Enforcing |
| Câmera | CameraService → `sec-camera-provider-4-0` (HIDL, `vendor.samsung.hardware.camera.provider@4.0-impl`), IDs 0–4 visíveis; `vendor.camera.unihal.enable=true` |
| GPU | Adreno 650, OpenGL ES 3.2 |

## Cenários pedidos
| # | Cenário | Veredito neste aparelho | Evidência / motivo |
|---|---|---|---|
| 1 | APK comum, APIs públicas | **Funciona só para apps que usam o SDK** (ou `IMAGE_CAPTURE`). Não registra câmera no `CameraManager` | Android 12 não tem API pública de câmera virtual; `dumpsys media.camera` não lista dispositivo nosso |
| 2 | ADB em desenvolvimento | Útil p/ instalar, testar, `pm grant`; **não equivale a root** e não registra câmera | `uid=2000`, SELinux enforcing |
| 3 | Componente root | **Indisponível** — requer desbloquear bootloader (apaga dados, aciona Knox/warranty, quebra Play Integrity/apps bancários) | probe: `su` ausente, bootloader travado. **Não executado; precisa de autorização** |
| 4 | Serviço/app de sistema | Indisponível (APK não é privilegiado; assinatura de plataforma Samsung inacessível) | probe do `PrivilegedBackend` |
| 5 | Camada de câmera (HAL/CameraService) | Teoricamente viável só com root + HAL/provider próprio + sepolicy + VINTF; **não implementado, não testado** | `system/README.md` |
| 6 | Consumidores integrados | **Funcional e testado** | `docs/TEST_REPORT.md` §6 |

## Interceptação no consumidor (LSPosed/LSPatch)
Fora de escopo por decisão do projeto (xCam/LSPatch/LSPosed/Shizuku não são dependências). Seria interceptação por app, **não** uma câmera registrada no sistema, e é incompatível com apps protegidos.

## Por aplicativo
| App | Resultado |
|---|---|
| `br.com.neurelix.vcam.consumer` (SDK) | **Comprovado**: preview, captura, ciclo de vida (ver relatório) |
| Qualquer app que dispare `ACTION_IMAGE_CAPTURE` com EXTRA_OUTPUT | Comprovado com o consumidor de teste via intent explícita; apps de terceiros **não testados** |
| WhatsApp, Instagram, navegadores (`getUserMedia`), câmeras de terceiros | **Não compatíveis**: usam a câmera física via `CameraManager`. Página `getUserMedia` não pode receber a fonte sem câmera registrada no sistema |
| Apps com biometria/KYC/antifraude | Fora de escopo e **não suportado** por princípio |

## Outros aparelhos
Galaxy A54 (Android 15/One UI 7): **não tocado**. Etapa futura: reavaliar (a API de câmera virtual do framework a partir do Android 14/15 é restrita a dispositivos virtuais com companion/privilégios; não assumir disponibilidade a apps comuns).
