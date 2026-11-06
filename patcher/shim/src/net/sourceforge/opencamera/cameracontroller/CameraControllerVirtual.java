package net.sourceforge.opencamera.cameracontroller;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.location.Location;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.TextureView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import br.com.neurelix.vcam.shim.NeurelixSession;

/**
 * Controlador virtual injetado no APK do Open Camera. Em vez de abrir o sensor, abre uma sessão no NEURELIX
 * (Binder + autorização do usuário) e entrega a Surface do TextureView. Foto = JPEG do quadro processado.
 * Sem vídeo. Mesma implementação do fork em third_party/opencamera (ver docs do patcher).
 */
public class CameraControllerVirtual extends CameraController {
    private static final String TAG = "CameraControllerVirtual";

    private final Context context;
    private final ErrorCallback error_cb = null;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("nvc-virtual-cam");
    private final Handler bg;
    private final NeurelixSession session;
    private volatile int stream_id = -1;
    private TextureView texture_view;
    private Surface surface;
    private Size preview_size = new Size(1280, 720);
    private Size picture_size = new Size(1280, 720);
    private int jpeg_quality = 92;

    public CameraControllerVirtual(Context context, int cameraId) throws CameraControllerException {
        super(cameraId);
        this.context = context;
        thread.start();
        bg = new Handler(thread.getLooper());
        session = new NeurelixSession(context);
        try {
            session.open("Open Camera (NEURELIX)", 4000);
            Log.i(TAG, "sessão NEURELIX aberta");
        } catch (Throwable t) {
            Log.e(TAG, "NEURELIX recusou/indisponível: " + t.getMessage());
            toast("NEURELIX: " + t.getMessage());
            closeAll();
            throw new CameraControllerException();
        }
    }

    private void toast(final String m) {
        main.post(new Runnable() { @Override public void run() { Toast.makeText(context, m, Toast.LENGTH_LONG).show(); } });
    }

    private void closeAll() {
        detachStream();
        session.close();
        thread.quitSafely();
    }

    private synchronized void detachStream() {
        if (stream_id >= 0) session.detach(stream_id);
        stream_id = -1;
        if (surface != null) { surface.release(); surface = null; }
    }

    private void startPreviewImpl(final Runnable runnable, final Runnable on_failed) {
        bg.post(new Runnable() {
            @Override public void run() {
                try {
                    if (texture_view == null || texture_view.getSurfaceTexture() == null)
                        throw new IllegalStateException("sem TextureView");
                    SurfaceTexture st = texture_view.getSurfaceTexture();
                    int w = preview_size.height, h = preview_size.width;   // buffer em retrato
                    st.setDefaultBufferSize(w, h);
                    detachStream();
                    surface = new Surface(st);
                    stream_id = session.attach(surface, w, h, 30, 0);
                    Log.i(TAG, "preview anexado: stream " + stream_id);
                    if (runnable != null) main.post(runnable);
                } catch (Throwable t) {
                    Log.e(TAG, "falha ao iniciar preview virtual", t);
                    toast("NEURELIX: " + t.getMessage());
                    if (on_failed != null) main.post(on_failed);
                }
            }
        });
    }

    private void takePictureImpl(final PictureCallback picture, final ErrorCallback error) {
        bg.post(new Runnable() {
            @Override public void run() {
                try {
                    final byte[] jpeg = session.captureJpeg(jpeg_quality);
                    Log.i(TAG, "captura virtual: " + jpeg.length + " bytes");
                    main.post(new Runnable() {
                        @Override public void run() {
                            picture.onStarted();
                            picture.onPictureTaken(jpeg);
                            picture.onCompleted();
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "captura virtual falhou", t);
                    toast("NEURELIX: " + t.getMessage());
                    if (error != null) main.post(new Runnable() { @Override public void run() { error.onError(); } });
                }
            }
        });
    }

    @Override
    public void updatePreviewTexture() {
        if (texture_view != null && texture_view.getSurfaceTexture() != null)
            texture_view.getSurfaceTexture().setDefaultBufferSize(preview_size.height, preview_size.width);
    }

    private CameraFeatures buildFeatures() {
        CameraFeatures f = new CameraFeatures();
        List<Size> sizes = new ArrayList<>();
        sizes.add(new Size(1920, 1080)); sizes.add(new Size(1280, 720)); sizes.add(new Size(960, 540)); sizes.add(new Size(640, 360));
        f.picture_sizes = sizes;
        f.preview_sizes = new ArrayList<>(sizes);
        f.video_sizes = new ArrayList<>();
        f.video_sizes_high_speed = null;
        f.is_zoom_supported = false; f.max_zoom = 0; f.zoom_ratios = new ArrayList<>();
        f.supports_face_detection = false;
        f.supported_flash_values = null; f.supported_focus_values = null; f.apertures = null;
        f.max_num_focus_areas = 0; f.minimum_focus_distance = 0f;
        f.is_exposure_lock_supported = false; f.is_white_balance_lock_supported = false;
        f.is_optical_stabilization_supported = false; f.is_video_stabilization_supported = false;
        f.is_photo_video_recording_supported = false;
        f.supports_white_balance_temperature = false; f.supports_iso_range = false; f.supports_exposure_time = false;
        f.min_exposure = 0; f.max_exposure = 0; f.exposure_step = 1f;
        f.can_disable_shutter_sound = true;
        f.tonemap_max_curve_points = 0; f.supports_tonemap_curve = false;
        f.supports_expo_bracketing = false; f.supports_focus_bracketing = false; f.supports_burst = false;
        f.supports_jpeg_r = false; f.supports_raw = false;
        f.view_angle_x = 60f; f.view_angle_y = 38f;
        return f;
    }

    @Override
    public void release()  {
        closeAll();
    }

    @Override
    public void appIsPaused()  {
        
    }

    @Override
    public void onError()  {
        if (error_cb != null) error_cb.onError();
    }

    @Override
    public String getAPI()  {
        return "NEURELIX-Virtual";
    }

    @Override
    public CameraFeatures getCameraFeatures() throws CameraControllerException {
        return buildFeatures();
    }

    @Override
    public List<Integer> setZoomSticky(boolean sticky)  {
        return null;
    }

    @Override
    public SupportedValues setSceneMode(String value)  {
        return null;
    }

    @Override
    public String getSceneMode()  {
        return SCENE_MODE_DEFAULT;
    }

    @Override
    public boolean sceneModeAffectsFunctionality()  {
        return false;
    }

    @Override
    public SupportedValues setColorEffect(String value)  {
        return null;
    }

    @Override
    public String getColorEffect()  {
        return COLOR_EFFECT_DEFAULT;
    }

    @Override
    public SupportedValues setWhiteBalance(String value)  {
        return null;
    }

    @Override
    public String getWhiteBalance()  {
        return WHITE_BALANCE_DEFAULT;
    }

    @Override
    public boolean setWhiteBalanceTemperature(int temperature)  {
        return false;
    }

    @Override
    public int getWhiteBalanceTemperature()  {
        return 0;
    }

    @Override
    public SupportedValues setAntiBanding(String value)  {
        return null;
    }

    @Override
    public String getAntiBanding()  {
        return ANTIBANDING_DEFAULT;
    }

    @Override
    public SupportedValues setEdgeMode(String value)  {
        return null;
    }

    @Override
    public String getEdgeMode()  {
        return EDGE_MODE_DEFAULT;
    }

    @Override
    public SupportedValues setNoiseReductionMode(String value)  {
        return null;
    }

    @Override
    public String getNoiseReductionMode()  {
        return NOISE_REDUCTION_MODE_DEFAULT;
    }

    @Override
    public SupportedValues setISO(String value)  {
        return null;
    }

    @Override
    public void setManualISO(boolean manual_iso, int iso)  {
        
    }

    @Override
    public boolean isManualISO()  {
        return false;
    }

    @Override
    public boolean setISO(int iso)  {
        return false;
    }

    @Override
    public String getISOKey()  {
        return "";
    }

    @Override
    public int getISO()  {
        return 0;
    }

    @Override
    public long getExposureTime()  {
        return 0L;
    }

    @Override
    public boolean setExposureTime(long exposure_time)  {
        return false;
    }

    @Override
    public void setAperture(float aperture)  {
        
    }

    @Override
    public CameraController.Size getPictureSize()  {
        return picture_size;
    }

    @Override
    public void setPictureSize(int width, int height)  {
        picture_size = new Size(width, height);
    }

    @Override
    public CameraController.Size getPreviewSize()  {
        return preview_size;
    }

    @Override
    public void setPreviewSize(int width, int height)  {
        preview_size = new Size(width, height);
    }

    @Override
    public void setCameraExtension(boolean enabled, int extension)  {
        
    }

    @Override
    public boolean isCameraExtension()  {
        return false;
    }

    @Override
    public int getCameraExtension()  {
        return 0;
    }

    @Override
    public void setBurstType(BurstType new_burst_type)  {
        
    }

    @Override
    public BurstType getBurstType()  {
        return BurstType.BURSTTYPE_NONE;
    }

    @Override
    public void setBurstNImages(int burst_requested_n_images)  {
        
    }

    @Override
    public void setBurstForNoiseReduction(boolean burst_for_noise_reduction, boolean noise_reduction_low_light)  {
        
    }

    @Override
    public boolean isContinuousBurstInProgress()  {
        return false;
    }

    @Override
    public void stopContinuousBurst()  {
        
    }

    @Override
    public void stopFocusBracketingBurst()  {
        
    }

    @Override
    public void setExpoBracketingNImages(int n_images)  {
        
    }

    @Override
    public void setExpoBracketingStops(double stops)  {
        
    }

    @Override
    public void setUseExpoFastBurst(boolean use_expo_fast_burst)  {
        
    }

    @Override
    public void setDummyCaptureHack(boolean dummy_capture_hack)  {
        
    }

    @Override
    public boolean isCaptureFastBurst()  {
        return false;
    }

    @Override
    public boolean isCapturingBurst()  {
        return false;
    }

    @Override
    public int getNBurstTaken()  {
        return 0;
    }

    @Override
    public int getBurstTotal()  {
        return 0;
    }

    @Override
    public void setJpegR(boolean want_jpeg_r)  {
        
    }

    @Override
    public void setRaw(boolean want_raw, int max_raw_images)  {
        
    }

    @Override
    public void setVideoHighSpeed(boolean setVideoHighSpeed)  {
        
    }

    @Override
    public boolean getOpticalStabilization()  {
        return false;
    }

    @Override
    public void setVideoStabilization(boolean enabled)  {
        
    }

    @Override
    public boolean getVideoStabilization()  {
        return false;
    }

    @Override
    public void setTonemapProfile(TonemapProfile tonemap_profile, float log_profile_strength, float gamma)  {
        
    }

    @Override
    public TonemapProfile getTonemapProfile()  {
        return TonemapProfile.TONEMAPPROFILE_OFF;
    }

    @Override
    public int getJpegQuality()  {
        return jpeg_quality;
    }

    @Override
    public void setJpegQuality(int quality)  {
        jpeg_quality = quality;
    }

    @Override
    public int getZoom()  {
        return 0;
    }

    @Override
    public void setZoom(int value)  {
        
    }

    @Override
    public void setZoom(int value, float smooth_zoom)  {
        
    }

    @Override
    public void resetZoom()  {
        
    }

    @Override
    public int getExposureCompensation()  {
        return 0;
    }

    @Override
    public boolean setExposureCompensation(int new_exposure)  {
        return false;
    }

    @Override
    public void setPreviewFpsRange(int min, int max)  {
        
    }

    @Override
    public void clearPreviewFpsRange()  {
        
    }

    @Override
    public List<int []> getSupportedPreviewFpsRange()  {
        List<int[]> l = new ArrayList<>(); l.add(new int[]{15000, 30000}); l.add(new int[]{30000, 30000}); return l;
    }

    @Override
    public void setFocusValue(String focus_value)  {
        
    }

    @Override
    public String getFocusValue()  {
        return "focus_mode_auto";
    }

    @Override
    public float getFocusDistance()  {
        return 0f;
    }

    @Override
    public boolean setFocusDistance(float focus_distance)  {
        return false;
    }

    @Override
    public void setFocusBracketingNImages(int n_images)  {
        
    }

    @Override
    public void setFocusBracketingAddInfinity(boolean focus_bracketing_add_infinity)  {
        
    }

    @Override
    public void setFocusBracketingSourceDistance(float focus_bracketing_source_distance)  {
        
    }

    @Override
    public float getFocusBracketingSourceDistance()  {
        return 0f;
    }

    @Override
    public void setFocusBracketingSourceDistanceFromCurrent()  {
        
    }

    @Override
    public void setFocusBracketingTargetDistance(float focus_bracketing_target_distance)  {
        
    }

    @Override
    public float getFocusBracketingTargetDistance()  {
        return 0f;
    }

    @Override
    public void setFlashValue(String flash_value)  {
        
    }

    @Override
    public String getFlashValue()  {
        return "flash_off";
    }

    @Override
    public void setRecordingHint(boolean hint)  {
        
    }

    @Override
    public void setAutoExposureLock(boolean enabled)  {
        
    }

    @Override
    public boolean getAutoExposureLock()  {
        return false;
    }

    @Override
    public void setAutoWhiteBalanceLock(boolean enabled)  {
        
    }

    @Override
    public boolean getAutoWhiteBalanceLock()  {
        return false;
    }

    @Override
    public void setRotation(int rotation)  {
        
    }

    @Override
    public void setLocationInfo(Location location)  {
        
    }

    @Override
    public void removeLocationInfo()  {
        
    }

    @Override
    public void enableShutterSound(boolean enabled)  {
        
    }

    @Override
    public boolean setFocusAndMeteringArea(List<CameraController.Area> areas)  {
        return false;
    }

    @Override
    public void clearFocusAndMetering()  {
        
    }

    @Override
    public List<CameraController.Area> getFocusAreas()  {
        return null;
    }

    @Override
    public List<CameraController.Area> getMeteringAreas()  {
        return null;
    }

    @Override
    public boolean supportsAutoFocus()  {
        return false;
    }

    @Override
    public boolean supportsMetering()  {
        return false;
    }

    @Override
    public boolean focusIsContinuous()  {
        return false;
    }

    @Override
    public boolean focusIsVideo()  {
        return false;
    }

    @Override
    public void reconnect(boolean restart_preview) throws CameraControllerException {
        detachStream(); /* o Preview chama startPreview em seguida */
    }

    @Override
    public void setPreviewDisplay(SurfaceHolder holder) throws CameraControllerException {
        throw new CameraControllerException(); // só TextureView (Camera2-like) é suportado
    }

    @Override
    public void setPreviewTexture(TextureView texture) throws CameraControllerException {
        this.texture_view = texture;
    }

    @Override
    public void startPreview(boolean wait_until_started, Runnable runnable, Runnable on_failed) throws CameraControllerException {
        startPreviewImpl(runnable, on_failed);
    }

    @Override
    public void stopRepeating()  {
        detachStream();
    }

    @Override
    public void stopPreview()  {
        detachStream();
    }

    @Override
    public boolean startFaceDetection()  {
        return false;
    }

    @Override
    public void setFaceDetectionListener(final CameraController.FaceDetectionListener listener)  {
        
    }

    @Override
    public void autoFocus(final CameraController.AutoFocusCallback cb, boolean capture_follows_autofocus_hint)  {
        if (cb != null) cb.onAutoFocus(true);
    }

    @Override
    public void setCaptureFollowAutofocusHint(boolean capture_follows_autofocus_hint)  {
        
    }

    @Override
    public void cancelAutoFocus()  {
        
    }

    @Override
    public void setContinuousFocusMoveCallback(ContinuousFocusMoveCallback cb)  {
        
    }

    @Override
    public void takePicture(final CameraController.PictureCallback picture, final ErrorCallback error)  {
        takePictureImpl(picture, error);
    }

    @Override
    public void setDisplayOrientation(int degrees)  {
        
    }

    @Override
    public int getDisplayOrientation()  {
        return 0;
    }

    @Override
    public int getCameraOrientation()  {
        return 90;
    }

    @Override
    public Facing getFacing()  {
        return Facing.FACING_EXTERNAL;
    }

    @Override
    public void unlock()  {
        
    }

    @Override
    public void initVideoRecorderPrePrepare(MediaRecorder video_recorder)  {
        
    }

    @Override
    public void initVideoRecorderPostPrepare(MediaRecorder video_recorder, boolean want_photo_video_recording) throws CameraControllerException {
        throw new CameraControllerException(); // vídeo não suportado na câmera virtual (ainda)
    }

    @Override
    public String getParametersString()  {
        return "NEURELIX virtual camera (" + preview_size.width + "x" + preview_size.height + ")";
    }

}
