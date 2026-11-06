# Recuperação e reversão

## O que o projeto instala hoje
Apenas dois APKs comuns. **Reversão completa** = desinstalar (ver INSTALLATION.md). Nenhuma alteração de sistema, bootloader, SELinux, partição ou firmware foi feita.

## Se algo travar durante o uso
1. Notificação **PARAR TUDO**, ou *Painel → Desligar tudo*: fecha sessões externas, desliga a integração, para o motor, libera a câmera.
2. `adb shell am force-stop br.com.neurelix.virtualcamera` (a câmera física volta ao normal; confirme com `adb shell dumpsys media.camera`, "Active Camera Clients: []").
3. `adb shell pm clear br.com.neurelix.virtualcamera` zera configurações, autorizações e mídia copiada (**apaga dados do app**).

## Caminho de sistema (NÃO executado — exige autorização específica)
Qualquer câmera virtual global exigiria, no mínimo: (a) desbloquear o bootloader, (b) obter root, (c) instalar um provider de câmera próprio e (d) ajustar sepolicy/VINTF. Riscos: apaga todos os dados do aparelho; contador **Knox/warranty_bit** passa a 1 de forma irreversível (perde garantia e recursos Samsung — Pay, Secure Folder, Knox); Play Integrity/atestação falham (apps bancários, gov.br, carteiras digitais instalados no S20 FE podem deixar de abrir); risco de bootloop.

Recuperação nesse cenário: baixar o **firmware oficial Samsung** exato do modelo/CSC (SM‑G781B), entrar em Download Mode e restaurar via Odin (AP+BL+CP+CSC; usar CSC e não HOME_CSC para limpar). O Knox/warranty_bit **não** é restaurado por reflash. Faça backup completo antes. Nada disso é feito sem sua autorização expressa.
