#!/usr/bin/env bash
# Auditoria somente-leitura do aparelho (nada é modificado). Uso: scripts/diagnose.sh [serial] > relatorio.txt
set -u
SERIAL=${1:-${ANDROID_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}}
A="adb -s $SERIAL"
echo "== adb devices -l"; adb devices -l
for p in ro.product.model ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.build.fingerprint \
         ro.boot.flash.locked ro.boot.verifiedbootstate ro.boot.warranty_bit ro.build.type ro.debuggable ro.secure sys.oem_unlock_allowed; do
  echo "$p=$($A shell getprop $p | tr -d '\r')"
done
echo "== id";        $A shell id
echo "== which su";  $A shell 'which su; echo rc=$?'
echo "== getenforce"; $A shell getenforce
echo "== HAL de câmera"; $A shell 'getprop | grep -iE "camera|hidl" | head -20; ls /vendor/lib64/hw | grep -i camera'
echo "== dumpsys media.camera (resumo)"; $A shell dumpsys media.camera | sed -n '1,25p'
echo "== pacotes NEURELIX"; $A shell pm list packages | grep neurelix
echo "== logcat NVC (últimas 80)"; $A logcat -d -s NVC/app:I NVC/engine:I NVC/binder:I NVC/camera2:I NVC/codec:I NVC/privileged:I | tail -80
