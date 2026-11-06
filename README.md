# NEURELIX Câmera Virtual (Android)

Central de controle + motor GPU que transforma **fotografia, vídeo, câmera física processada ou padrão de diagnóstico** numa fonte de imagem entregue a **aplicativos consumidores autorizados**.

> **Estado honesto (Galaxy S20 FE SM-G781B, Android 12, sem root):**
> - ✅ Motor, interface, fontes, filtros, diagnóstico: funcionais e testados no aparelho.
> - ✅ **Integração externa por SDK/Binder**: um app independente (outro pacote/UID) recebeu a fonte em tempo real, preview e captura – ver `docs/TEST_REPORT.md`.
> - ✅ Handler `IMAGE_CAPTURE` (apps que pedem "tire uma foto" podem receber a fonte virtual após confirmação do usuário).
> - ❌ **Não existe câmera virtual global**: apps como WhatsApp/navegador/câmeras de terceiros continuam usando a câmera física. Isso exige root/ROM modificada (bootloader hoje travado) e **não foi feito** – ver `system/README.md`.
>
> Nenhum item é declarado "funcional" sem evidência em `evidence/` e `docs/TEST_REPORT.md`.

## Módulos
| Módulo | Função |
|---|---|
| `core` | modelos, persistência (DataStore), log, exportação de diagnóstico |
| `engine` | `RenderEngine` (EGL/GLES 3.0), fontes, shaders (denoise, nitidez, cor), benchmark de ruído |
| `integration-api` | AIDL + SDK cliente (`VirtualCameraClient`) usado por consumidores |
| `app` | UI Compose, serviço Binder, backends local/consumidores/privilegiado (sonda), `CaptureActivity` |
| `consumer-sample` | consumidor **independente** usado para provar a integração externa |

## Uso rápido
```bash
scripts/build.sh && scripts/install.sh      # compila + instala nos dois APKs
scripts/test.sh                              # ponta a ponta no aparelho (usa a UI real para autorizar)
scripts/stability.sh pattern 10              # estabilidade (modos: pattern video image camera_front camera_back)
scripts/diagnose.sh > relatorio.txt          # auditoria somente-leitura
```
Documentação: `docs/ARCHITECTURE.md`, `docs/COMPATIBILITY.md`, `docs/INSTALLATION.md`, `docs/RECOVERY.md`, `docs/TEST_REPORT.md`, `system/README.md`.

## Princípios
Processamento 100% local, sem telemetria. Fonte virtual sempre sinalizada (banner + notificação permanente + botão "Desligar tudo"). Consumidores só recebem a fonte após **autorização explícita** (pacote + hash do certificado). Não é destinado a burlar biometria, prova de vida, KYC ou antifraude.

## Patcher (câmera virtual em apps instalados, sem root)
`patcher/patch_app.py` gera uma cópia de um app (pacote e assinatura novos, instala ao lado do original) que usa a câmera virtual NEURELIX.
Validado no S20 FE com o Open Camera 1.56.2: preview e foto vieram da fonte virtual. O hook é específico desse app; limites em `patcher/README.md`.
Pré-requisitos: `tools/README.md` (apktool), `third_party/README.md` (fork opcional do Open Camera). A chave de assinatura (`patcher/keystore/`) é de teste e não é versionada.
