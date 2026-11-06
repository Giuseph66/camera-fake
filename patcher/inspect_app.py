#!/usr/bin/env python3
"""Inspeciona um APK (sem código-fonte) e diz se e como a câmera virtual NEURELIX pode entrar nele.

Uso: inspect_app.py <app.apk> [--json]

Lê só os bytes do APK (manifest, dex, libs). Não decompila e não modifica nada.
Classificação (ordem de prioridade):
  BLOQUEADO            checagem de integridade/identidade (Play Integrity, SafetyNet, biometria, KYC)
  SEM_CAMERA           não pede CAMERA ou não referencia APIs de câmera
  CAMERA2_INTERCEPTAVEL  usa Camera2 (diretamente ou via CameraX): hook nas sessões Camera2
  CAMERA1_NAO_SUPORTADO  só Camera1 (android.hardware.Camera): precisa de hook próprio
  OUTRO                 caso não previsto
"""
import json, re, sys, zipfile

INTEGRITY = {
    "Play Integrity": ["Lcom/google/android/play/core/integrity", "Lcom/google/android/play/integrity"],
    "SafetyNet": ["Lcom/google/android/gms/safetynet"],
    "Biometria": ["Landroidx/biometric/", "Landroid/hardware/biometrics/", "Landroid/security/keystore/KeyGenParameterSpec"],
    "Attestation/root": ["Lcom/scottyab/rootbeer", "Lcom/google/android/gms/tasks/Task", "KeyAttestation"],
}
CAM2 = ["Landroid/hardware/camera2/CameraDevice;", "Landroid/hardware/camera2/CameraCaptureSession;", "Landroid/hardware/camera2/CaptureRequest$Builder;"]
CAMX = ["Landroidx/camera/core/", "Landroidx/camera/camera2/", "Landroidx/camera/lifecycle/"]
CAM1 = ["Landroid/hardware/Camera;"]
ONNX = ["ai/onnxruntime/"]

def scan(apk):
    z = zipfile.ZipFile(apk)
    names = z.namelist()
    dex = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
    blob = b"".join(z.read(n) for n in dex)
    def has(lst): return sorted({p for p in lst if p.encode() in blob})
    manifest = z.read("AndroidManifest.xml")
    # manifest binário (AXML) guarda strings em UTF-16LE: busca nas duas codificações
    perms = (b"android.permission.CAMERA" in manifest) or ("android.permission.CAMERA".encode("utf-16-le") in manifest)
    libs = sorted({n.split("/")[-1] for n in names if n.startswith("lib/") and n.endswith(".so")})
    models = [n for n in names if n.endswith((".onnx", ".tflite", ".pt", ".ptl"))]
    return {
        "dex_arquivos": len(dex), "pede_CAMERA": perms,
        "integridade": {k: has(v) for k, v in INTEGRITY.items() if has(v)},
        "camera2": has(CAM2), "camerax": has(CAMX), "camera1": has(CAM1),
        "onnx": bool(has(ONNX)), "modelos": models, "libs_nativas": libs,
        "createCaptureSession_presente": b"createCaptureSession" in blob,
        "addTarget_presente": b"addTarget" in blob,
    }

def classify(r):
    if any(r["integridade"].get(k) for k in ("Play Integrity", "SafetyNet", "Biometria", "Attestation/root")):
        return "BLOQUEADO", "checagem de integridade/identidade encontrada: não é alvo (regra do projeto)"
    if not r["pede_CAMERA"] and not (r["camera2"] or r["camerax"] or r["camera1"]):
        return "SEM_CAMERA", "o app não usa câmera"
    if r["camera2"] or r["camerax"]:
        via = "CameraX (sobre Camera2)" if r["camerax"] else "Camera2 direto"
        if not r["createCaptureSession_presente"]:
            return "OUTRO", f"{via}, mas não achei createCaptureSession"
        return "CAMERA2_INTERCEPTAVEL", f"{via}: dá para trocar as Surfaces de saída da sessão pelas do NEURELIX"
    if r["camera1"]:
        return "CAMERA1_NAO_SUPORTADO", "usa a API antiga Camera1: precisa de hook próprio (não implementado)"
    return "OUTRO", "caso não previsto"

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    r = scan(sys.argv[1])
    status, why = classify(r)
    r["status"], r["motivo"] = status, why
    if "--json" in sys.argv:
        print(json.dumps(r, indent=2, ensure_ascii=False))
    else:
        print(f"status : {status}")
        print(f"motivo : {why}")
        for k, v in r.items():
            if k not in ("status", "motivo"):
                print(f"{k:32}: {v}")
