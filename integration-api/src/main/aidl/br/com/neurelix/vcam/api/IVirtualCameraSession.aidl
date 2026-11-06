package br.com.neurelix.vcam.api;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import br.com.neurelix.vcam.api.StreamRequest;
import br.com.neurelix.vcam.api.StreamConfig;
import br.com.neurelix.vcam.api.SessionStats;

interface IVirtualCameraSession {
    /** Anexa a Surface do consumidor (SurfaceView, ImageReader RGBA_8888, codec). O motor desenha nela. */
    StreamConfig attachSurface(in Surface surface, in StreamRequest request);
    void detachSurface(int streamId);
    /** JPEG do quadro processado atual (mesmo master que alimenta as Surfaces). Lê-se do descritor devolvido. */
    ParcelFileDescriptor captureJpeg(int quality);
    SessionStats getStats();
    void close();
}
