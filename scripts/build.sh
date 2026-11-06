#!/usr/bin/env bash
# Compila os APKs (app + consumidor de teste) e executa os testes unitários.
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/android-studio/jbr}   # AGP 8.13 exige JDK 17–21 (o JDK 25 do sistema não serve)
./gradlew :core:testDebugUnitTest :app:assembleDebug :consumer-sample:assembleDebug :opencamera:assembleDebug "$@"
mkdir -p out
cp app/build/outputs/apk/debug/app-debug.apk out/neurelix-virtualcamera-debug.apk
cp consumer-sample/build/outputs/apk/debug/consumer-sample-debug.apk out/neurelix-consumer-sample-debug.apk
cp third_party/opencamera/app/build/outputs/apk/debug/opencamera-debug.apk out/opencamera-neurelix-debug.apk
ls -la out/
