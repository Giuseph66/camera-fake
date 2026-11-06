#!/usr/bin/env bash
# E2E no aparelho: Open Camera (fork NEURELIX) recebe a fonte virtual e tira foto com ela.
# Pré: APKs instalados (scripts/install.sh). Usa a UI real para autorizar o app. Uso: scripts/test_opencamera.sh [serial]
set -u
cd "$(dirname "$0")/.."
SERIAL=${1:-${ANDROID_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}}
A="adb -s $SERIAL"; APP=br.com.neurelix.virtualcamera; M=$APP/br.com.neurelix.vcam.MainActivity
OC=net.sourceforge.opencamera.neurelix; OCA=$OC/net.sourceforge.opencamera.MainActivity
OUT=evidence/oc_$(date +%H%M%S); mkdir -p "$OUT"; FAIL=0
ok(){ echo "[ OK ] $1"; }; ko(){ echo "[FALHA] $1"; FAIL=1; }
tap_text(){ $A shell uiautomator dump /sdcard/ui.xml >/dev/null; $A exec-out cat /sdcard/ui.xml | python3 -c "
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*?(?:text|content-desc)=\"([^\"]+)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    if m.group(1)==t: print((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2); break" "$1"; }

$A shell pm clear $OC >/dev/null 2>&1
for p in CAMERA RECORD_AUDIO; do $A shell pm grant $OC android.permission.$p; done      # só o fork de teste
$A shell am start -n $M --ez reset true --es src pattern:NUMBERED --ez start true --ez ext true >/dev/null; sleep 4
$A logcat -c

echo "--- 1) Open Camera (fork) sem autorização → deve ser recusado"
$A shell am start -n $OCA --ez neurelix_virtual true >/dev/null; sleep 6
$A logcat -d -s NVC/binder:W | grep -q "$OC não autorizado" && ok "recusado: não autorizado" || ko "não houve recusa"
$A exec-out screencap -p > "$OUT/01_recusado.png"

echo "--- 2) autorizar na UI do NEURELIX"
$A shell am force-stop $OC
$A shell am start -n $M --ei tab 3 >/dev/null; sleep 2; $A shell input swipe 540 1700 540 500 400; sleep 1
XY=$(tap_text "Permitir"); [ -n "$XY" ] && { $A shell input tap $XY; ok "Permitir tocado"; } || ko "botão Permitir não encontrado"
sleep 1

echo "--- 3) Open Camera recebe a fonte"
$A logcat -c
$A shell am start -n $OCA --ez neurelix_virtual true >/dev/null; sleep 7
$A exec-out screencap -p > "$OUT/02_opencamera_preview.png"
$A logcat -d -s CameraControllerVirtual:I NVC/binder:I | tee "$OUT/attach.log" | tail -5
grep -q "preview anexado" "$OUT/attach.log" && ok "preview anexado à sessão NEURELIX" || ko "preview não anexou"

echo "--- 4) foto pelo obturador do Open Camera"
XY=$(tap_text "Take Photo"); [ -z "$XY" ] && XY=$(tap_text "Tirar foto"); [ -z "$XY" ] && XY="540 2050"
$A shell input tap $XY; sleep 4
$A exec-out screencap -p > "$OUT/03_pos_foto.png"
F=$($A shell "ls -t /sdcard/DCIM/OpenCamera/*.jpg 2>/dev/null | head -1" | tr -d '\r')
if [ -n "$F" ]; then
  $A pull "$F" "$OUT/foto_opencamera.jpg" >/dev/null && ok "foto salva: $F"
  python3 scripts/verify_frame.py "$OUT/foto_opencamera.jpg" && ok "conteúdo da foto = fonte virtual (marcador + contador decodificados)" || ko "foto não contém o padrão virtual"
else ko "nenhuma foto encontrada em DCIM/OpenCamera"; fi
echo "--- 5) fechar: câmera não deve ficar retida"
$A shell am force-stop $OC; sleep 1
$A shell am start -n $M --ez stop true >/dev/null; sleep 3
$A shell dumpsys media.camera | grep -q "Client Package Name: $APP" && ko "NEURELIX retém a câmera" || ok "câmera livre"
echo "Evidências: $OUT"; exit $FAIL
