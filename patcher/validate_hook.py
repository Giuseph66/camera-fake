#!/usr/bin/env python3
"""Valida um APK já patcheado (patch_app.py) no aparelho, sem intervenção manual.

Uso: validate_hook.py <apk> <pacote> [--serial S] [--install] [--out DIR]

Sequência (tudo automático):
  1. (--install) instala o APK por cima; já mantém a autorização se a assinatura for a mesma
  2. liga a fonte NEURELIX (padrão numerado, sessão externa ligada)
  3. concede CAMERA ao pacote de teste
  4. abre o app; se o NEURELIX recusar (não autorizado), toca "Permitir" na UI e reabre
  5. espera e coleta: crashes, logs do hook (CAMHOOK), sessões/streams, clientes de câmera
  6. confere na tela se o padrão virtual está aparecendo (cor dos dígitos do padrão)
Veredito por etapa + JSON. Exit 0 só se tudo passou.
"""
import argparse, json, os, re, subprocess, sys, time

NVC = "br.com.neurelix.virtualcamera"
NVC_MAIN = f"{NVC}/br.com.neurelix.vcam.MainActivity"
DIGIT_GREEN = (0x33, 0xFF, 0x66)     # cor dos dígitos do padrão "quadros numerados"

class Adb:
    def __init__(self, serial): self.s = serial
    def run(self, *a, timeout=60):
        r = subprocess.run(["adb", "-s", self.s, *a], capture_output=True, text=True, timeout=timeout)
        return r.stdout
    def shell(self, *a, timeout=60): return self.run("shell", *a, timeout=timeout).replace("\r", "")
    def screenshot(self, path):
        with open(path, "wb") as f:
            subprocess.run(["adb", "-s", self.s, "exec-out", "screencap", "-p"], stdout=f, timeout=60)

def tap_text(adb, text):
    adb.shell("uiautomator dump /sdcard/ui.xml >/dev/null")
    xml = adb.run("exec-out", "cat", "/sdcard/ui.xml")
    for m in re.finditer(r'<node [^>]*?text="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1) == text:
            x = (int(m.group(2)) + int(m.group(4))) // 2
            y = (int(m.group(3)) + int(m.group(5))) // 2
            adb.shell(f"input tap {x} {y}")
            return True
    return False

def screen_has_virtual_pattern(png):
    """Conta pixels da cor dos dígitos do padrão NEURELIX na captura. Limiar: >= 300 pixels."""
    from PIL import Image
    im = Image.open(png).convert("RGB")
    px = im.load(); w, h = im.size; n = 0
    for y in range(0, h, 3):
        for x in range(0, w, 3):
            r, g, b = px[x, y]
            if abs(r - DIGIT_GREEN[0]) < 40 and abs(g - DIGIT_GREEN[1]) < 40 and abs(b - DIGIT_GREEN[2]) < 40:
                n += 1
    return n, n >= 300

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk"); ap.add_argument("pkg")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    if not a.serial:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.split("\n")
        a.serial = next((l.split()[0] for l in out[1:] if "\tdevice" in l), None)
    if not a.serial: sys.exit("nenhum aparelho")
    adb = Adb(a.serial)
    out = a.out or os.path.join(os.environ.get("TMPDIR", "/tmp"), f"validate_{a.pkg}_{int(time.time())}")
    os.makedirs(out, exist_ok=True)
    checks = {}

    if a.install:
        adb.run("install", "-r", a.apk, timeout=300)
    installed = adb.shell(f"pm path {a.pkg}").strip()
    checks["instalado"] = {"ok": bool(installed), "detalhe": installed[:80]}

    adb.shell(f"am start -n {NVC_MAIN} --ez reset true --es src pattern:NUMBERED --ez start true --ez ext true")
    time.sleep(4)
    adb.shell(f"pm grant {a.pkg} android.permission.CAMERA")

    def launch():
        adb.shell("am force-stop " + a.pkg)
        adb.shell("logcat -c")
        adb.shell(f"monkey -p {a.pkg} -c android.intent.category.LAUNCHER 1")
        time.sleep(14)

    launch()
    log = adb.shell("logcat -d -s NVC-CAMHOOK:* NVC/binder:W")
    if "não autorizado" in log:
        adb.shell("am start -n " + NVC_MAIN + " --ei tab 3")
        time.sleep(2)
        achou = False
        for _ in range(8):                       # "Pedidos pendentes" fica abaixo do guia e da lista de apps: rola até achar
            if tap_text(adb, "Permitir"):
                achou = True; break
            adb.shell("input swipe 540 1700 540 700 400"); time.sleep(1)
        checks["autorizacao"] = {"ok": achou, "detalhe": "toque em Permitir na UI do NEURELIX" + ("" if achou else " (não encontrado)")}
        time.sleep(1)
        launch()
    else:
        checks["autorizacao"] = {"ok": True, "detalhe": "já autorizado"}

    log = adb.shell("logcat -d -s NVC-CAMHOOK:* NVC/binder:I CameraControllerVirtual:I AndroidRuntime:E")
    # conta só crash real do pacote testado (AndroidRuntime: "Process: <pacote>, PID"); ignora ecos do adbd
    full = adb.shell("logcat -d -s AndroidRuntime:E")
    crashes = str(len(re.findall(r"Process: " + re.escape(a.pkg) + r", PID", full)))
    redirects = len(re.findall(r"redirecionad[ao] para o NEURELIX", log))
    kept = re.findall(r"mantida na câmera real|formato \w+ mantida", log)
    refused = "não autorizado" in log or "indisponível" in log
    clients = adb.shell("dumpsys media.camera | grep -c 'Client Package Name: " + a.pkg + "'").strip()
    pid = adb.shell(f"pidof {a.pkg}").strip()

    checks["app_nao_crashou"] = {"ok": crashes == "0" and bool(pid), "detalhe": f"FATAL={crashes} pid={pid or 'morto'}"}
    checks["hook_executou"] = {"ok": "NVC-CAMHOOK" in log or "CameraControllerVirtual" in log, "detalhe": "logs do shim presentes"}
    checks["sessao_neurelix"] = {"ok": redirects > 0, "detalhe": f"saídas redirecionadas={redirects}"}
    checks["camera_do_app_ativa"] = {"ok": clients != "0", "detalhe": f"clientes de câmera={clients}"}
    png = os.path.join(out, "tela.png"); adb.screenshot(png)
    n, ok = screen_has_virtual_pattern(png)
    checks["padrao_virtual_na_tela"] = {"ok": ok, "detalhe": f"pixels do padrão={n}", "captura": png}
    feed = re.findall(r"análise: (\d+) quadros virtuais", log)
    falhas_feed = len(re.findall(r"quadro de análise falhou", log))
    checks["analise_yolo_com_fonte_virtual"] = {"ok": bool(feed) and falhas_feed == 0,
        "detalhe": f"quadros virtuais entregues={feed[-1] if feed else 0}, falhas={falhas_feed}"}
    checks["saidas_mantidas_na_camera_real"] = {"ok": True, "detalhe": "; ".join(sorted(set(kept))) or "nenhuma"}

    veredito = all(c["ok"] for k, c in checks.items() if k != "saidas_mantidas_na_camera_real")
    report = {"apk": a.apk, "pacote": a.pkg, "aparelho": a.serial, "veredito": "PASSOU" if veredito else "FALHOU", "checks": checks}
    with open(os.path.join(out, "relatorio.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    for k, c in checks.items():
        print(f"[{'OK' if c['ok'] else 'FALHA'}] {k}: {c['detalhe']}")
    print(f"\nVEREDITO: {report['veredito']}  (relatório: {os.path.join(out, 'relatorio.json')})")
    sys.exit(0 if veredito else 1)

if __name__ == "__main__":
    main()
