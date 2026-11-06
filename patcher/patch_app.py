#!/usr/bin/env python3
"""Patcher NEURELIX (sem root, sem LSPatch): pega o APK de um app instalado e gera uma versão
que SEMPRE usa a câmera virtual NEURELIX. Pacote novo + assinatura nova => instala ao lado do original.

Uso: patch_app.py <origin.apk> <novo.pacote> <saida.apk> [--install SERIAL]

Etapas: apktool decode -> manifest (pacote novo + <queries> do NEURELIX) -> smali hook em
Preview.openCameraCore() -> dex do shim como classesN.dex -> apktool build -> zipalign -> apksigner.
Escopo: somente apps escolhidos pelo próprio usuário. Não é para contornar biometria, prova de vida,
KYC ou antifraude; o patcher recusa pacotes da lista em DENYLIST.
"""
import os, re, shutil, subprocess, sys, zipfile, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
APKTOOL = os.path.join(HERE, "..", "tools", "apktool.jar")
BT = "/home/jesus/Android/Sdk/build-tools/35.0.1"
JAVA = "/opt/android-studio/jbr/bin/java"
KS = os.path.join(HERE, "keystore", "neurelix-patch.jks")
KS_PASS = "neurelix-test"
DEX = os.path.join(HERE, "build", "dex", "classes.dex")
HOOK_CLASS = "Lbr/com/neurelix/vcam/shim/NeurelixHook;"
DENYLIST_PREFIXES = (  # apps de identidade/pagamento: o patcher não os modifica
    "com.google.android.apps.walletnfcrel", "br.gov.", "com.nu.", "br.com.bradesco", "br.com.sicoob",
    "com.bradesco", "br.com.vivo", "com.paypal", "com.samsung.android.spay", "br.com.honda", "com.salesforce",
    "com.paypal.android", "br.com.sim.", "br.com.sicoobnet", "br.dataprev",
)

def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit(f"falhou: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r.stdout

def patch_manifest(path, new_pkg):
    s = open(path, encoding="utf-8").read()
    old = re.search(r'<manifest [^>]*package="([^"]+)"', s).group(1)
    s = s.replace(f'package="{old}"', f'package="{new_pkg}"', 1)
    queries = ('<queries><package android:name="br.com.neurelix.virtualcamera" />'
               '<intent><action android:name="br.com.neurelix.vcam.action.BIND_VIRTUAL_CAMERA" /></intent></queries>')
    # permissões customizadas declaradas com o pacote antigo: o original já é "dono" desses nomes no sistema
    for perm in set(re.findall(r'android:name="(' + re.escape(old) + r'\.[A-Za-z_0-9.]+)"', s)):
        if "PERMISSION" in perm or perm.endswith(".permission"):
            s = s.replace(perm, perm.replace(old, new_pkg, 1))
    # authorities de providers (androidx-startup, FileProvider...) também são únicas no sistema
    s = re.sub(r'android:authorities="' + re.escape(old) + r'([.\-A-Za-z_0-9]*)"',
               lambda m: f'android:authorities="{new_pkg}{m.group(1)}"', s)
    entries = ('<package android:name="br.com.neurelix.virtualcamera" />'
               '<intent><action android:name="br.com.neurelix.vcam.action.BIND_VIRTUAL_CAMERA" /></intent>')
    if "</queries>" in s:
        s = s.replace("</queries>", entries + "</queries>", 1)   # bloco já existe: acrescenta dentro dele
    else:
        s = s.replace("<application", queries + "\n    <application", 1)
    open(path, "w", encoding="utf-8").write(s)
    return old

def rename_permission_refs(dec, old, new_pkg):
    """Troca o prefixo do pacote antigo em nomes de permissão (manifest, smali, res, values)."""
    for root, _, files in os.walk(dec):
        for f in files:
            if not f.endswith((".xml", ".smali")):
                continue
            fp = os.path.join(root, f)
            try:
                t = open(fp, encoding="utf-8").read()
            except UnicodeDecodeError:
                continue
            n = re.sub(re.escape(old) + r"\.([A-Z_]+_PERMISSION)", new_pkg + r".\1", t)
            if n != t:
                open(fp, "w", encoding="utf-8").write(n)

def patch_preview_smali(path):
    s = open(path, encoding="utf-8").read()
    sig = ".method private openCameraCore(ILjava/lang/String;)Lnet/sourceforge/opencamera/cameracontroller/CameraController;"
    if sig not in s:
        sys.exit("openCameraCore não encontrado no smali (versão do Open Camera diferente?)")
    if "NeurelixHook" in s:
        return
    hook = (
        "    invoke-direct {p0}, Lnet/sourceforge/opencamera/preview/Preview;->getContext()Landroid/content/Context;\n"
        "    move-result-object v0\n"
        "    invoke-static {v0, p1, p2}, " + HOOK_CLASS + "->openCamera(Landroid/content/Context;ILjava/lang/String;)"
        "Lnet/sourceforge/opencamera/cameracontroller/CameraController;\n"
        "    move-result-object v0\n"
        "    if-eqz v0, :neurelix_skip\n"
        "    return-object v0\n"
        "    :neurelix_skip\n"
    )
    i = s.index(sig)
    j = s.index(":try_start_0", i)
    s = s[:j] + hook + "\n    " + s[j:]
    open(path, "w", encoding="utf-8").write(s)

def add_dex(apk_built, dex_src, out_apk):
    with zipfile.ZipFile(apk_built) as zin:
        names = zin.namelist()
        n = 1 + sum(1 for x in names if re.fullmatch(r"classes\d*\.dex", x))
        target = "classes.dex" if n == 1 else f"classes{n}.dex"
        with zipfile.ZipFile(out_apk, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                if item.filename == "resources.arsc":
                    zout.writestr(item, zin.read(item.filename), compress_type=zipfile.ZIP_STORED)
                else:
                    zout.writestr(item, zin.read(item.filename))
            zout.write(dex_src, target)
    return target

def main():
    args = sys.argv[1:]
    if len(args) < 3:
        sys.exit(__doc__)
    origin, new_pkg, out = args[0], args[1], args[2]
    serial = args[args.index("--install") + 1] if "--install" in args else None
    for bad in DENYLIST_PREFIXES:
        if new_pkg.startswith(bad) or bad in os.path.basename(origin):
            sys.exit("recusado: app de identidade/pagamento na lista de exclusão")
    work = tempfile.mkdtemp(prefix="nvc_patch_")
    dec = os.path.join(work, "dec")
    print("1) decode"); run([JAVA, "-jar", APKTOOL, "d", "-f", origin, "-o", dec], cwd=work)
    print("2) manifest"); old_pkg = patch_manifest(os.path.join(dec, "AndroidManifest.xml"), new_pkg)
    rename_permission_refs(dec, old_pkg, new_pkg)
    print(f"   {old_pkg} -> {new_pkg}")
    print("3) hook smali")
    pv = subprocess.run(["find", dec, "-path", "*/net/sourceforge/opencamera/preview/Preview.smali"], capture_output=True, text=True).stdout.strip().splitlines()
    patch_preview_smali(pv[0])
    print("4) build"); built = os.path.join(work, "built.apk")
    run([JAVA, "-jar", APKTOOL, "b", dec, "-o", built], cwd=work)
    print("5) dex do shim")
    unsigned = os.path.join(work, "unsigned.apk"); dexname = add_dex(built, DEX, unsigned)
    print("   adicionado como", dexname)
    print("6) zipalign + assinatura nova")
    aligned = os.path.join(work, "aligned.apk")
    run([f"{BT}/zipalign", "-f", "-p", "4", unsigned, aligned])
    run([f"{BT}/apksigner", "sign", "--ks", KS, "--ks-key-alias", "neurelix", "--ks-pass", f"pass:{KS_PASS}",
         "--key-pass", f"pass:{KS_PASS}", "--out", out, aligned])
    run([f"{BT}/apksigner", "verify", "--print-certs", out]); 
    print("OK ->", out)
    if serial:
        print("7) install", serial)
        print(run(["adb", "-s", serial, "install", "-r", out]))
    shutil.rmtree(work, ignore_errors=True)

if __name__ == "__main__":
    main()
