#!/usr/bin/env python3
"""Pipeline automático: vigia o aparelho e processa APKs exportados pelo NEURELIX, sem intervenção.

Fluxo por APK novo em /sdcard/Android/data/br.com.neurelix.virtualcamera/files/apks/:
  1. puxa o APK, classifica (inspect_app)           -> BLOQUEADO/SEM_CAMERA/CAMERA1 param aqui
  2. gera a cópia (patch_app), pacote <original>.neurelix
  3. instala + autoriza (validate_hook: toca "Permitir") + valida
  4. grava <pacote>.status.json na mesma pasta (o app pode mostrar o resultado)

Uso: auto_pipeline.py [--serial S] [--interval 15] [--once]
"""
import argparse, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
APKS = "/sdcard/Android/data/br.com.neurelix.virtualcamera/files/apks"
WORK = os.path.join(HERE, "work")
PY = sys.executable

def adb(serial, *a, timeout=120):
    r = subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True, timeout=timeout)
    return r.stdout.replace("\r", "")

def list_apks(serial):
    out = adb(serial, "shell", f"ls {APKS} 2>/dev/null")
    return [f for f in out.split() if f.endswith(".apk")]

def status(serial, pkg, data):
    path = f"{APKS}/{pkg}.status.json"
    local = os.path.join(WORK, f"{pkg}.status.json")
    with open(local, "w", encoding="utf-8") as f: json.dump(data, f, ensure_ascii=False, indent=2)
    adb(serial, "push", local, path)
    print(f"[{pkg}] {data['etapa']}: {data.get('detalhe', '')}", flush=True)

def process(serial, name):
    pkg = name[:-4]
    dst_pkg = pkg + ".neurelix"
    os.makedirs(WORK, exist_ok=True)
    apk = os.path.join(WORK, name)
    adb(serial, "pull", f"{APKS}/{name}", apk, timeout=300)
    status(serial, pkg, {"etapa": "inspecao", "detalhe": "analisando APK"})

    ins = subprocess.run([PY, os.path.join(HERE, "inspect_app.py"), apk, "--json"], capture_output=True, text=True)
    rep = json.loads(ins.stdout)
    if rep["status"] != "CAMERA2_INTERCEPTAVEL":
        status(serial, pkg, {"etapa": "recusado", "detalhe": rep["motivo"], "status": rep["status"]})
        return False

    status(serial, pkg, {"etapa": "patch", "detalhe": f"gerando {dst_pkg}"})
    out_apk = os.path.join(WORK, f"{pkg}-neurelix.apk")
    p = subprocess.run([PY, os.path.join(HERE, "patch_app.py"), apk, dst_pkg, out_apk], capture_output=True, text=True)
    if p.returncode != 0:
        status(serial, pkg, {"etapa": "falha_patch", "detalhe": (p.stdout + p.stderr)[-400:]})
        return False

    status(serial, pkg, {"etapa": "instalacao", "detalhe": "instalando e validando"})
    v = subprocess.run([PY, os.path.join(HERE, "validate_hook.py"), out_apk, dst_pkg, "--serial", serial, "--install",
                        "--out", os.path.join(WORK, f"val_{pkg}")], capture_output=True, text=True, timeout=1200)
    rel = os.path.join(WORK, f"val_{pkg}", "relatorio.json")
    checks = json.load(open(rel))["checks"] if os.path.exists(rel) else {}
    falhas = [k for k, c in checks.items() if not c["ok"] and k != "saidas_mantidas_na_camera_real"]
    if v.returncode == 0:
        status(serial, pkg, {"etapa": "pronto", "detalhe": f"{dst_pkg} instalado e validado", "checks": checks})
    else:
        status(serial, pkg, {"etapa": "falhou_validacao", "detalhe": "falhou: " + ", ".join(falhas), "checks": checks})
    return v.returncode == 0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--interval", type=int, default=15)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--process-existing", action="store_true", help="processa também APKs já presentes ao iniciar")
    a = ap.parse_args()
    if not a.serial:
        lines = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.split("\n")
        a.serial = next((l.split()[0] for l in lines[1:] if "\tdevice" in l), None)
    if not a.serial: sys.exit("nenhum aparelho")
    done = set() if a.process_existing else set(list_apks(a.serial))   # baseline: só o que chegar depois
    print(f"vigiando {a.serial} em {APKS} (a cada {a.interval}s)", flush=True)
    while True:
        try:
            for name in list_apks(a.serial):
                if name in done: continue
                done.add(name)
                process(a.serial, name)
        except Exception as e:
            print("erro no ciclo:", e, flush=True)
        if a.once: break
        time.sleep(a.interval)

if __name__ == "__main__":
    main()
