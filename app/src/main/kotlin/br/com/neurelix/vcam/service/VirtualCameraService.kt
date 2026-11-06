package br.com.neurelix.vcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import br.com.neurelix.vcam.App
import br.com.neurelix.vcam.MainActivity
import br.com.neurelix.vcam.core.diagnostics.AppLog
import br.com.neurelix.vcam.core.model.SourceKind

/**
 * Serviço em primeiro plano: (1) mantém o processo vivo enquanto a fonte virtual estiver ativa e mostra a
 * notificação permanente "Fonte virtual ativa" (transparência), (2) expõe o Binder para consumidores integrados.
 */
class VirtualCameraService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        AppLog.i("binder", "service", "bind de ${intent?.action}")
        return App.controller.sessions.createBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                App.controller.emergencyStop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                // precisa chamar startForeground antes de parar (contrato de startForegroundService)
                goForeground()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> goForeground()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Fonte virtual ativa", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, VirtualCameraService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE)
        val label = App.controller.sourceLabel.value
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Fonte virtual ativa")
            .setContentText("NEURELIX: $label")
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "PARAR TUDO", stop)
            .build()
        val cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val usesCamera = App.controller.activeSpec.value?.kind.let { it == SourceKind.CAMERA_BACK || it == SourceKind.CAMERA_FRONT }
        val type = if (Build.VERSION.SDK_INT >= 34) {
            if (cameraGranted && usesCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (cameraGranted && usesCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        runCatching { if (type != 0) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n) }
            .onFailure { AppLog.e("app", "service", "startForeground falhou", it); startForeground(NOTIF_ID, n) }
    }

    override fun onDestroy() {
        AppLog.i("app", "service", "serviço encerrado")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "br.com.neurelix.vcam.action.START"
        const val ACTION_STOP_SERVICE = "br.com.neurelix.vcam.action.STOP_SERVICE"
        const val ACTION_STOP_ALL = "br.com.neurelix.vcam.action.STOP_ALL"
        private const val CH = "active"
        private const val NOTIF_ID = 42
    }
}
