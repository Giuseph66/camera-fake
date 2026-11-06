#!/usr/bin/env bash
# Teste de estabilidade: roda um modo por N minutos com consumidor externo e coleta métricas reais.
# Uso: scripts/stability.sh <modo> <minutos> [serial]
#   modo: pattern | video | camera_front | camera_back | image
set -u
MODE=${1:?modo}; MIN=${2:?minutos}; SERIAL=${3:-${ANDROID_SERIAL:-$(adb devices | awk 'NR==2{print $1}')}}
A="adb -s $SERIAL"
APP=br.com.neurelix.virtualcamera; M=$APP/br.com.neurelix.vcam.MainActivity
P=/data/user/0/$APP/files/sources
OUT=${OUT:-evidence/stability_${MODE}_$(date +%H%M%S)}; mkdir -p "$OUT"
case $MODE in
  pattern) SRC="pattern:NUMBERED"; EXTRA="";;
  video) SRC="video:$P/land.mp4"; EXTRA="";;
  image) SRC="image:$P/photo_land.jpg"; EXTRA="";;
  camera_front) SRC=camera_front; EXTRA="--ef denoise 0.6 --ef sharp 0.3";;
  camera_back) SRC=camera_back; EXTRA="--ef denoise 0.6 --ef sharp 0.3";;
esac
$A shell am force-stop br.com.neurelix.vcam.consumer; $A shell am force-stop $APP; sleep 1
$A logcat -c
$A shell am start -n $M --ez reset true --es src "$SRC" --ez start true --ez ext true $EXTRA >/dev/null
sleep 4
SEC=$((MIN*60))
$A shell am start -n br.com.neurelix.vcam.consumer/.ConsumerActivity --ez auto true --ei seconds $SEC >/dev/null
PID=$($A shell pidof $APP)
echo "modo=$MODE minutos=$MIN pid=$PID início=$(date +%T)" | tee "$OUT/resumo.txt"
echo "t_s,pss_kb,native_kb,cpu_pct,bat_temp_dc,bat_level" > "$OUT/recursos.csv"
for ((t=0; t<SEC; t+=30)); do
  sleep 30
  PSS=$($A shell dumpsys meminfo $PID 2>/dev/null | awk '/TOTAL PSS:/{print $3; exit} /^ *TOTAL +[0-9]/{print $2; exit}')
  NAT=$($A shell dumpsys meminfo $PID 2>/dev/null | awk '/Native Heap/{print $3; exit}')
  CPU=$($A shell dumpsys cpuinfo 2>/dev/null | grep " $PID/$APP" | head -1 | awk '{print $1}' | tr -d '%')
  TMP=$($A shell dumpsys battery | awk '/temperature/{print $2}'); LVL=$($A shell dumpsys battery | awk '/level/{print $2}')
  echo "$((t+30)),${PSS:-},${NAT:-},${CPU:-},${TMP:-},${LVL:-}" >> "$OUT/recursos.csv"
done
sleep 20
$A logcat -d -s NVC/engine:I NVC-CONSUMER:I NVC/binder:I NVC/camera2:I NVC/app:I AndroidRuntime:E > "$OUT/logcat.txt"
python3 - "$OUT" <<'PY' | tee -a "$OUT/resumo.txt"
import re,sys,statistics as st
o=sys.argv[1]; L=open(f"{o}/logcat.txt",errors="ignore").read().splitlines()
m=[l for l in L if "[metrics]" in l]
def g(p,l):
    r=re.search(p,l); return float(r.group(1).replace(",",".")) if r else None
out=[g(r"out=([\d,\.]+)",l) for l in m]; out=[x for x in out if x is not None]
src=[g(r"src=([\d,\.]+)",l) for l in m]; src=[x for x in src if x is not None]
lat=[g(r"lat=([\d,\.]+)",l) for l in m]; lat=[x for x in lat if x is not None]
p95=[g(r"p95 ([\d,\.]+)",l) for l in m]; p95=[x for x in p95 if x is not None]
drop=[g(r"drop=(\d+)",l) for l in m]; drop=[x for x in drop if x is not None]
cpu=[g(r"cpu=(\d+)",l) for l in m]; cpu=[x for x in cpu if x is not None]
mem=[g(r"mem=(\d+)",l) for l in m]; mem=[x for x in mem if x is not None]
W=3  # descarta as 3 primeiras amostras (aquecimento: 15 s)
out,src,lat,p95,drop,cpu,mem=[x[W:] for x in (out,src,lat,p95,drop,cpu,mem)]
print(f"amostras de métricas (a cada 5 s, após aquecimento): {len(out)}")
if out:
    print(f"FPS saída: média {st.mean(out):.2f}  mín {min(out):.1f}  máx {max(out):.1f}  σ {st.pstdev(out):.2f}")
    print(f"FPS fonte: média {st.mean(src):.2f}")
    print(f"latência pipeline: média {st.mean(lat):.1f} ms, p95 médio {st.mean(p95):.1f} ms, pior p95 {max(p95):.1f} ms")
    print(f"descartes acumulados (final): {int(drop[-1])}")
    print(f"CPU app (amostras de 1 s do motor): média {st.mean(cpu):.0f}%  máx {max(cpu):.0f}%")
    print(f"Memória PSS app: início {mem[0]:.0f} MB → fim {mem[-1]:.0f} MB (máx {max(mem):.0f})")
res=[l for l in L if "AUTO_RESULT" in l or "AUTO_FAIL" in l]
print("consumidor:", res[-1].split("NVC-CONSUMER:")[-1].strip() if res else "sem AUTO_RESULT")
crash=[l for l in L if "FATAL" in l or "AndroidRuntime" in l]
print("crashes:", len(crash))
ev=[l for l in L if " W NVC" in l or " E NVC" in l]
print("avisos/erros NVC:", len(ev))
for l in ev[:5]: print("  ",l[-160:])
PY
