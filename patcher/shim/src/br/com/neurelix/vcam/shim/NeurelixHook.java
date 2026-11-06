package br.com.neurelix.vcam.shim;

import android.util.Log;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.CameraControllerVirtual;
import android.content.Context;

/**
 * Ponto de entrada chamado pelo smali injetado em Preview.openCameraCore(). Sempre devolve o controlador virtual;
 * se o NEURELIX não estiver disponível, devolve null e o app segue com a câmera física (para não quebrar o app).
 */
public final class NeurelixHook {
    private static final String TAG = "NVC-SHIM";

    private NeurelixHook() { }

    public static CameraController openCamera(Context context, int cameraId, String cameraIdSPhysical) {
        try {
            Log.i(TAG, "abrindo câmera virtual NEURELIX no lugar da câmera " + cameraId);
            return new CameraControllerVirtual(context.getApplicationContext(), cameraId);
        } catch (Throwable t) {
            Log.e(TAG, "câmera virtual indisponível, usando câmera física: " + t);
            return null;
        }
    }
}
