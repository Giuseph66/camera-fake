package br.com.neurelix.vcam.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

object VcamApi {
    const val VERSION = 1
    const val SERVICE_PACKAGE = "br.com.neurelix.virtualcamera"
    const val SERVICE_CLASS = "br.com.neurelix.vcam.service.VirtualCameraService"
    const val ACTION_BIND = "br.com.neurelix.vcam.action.BIND_VIRTUAL_CAMERA"

    // ordinais de EngineState (espelho para consumidores que não dependem do :core)
    const val STATE_IDLE = 0
    const val STATE_STARTING = 1
    const val STATE_RUNNING_LOCAL = 2
    const val STATE_RUNNING_EXTERNAL = 3
    const val STATE_SOURCE_LOST = 4
    const val STATE_ERROR = 5

    const val FIT_CROP = 0
    const val FIT_FIT = 1
    const val FIT_STRETCH = 2
}

@Parcelize
data class ServiceStatus(
    val apiVersion: Int,
    val engineState: Int,
    val sourceLabel: String,
    val externalEnabled: Boolean,
    val callerAuthorized: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
) : Parcelable

@Parcelize
data class SessionRequest(val clientLabel: String, val apiVersion: Int = VcamApi.VERSION) : Parcelable

@Parcelize
data class StreamRequest(val width: Int, val height: Int, val fps: Int = 30, val fit: Int = VcamApi.FIT_CROP) : Parcelable

@Parcelize
data class StreamConfig(
    val streamId: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val sourceLabel: String,
    val format: String,
) : Parcelable

@Parcelize
data class SessionStats(val sessionId: Long, val framesDelivered: Long, val framesDropped: Long, val streams: Int) : Parcelable
