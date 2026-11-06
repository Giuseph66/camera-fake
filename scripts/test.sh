#!/usr/bin/env bash
# Teste de integração externa ponta a ponta no aparelho (usa a UI real para autorizar o consumidor).
# Uso: scripts/test.sh [serial]. Gera evidências em evidence/e2e_*.
set -u
cd "$(dirname "$0")/.."
SERIAL=${1:-${ANDROID_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}}
A="adb -s $SERIAL"; APP=br.com.neurelix.virtualcamera; M=$APP/br.com.neurelix.vcam.MainActivity; C=br.com.neurelix.vcam.consumer
OUT=evidence/e2e_$(date +%H%M%S); mkdir -p "$OUT"; FAIL=0
ok(){ echo "[ OK ] $1"; }; ko(){ echo "[FALHA] $1"; FAIL=1; }
tap_text(){ # toca no centro do primeiro nó com o texto dado
  $A shell uiautomator dump /sdcard/ui.xml >/dev/null; $A exec-out cat /sdcard/ui.xml | python3 -c "
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*?text=\"([^\"]+)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x):
    if m.group(1)==t: print((int(m.group(2))+int(m.group(4)))//2,(int(m.group(3))+int(m.group(5)))//2); break" "$1"; }

$A shell pm clear $APP >/dev/null            # só os dados do NOSSO app: parte de estado limpo
$A shell am force-stop $C; $A logcat -c
$A shell am start -n $M --ez reset true --es src pattern:NUMBERED --ez start true --ez ext true >/dev/null; sleep 4

echo "--- 1) consumidor NÃO autorizado deve ser recusado"
$A shell am start -n $C/.ConsumerActivity --ez auto true --ei seconds 8 >/dev/null; sleep 8
$A logcat -d -s NVC-CONSUMER:I | grep -q "NOT_AUTHORIZED" && ok "recusado com NOT_AUTHORIZED" || ko "não foi recusado"

echo "--- 2) autorizar pela UI (Integração → Permitir)"
$A shell am start -n $M --ei tab 3 >/dev/null; sleep 2; $A shell input swipe 540 1700 540 500 400; sleep 1
XY=$(tap_text "Permitir"); if [ -n "$XY" ]; then $A shell input tap $XY; ok "toque em Permitir ($XY)"; else ko "botão Permitir não encontrado"; fi
sleep 1

echo "--- 3) consumidor autorizado recebe quadros (15 s)"
$A shell am force-stop $C; $A logcat -c
$A shell am start -n $C/.ConsumerActivity --ez auto true --ei seconds 15 >/dev/null; sleep 24
$A logcat -d -s NVC-CONSUMER:I > "$OUT/consumer.log"
grep -q "OPEN_SESSION ok" "$OUT/consumer.log" && ok "sessão aberta" || ko "sessão não abriu"
R=$(grep AUTO_RESULT "$OUT/consumer.log" | tail -1); echo "     $R"
FR=$(echo "$R" | sed -n 's/.*frames=\([0-9]*\).*/\1/p'); MK=$(echo "$R" | sed -n 's/.*marker=\([0-9]*\).*/\1/p')
[ "${FR:-0}" -gt 300 ] && [ "${MK:-0}" -eq "${FR:-0}" ] && ok "quadros recebidos com contador íntegro ($FR)" || ko "quadros insuficientes/corrompidos"
grep -q "PHOTO_SDK ok.*marker=true" "$OUT/consumer.log" && ok "captura JPEG coerente com a fonte" || ko "captura SDK falhou"

echo "--- 4) desativação: motor para e a câmera física é liberada"
$A shell am start -n $M --ez stop true >/dev/null; sleep 3
$A shell dumpsys media.camera | grep -q "Client Package Name: $APP" && ko "NEURELIX ainda segura a câmera" || ok "nenhuma câmera retida pelo NEURELIX"
$A exec-out run-as $C cat files/out/sdk_capture.jpg > "$OUT/captura_sdk.jpg"
$A exec-out screencap -p > "$OUT/tela_final.png"
$A logcat -d -s NVC/binder:I NVC/engine:I NVC/app:I > "$OUT/neurelix.log"
echo "Evidências em $OUT"; exit $FAIL
