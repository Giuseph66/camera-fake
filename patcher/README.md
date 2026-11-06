# patcher — câmera virtual NEURELIX em apps instalados (sem root)

Gera uma cópia de um app com **pacote novo + assinatura nova** (instala ao lado do original, sem desinstalar),
que usa a câmera virtual NEURELIX em vez do sensor.

## Uso
```bash
python3 patcher/patch_app.py <origin.apk> <novo.pacote> <saida.apk> [--install SERIAL]
```
- `origin.apk`: APK limpo do app. Se o app instalado for um pacote LSPatch, extraia `assets/lspatch/origin.apk`.
- Ferramentas: `tools/apktool.jar` (2.11.1), `build-tools` do SDK, JDK 21 (`/opt/android-studio/jbr`).
- Chave de teste: `patcher/keystore/neurelix-patch.jks` (senha de teste; não é chave de produção).

## O que o patch faz
1. apktool decode.
2. Manifest: pacote novo; permissões customizadas e `authorities` trocados do prefixo antigo (senão colidem com o original instalado); `<queries>` para o NEURELIX.
3. smali: hook no início de `Preview.openCameraCore()` → `NeurelixHook.openCamera()`. Se o NEURELIX der sessão, o preview e a foto vêm dele; se não, volta à câmera física.
4. Shim (`patcher/shim`, Java puro, compilado com d8 em `build/dex/classes.dex`) entra como `classesN.dex`.
5. apktool build → zipalign → apksigner (chave nova).

## Limites
- Hook **específico do Open Camera 1.56.2** (`Preview.openCameraCore`). Outro app precisa de análise própria dos pontos de abertura de câmera.
- Recusa pacotes de identidade/pagamento listados em `DENYLIST_PREFIXES`. Não é para contornar biometria, prova de vida, KYC ou antifraude.
- Fallback para câmera física quando o NEURELIX não autoriza ou não tem fonte ativa.
