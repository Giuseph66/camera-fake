#!/usr/bin/env bash
# Instala/atualiza o app e o consumidor de teste no aparelho. Uso: scripts/install.sh [serial]
set -eu
cd "$(dirname "$0")/.."
SERIAL=${1:-${ANDROID_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}}
[ -n "$SERIAL" ] || { echo "nenhum aparelho em 'adb devices'"; exit 1; }
MODEL=$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
echo "Instalando em $SERIAL ($MODEL)"
adb -s "$SERIAL" install -r out/neurelix-virtualcamera-debug.apk
adb -s "$SERIAL" install -r out/neurelix-consumer-sample-debug.apk
[ -f out/opencamera-neurelix-debug.apk ] && adb -s "$SERIAL" install -r out/opencamera-neurelix-debug.apk   # fork de teste (pacote .neurelix; não toca no Open Camera original)
adb -s "$SERIAL" shell pm list packages | grep -E "neurelix"
echo "Concluído. Abra 'NEURELIX Câmera Virtual'. Nada é concedido automaticamente: permissão de câmera e autorização de consumidores são pedidas na UI."
