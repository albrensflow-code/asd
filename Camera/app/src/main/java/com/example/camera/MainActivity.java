package com.example.camera;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapabilities;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends ComponentActivity {
    private static final int MODE_PHOTO = 0;
    private static final int MODE_HDR = 1;
    private static final int MODE_NIGHT = 2;
    private static final int MODE_PORTRAIT = 3;
    private static final int MODE_VIDEO = 4;

    private static final int FLASH_AUTO = 0;
    private static final int FLASH_ON = 1;
    private static final int FLASH_OFF = 2;

    private static final int AWB_AUTO = CaptureRequest.CONTROL_AWB_MODE_AUTO;

    private PreviewView previewView;
    private TextView statusText;
    private TextView previewBadge;
    private TextView focusHint;
    private TextView flashButton;
    private TextView lensButton;
    private TextView timerButton;
    private TextView gridButton;
    private FrameLayout shutterButton;
    private View shutterCore;
    private GridOverlay gridOverlay;
    private LinearLayout zoomRow;
    private LinearLayout modeRow;

    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private boolean torchOn = false;
    private ProcessCameraProvider cameraProvider;
    private ExtensionsManager extensionsManager;
    private Camera camera;
    private CameraManager cameraManager;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService colorWorker = Executors.newSingleThreadExecutor();
    private int timerIndex = 0;
    private final int[] timerValues = {0, 3, 5, 10};
    private boolean countdownActive = false;
    private boolean gridEnabled = false;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private final List<String> backCameraIds = new ArrayList<>();
    private final List<String> frontCameraIds = new ArrayList<>();
    private final List<Button> modeButtons = new ArrayList<>();
    private String selectedBackCameraId;
    private int backLensIndex = 0;
    private int activeMode = MODE_PHOTO;
    private int flashMode = FLASH_AUTO;
    private int whiteBalanceMode = AWB_AUTO;
    private int exposureCompensationIndex = 0;
    private int manualIso = 200;
    private long manualExposureNs = 10_000_000L;
    private boolean manualExposureEnabled = false;
    private boolean isFrontCamera = false;
    private boolean flashAvailable = false;
    private Uri lastPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(8, 10, 14));
        getWindow().setNavigationBarColor(Color.rgb(8, 10, 14));
        getWindow().getDecorView().setSystemUiVisibility(0);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) startCamera();
                    else {
                        setStatus("اسمح للتطبيق باستخدام الكاميرا حتى تظهر المعاينة.");
                        Toast.makeText(this, "إذن الكاميرا مطلوب", Toast.LENGTH_LONG).show();
                    }
                });
        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> beginVideoRecording(granted));

        setContentView(createScreen());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private View createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(Color.rgb(8, 10, 14));
        root.setPadding(dp(12), dp(7), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(3), 0, dp(3), dp(7));

        TextView brand = new TextView(this);
        brand.setText("REDMI  •  CAMERA");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(15);
        brand.setLetterSpacing(0.08f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(44), 1f));

        timerButton = iconButton("⏱ OFF", 58);
        timerButton.setOnClickListener(v -> cycleTimer());
        header.addView(timerButton, new LinearLayout.LayoutParams(dp(58), dp(44)));

        gridButton = iconButton("▦", 42);
        gridButton.setOnClickListener(v -> toggleGrid());
        LinearLayout.LayoutParams gridButtonParams = new LinearLayout.LayoutParams(dp(42), dp(44));
        gridButtonParams.leftMargin = dp(5);
        header.addView(gridButton, gridButtonParams);

        flashButton = iconButton("⚡ AUTO", 68);
        flashButton.setOnClickListener(v -> cycleFlash());
        LinearLayout.LayoutParams flashParams = new LinearLayout.LayoutParams(dp(68), dp(44));
        flashParams.leftMargin = dp(5);
        header.addView(flashButton, flashParams);

        TextView colorButton = iconButton("🎨", 42);
        LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(dp(42), dp(44));
        colorParams.leftMargin = dp(5);
        header.addView(colorButton, colorParams);
        colorButton.setOnClickListener(v -> openColorLab());

        TextView settingsButton = iconButton("⚙", 42);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(42), dp(44));
        settingsParams.leftMargin = dp(5);
        header.addView(settingsButton, settingsParams);
        settingsButton.setOnClickListener(v -> showProSettings());

        root.addView(header);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(roundRect(Color.rgb(17, 21, 28), 22, 0, Color.TRANSPARENT));
        previewFrame.setClipToOutline(true);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        gridOverlay = new GridOverlay(this);
        previewFrame.addView(gridOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        gridOverlay.setVisibility(View.GONE);

        previewBadge = overlayLabel("AUTO  •  PHOTO", 12);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(30), Gravity.TOP | Gravity.LEFT);
        badgeParams.setMargins(dp(12), dp(12), 0, 0);
        previewFrame.addView(previewBadge, badgeParams);

        focusHint = overlayLabel("المس الشاشة للتركيز", 11);
        FrameLayout.LayoutParams focusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(28), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        focusParams.bottomMargin = dp(12);
        previewFrame.addView(focusHint, focusParams);

        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                focusAt(event.getX(), event.getY());
                return true;
            }
            return true;
        });

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.bottomMargin = dp(9);
        root.addView(previewFrame, previewParams);

        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(0, dp(1), 0, dp(7));
        root.addView(zoomRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.setFillViewport(false);
        modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        modeScroll.addView(modeRow);
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(47));
        modeParams.bottomMargin = dp(5);
        root.addView(modeScroll, modeParams);
        addModeButton("صورة", MODE_PHOTO);
        addModeButton("HDR", MODE_HDR);
        addModeButton("ليل", MODE_NIGHT);
        addModeButton("بورتريه", MODE_PORTRAIT);
        addModeButton("فيديو", MODE_VIDEO);
        updateModeButtons();

        LinearLayout captureRow = new LinearLayout(this);
        captureRow.setOrientation(LinearLayout.HORIZONTAL);
        captureRow.setGravity(Gravity.CENTER);
        captureRow.setPadding(dp(8), dp(3), dp(8), dp(2));

        TextView galleryButton = iconButton("▣", 52);
        galleryButton.setOnClickListener(v -> openGallery());
        captureRow.addView(galleryButton, new LinearLayout.LayoutParams(dp(56), dp(56)));

        FrameLayout shutterHolder = new FrameLayout(this);
        shutterButton = createShutter();
        shutterHolder.addView(shutterButton, new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER));
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(0, dp(88), 1f);
        captureRow.addView(shutterHolder, shutterParams);

        TextView switchButton = iconButton("↻", 52);
        switchButton.setOnClickListener(v -> switchCamera());
        captureRow.addView(switchButton, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(94));
        root.addView(captureRow, captureParams);

        statusText = new TextView(this);
        statusText.setText("جارٍ تجهيز الكاميرا...");
        statusText.setTextColor(Color.rgb(165, 173, 185));
        statusText.setTextSize(10);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(21)));

        return root;
    }

    private FrameLayout createShutter() {
        FrameLayout outer = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(4), Color.WHITE);
        outer.setBackground(ring);
        outer.setClickable(true);
        outer.setFocusable(true);

        View inner = new View(this);
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(Color.rgb(248, 248, 246));
        inner.setBackground(core);
        FrameLayout.LayoutParams innerParams = new FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER);
        outer.addView(inner, innerParams);

        outer.setOnClickListener(v -> onShutterPressed());
        outer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) v.setScaleX(0.94f);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
            return false;
        });
        return outer;
    }

    private TextView iconButton(String label, int widthDp) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(roundRect(Color.rgb(31, 37, 47), 18, 0, Color.TRANSPARENT));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private TextView overlayLabel(String label, int size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(11), 0, dp(11), 0);
        view.setBackground(roundRect(0x99090C12, 30, 0, Color.TRANSPARENT));
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private Button pillButton(String text, boolean selected) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(selected ? Color.rgb(12, 14, 17) : Color.rgb(224, 228, 235));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(roundRect(
                selected ? Color.rgb(230, 190, 102) : Color.rgb(31, 37, 47),
                24, selected ? 0 : 1, Color.rgb(54, 62, 75)));
        return button;
    }

    private void addModeButton(String label, int mode) {
        Button button = pillButton(label, mode == activeMode);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(39));
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        modeRow.addView(button, params);
        modeButtons.add(button);
        button.setOnClickListener(v -> {
            if (activeMode == mode) return;
            activeMode = mode;
            updateModeButtons();
            startCamera();
        });
    }

    private void updateModeButtons() {
        int[] modes = {MODE_PHOTO, MODE_HDR, MODE_NIGHT, MODE_PORTRAIT, MODE_VIDEO};
        for (int i = 0; i < modeButtons.size(); i++) {
            boolean selected = modes[i] == activeMode;
            Button button = modeButtons.get(i);
            button.setTextColor(selected ? Color.rgb(12, 14, 17) : Color.rgb(224, 228, 235));
            button.setBackground(roundRect(
                    selected ? Color.rgb(230, 190, 102) : Color.rgb(31, 37, 47),
                    24, selected ? 0 : 1, Color.rgb(54, 62, 75)));
        }
        String name = modeName(activeMode);
        if (previewBadge != null) previewBadge.setText("AUTO  •  " + name.toUpperCase(Locale.ROOT));
    }

    private String modeName(int mode) {
        if (mode == MODE_HDR) return "HDR";
        if (mode == MODE_NIGHT) return "ليل";
        if (mode == MODE_PORTRAIT) return "بورتريه";
        if (mode == MODE_VIDEO) return "فيديو";
        return "صورة";
    }

    private int extensionMode(int mode) {
        if (mode == MODE_HDR) return ExtensionMode.HDR;
        if (mode == MODE_NIGHT) return ExtensionMode.NIGHT;
        if (mode == MODE_PORTRAIT) return ExtensionMode.BOKEH;
        return ExtensionMode.NONE;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startCamera() {
        if (previewView == null) return;
        setCaptureEnabled(false);
        setStatus("جارٍ تشغيل الكاميرا...");
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(getApplicationContext());
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                if (extensionsManager == null) {
                    ListenableFuture<ExtensionsManager> extensionFuture =
                            ExtensionsManager.getInstanceAsync(getApplicationContext(), cameraProvider);
                    extensionFuture.addListener(() -> {
                        try {
                            extensionsManager = extensionFuture.get();
                        } catch (Exception ignored) {
                            extensionsManager = null;
                        }
                        refreshCameraLists();
                        bindCamera();
                    }, ContextCompat.getMainExecutor(this));
                } else {
                    refreshCameraLists();
                    bindCamera();
                }
            } catch (Exception error) {
                setStatus("تعذر تهيئة الكاميرا: " + safeMessage(error));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void refreshCameraLists() {
        backCameraIds.clear();
        frontCameraIds.clear();
        if (cameraProvider == null) return;
        for (CameraInfo info : cameraProvider.getAvailableCameraInfos()) {
            try {
                String id = Camera2CameraInfo.from(info).getCameraId();
                Integer facing = info.getLensFacing();
                if (facing != null && facing == CameraSelector.LENS_FACING_BACK) {
                    if (!backCameraIds.contains(id)) backCameraIds.add(id);
                } else if (facing != null && facing == CameraSelector.LENS_FACING_FRONT) {
                    if (!frontCameraIds.contains(id)) frontCameraIds.add(id);
                }
            } catch (Exception ignored) {
                // Some OEM camera implementations do not expose Camera2 metadata to CameraX.
            }
        }
        if (selectedBackCameraId == null && !backCameraIds.isEmpty()) {
            selectedBackCameraId = backCameraIds.get(0);
        }
        if (selectedBackCameraId != null && !backCameraIds.contains(selectedBackCameraId)
                && !backCameraIds.isEmpty()) {
            selectedBackCameraId = backCameraIds.get(0);
            backLensIndex = 0;
        }
        updateLensButton();
    }

    private CameraSelector baseCameraSelector() {
        if (isFrontCamera || backCameraIds.isEmpty()) {
            return isFrontCamera
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;
        }
        final String targetId = selectedBackCameraId;
        return new CameraSelector.Builder().addCameraFilter(cameraInfos -> {
            List<CameraInfo> filtered = new ArrayList<>();
            for (CameraInfo info : cameraInfos) {
                try {
                    if (targetId.equals(Camera2CameraInfo.from(info).getCameraId())) filtered.add(info);
                } catch (Exception ignored) {
                }
            }
            return filtered;
        }).build();
    }

    private void bindCamera() {
        if (cameraProvider == null || previewView == null) return;
        try {
            CameraSelector baseSelector = baseCameraSelector();
            CameraSelector finalSelector = baseSelector;

            if (!isFrontCamera && activeMode != MODE_PHOTO && activeMode != MODE_VIDEO && extensionsManager != null) {
                int ext = extensionMode(activeMode);
                if (extensionsManager.isExtensionAvailable(baseSelector, ext)) {
                    finalSelector = extensionsManager.getExtensionEnabledCameraSelector(baseSelector, ext);
                } else {
                    activeMode = MODE_PHOTO;
                    updateModeButtons();
                    Toast.makeText(this, "هذا الوضع غير مدعوم من تعريف الكاميرا؛ رجعنا للوضع العادي.", Toast.LENGTH_LONG).show();
                }
            } else if (activeMode != MODE_PHOTO && activeMode != MODE_VIDEO) {
                activeMode = MODE_PHOTO;
                updateModeButtons();
            }

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            cameraProvider.unbindAll();
            if (activeMode == MODE_VIDEO) {
                CameraInfo info = selectedCameraInfo();
                Recorder.Builder recorderBuilder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)));
                Recorder recorder = recorderBuilder.build();
                boolean stabilization = false;
                if (info != null) {
                    try { stabilization = Recorder.getVideoCapabilities(info).isStabilizationSupported(); }
                    catch (Exception ignored) { }
                }
                videoCapture = new VideoCapture.Builder<>(recorder)
                        .setVideoStabilizationEnabled(stabilization)
                        .build();
                imageCapture = null;
                camera = cameraProvider.bindToLifecycle(this, finalSelector, preview, videoCapture);
            } else {
                videoCapture = null;
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .build();
                camera = cameraProvider.bindToLifecycle(this, finalSelector, preview, imageCapture);
            }

            flashAvailable = camera.getCameraInfo().hasFlashUnit() && !isFrontCamera;
            flashButton.setEnabled(flashAvailable);
            flashButton.setAlpha(flashAvailable ? 1f : 0.45f);
            torchOn = false;
            updateFlashLabel();
            exposureCompensationIndex = camera.getCameraInfo().getExposureState()
                    .getExposureCompensationIndex();
            setCaptureEnabled(true);
            updateZoomButtons();
            updateLensButton();

            String lens = isFrontCamera ? "أمامية" : describeSelectedLens();
            setStatus("جاهزة • " + lens + " • " + modeName(activeMode));
            if (previewBadge != null) previewBadge.setText("AUTO  •  " + modeName(activeMode).toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            setCaptureEnabled(false);
            setStatus("لم تعمل هذه العدسة/الميزة: " + safeMessage(error));
        }
    }

    private CameraInfo selectedCameraInfo() {
        if (cameraProvider == null) return null;
        for (CameraInfo info : cameraProvider.getAvailableCameraInfos()) {
            try {
                String id = Camera2CameraInfo.from(info).getCameraId();
                String wanted = isFrontCamera ? (frontCameraIds.isEmpty() ? null : frontCameraIds.get(0)) : selectedBackCameraId;
                if (wanted != null && wanted.equals(id)) return info;
            } catch (Exception ignored) { }
        }
        return cameraProvider.getAvailableCameraInfos().isEmpty() ? null : cameraProvider.getAvailableCameraInfos().get(0);
    }

    private String describeSelectedLens() {
        if (selectedBackCameraId == null || cameraManager == null) return "خلفية";
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(selectedBackCameraId);
            float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focal != null && focal.length > 0) return String.format(Locale.US, "خلفية %.1fmm", focal[0]);
        } catch (Exception ignored) {
        }
        return "خلفية";
    }

    private void setCaptureEnabled(boolean enabled) {
        if (shutterButton != null) {
            shutterButton.setEnabled(enabled);
            shutterButton.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private void updateLensButton() {
        if (lensButton == null) return;
        if (backCameraIds.size() > 1 && !isFrontCamera) {
            lensButton.setVisibility(View.VISIBLE);
            lensButton.setText("عدسة " + (backLensIndex + 1) + "/" + backCameraIds.size());
        } else {
            lensButton.setVisibility(View.GONE);
        }
    }

    private void updateZoomButtons() {
        if (zoomRow == null) return;
        zoomRow.removeAllViews();
        if (camera == null) return;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state == null) return;
        float min = state.getMinZoomRatio();
        float max = state.getMaxZoomRatio();
        List<Float> values = new ArrayList<>();
        if (min <= 0.65f) values.add(0.6f);
        values.add(1.0f);
        if (max >= 2.0f) values.add(2.0f);
        if (max >= 3.0f) values.add(3.0f);
        for (Float ratio : values) {
            Button button = pillButton(String.format(Locale.US, "%.1fx", ratio), Math.abs(state.getZoomRatio() - ratio) < 0.15f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            params.leftMargin = dp(4);
            params.rightMargin = dp(4);
            zoomRow.addView(button, params);
            button.setOnClickListener(v -> setZoom(ratio));
        }
        if (lensButton == null) {
            lensButton = new TextView(this);
            lensButton.setTextColor(Color.rgb(240, 220, 180));
            lensButton.setTextSize(11);
            lensButton.setGravity(Gravity.CENTER);
            lensButton.setPadding(dp(9), 0, dp(9), 0);
            lensButton.setBackground(roundRect(Color.rgb(31, 37, 47), 20, 1, Color.rgb(70, 76, 85)));
            lensButton.setClickable(true);
            lensButton.setOnClickListener(v -> cycleBackLens());
        }
        if (backCameraIds.size() > 1 && !isFrontCamera) {
            lensButton.setVisibility(View.VISIBLE);
            lensButton.setText("عدسة " + (backLensIndex + 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            zoomRow.addView(lensButton, lp);
        }
    }

    private void setZoom(float ratio) {
        if (camera == null) return;
        try {
            ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            if (state == null) return;
            float bounded = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), ratio));
            camera.getCameraControl().setZoomRatio(bounded);
            updateZoomButtons();
        } catch (Exception error) {
            setStatus("التقريب غير متاح بهذه العدسة.");
        }
    }

    private void cycleBackLens() {
        if (backCameraIds.size() < 2) return;
        backLensIndex = (backLensIndex + 1) % backCameraIds.size();
        selectedBackCameraId = backCameraIds.get(backLensIndex);
        activeMode = MODE_PHOTO;
        updateModeButtons();
        startCamera();
    }

    private void switchCamera() {
        if (frontCameraIds.isEmpty() || backCameraIds.isEmpty()) {
            Toast.makeText(this, "لم يتم العثور على الكاميرتين الأمامية والخلفية.", Toast.LENGTH_SHORT).show();
            return;
        }
        isFrontCamera = !isFrontCamera;
        activeMode = MODE_PHOTO;
        manualExposureEnabled = false;
        updateModeButtons();
        startCamera();
    }

    private void cycleFlash() {
        if (!flashAvailable || camera == null) return;
        if (activeMode == MODE_VIDEO) {
            torchOn = !torchOn;
            camera.getCameraControl().enableTorch(torchOn);
            flashButton.setText(torchOn ? "☼ LIGHT ON" : "☼ LIGHT OFF");
            return;
        }
        if (imageCapture == null) return;
        flashMode = (flashMode + 1) % 3;
        imageCapture.setFlashMode(flashMode == FLASH_AUTO ? ImageCapture.FLASH_MODE_AUTO
                : flashMode == FLASH_ON ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        updateFlashLabel();
    }

    private void updateFlashLabel() {
        if (flashButton == null) return;
        if (activeMode == MODE_VIDEO) {
            flashButton.setText(torchOn ? "☼ LIGHT ON" : "☼ LIGHT OFF");
            return;
        }
        String label = flashMode == FLASH_AUTO ? "⚡ AUTO"
                : flashMode == FLASH_ON ? "⚡ ON" : "⚡ OFF";
        flashButton.setText(label);
    }


    private void focusAt(float x, float y) {
        if (camera == null || previewView == null) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
            camera.getCameraControl().startFocusAndMetering(action);
            if (focusHint != null) focusHint.setText("تم ضبط التركيز والتعريض");
        } catch (Exception ignored) {
            if (focusHint != null) focusHint.setText("التركيز التلقائي يعمل");
        }
    }

    private void onShutterPressed() {
        if (activeMode == MODE_VIDEO) {
            toggleVideoRecording();
        } else {
            capturePhoto();
        }
    }

    private void cycleTimer() {
        timerIndex = (timerIndex + 1) % timerValues.length;
        int seconds = timerValues[timerIndex];
        timerButton.setText(seconds == 0 ? "⏱ OFF" : "⏱ " + seconds + "s");
    }

    private void toggleGrid() {
        gridEnabled = !gridEnabled;
        if (gridOverlay != null) {
            gridOverlay.setGridVisible(gridEnabled);
            gridOverlay.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
        }
        if (gridButton != null) gridButton.setText(gridEnabled ? "▦ ON" : "▦");
    }

    private void openColorLab() {
        if (lastPhotoUri == null) {
            Toast.makeText(this, "التقط صورة أولًا حتى تفتح استوديو الألوان.", Toast.LENGTH_SHORT).show();
            return;
        }
        PhotoColorLab.show(this, lastPhotoUri, colorWorker, edited -> {
            lastPhotoUri = edited;
            setStatus("تم حفظ نسخة الألوان المعدّلة.");
        });
    }

    private void capturePhoto() {
        if (imageCapture == null || camera == null) {
            Toast.makeText(this, "انتظر حتى تجهز الكاميرا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (countdownActive) return;
        int seconds = timerValues[timerIndex];
        if (seconds > 0) {
            countdownActive = true;
            setCaptureEnabled(false);
            runCountdown(seconds);
            return;
        }
        performPhotoCapture();
    }

    private void runCountdown(int remaining) {
        if (remaining <= 0) {
            countdownActive = false;
            performPhotoCapture();
            return;
        }
        setStatus("التصوير بعد " + remaining + "...");
        mainHandler.postDelayed(() -> runCountdown(remaining - 1), 1000);
    }

    private void performPhotoCapture() {
        if (imageCapture == null) { setCaptureEnabled(true); return; }
        setCaptureEnabled(false);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "RedmiCamera_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/RedmiCamera");

        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults result) {
                        lastPhotoUri = result.getSavedUri();
                        setCaptureEnabled(true);
                        setStatus("تم حفظ الصورة • Pictures/RedmiCamera");
                        Toast.makeText(MainActivity.this, "تم حفظ الصورة", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(ImageCaptureException error) {
                        setCaptureEnabled(true);
                        setStatus("فشل التصوير: " + safeMessage(error));
                        Toast.makeText(MainActivity.this, "تعذر التقاط الصورة", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void toggleVideoRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            setStatus("جارٍ إنهاء وحفظ الفيديو...");
            return;
        }
        if (videoCapture == null) {
            Toast.makeText(this, "وضع الفيديو غير جاهز على هذه العدسة.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            beginVideoRecording(true);
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void beginVideoRecording(boolean withAudio) {
        if (videoCapture == null) return;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "RedmiVideo_" + stamp + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/RedmiCamera");
            MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(
                    getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values).build();
            PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
            if (withAudio) pending = pending.withAudioEnabled(false);
            else Toast.makeText(this, "سيتم تسجيل الفيديو دون صوت لأن إذن الميكروفون غير متاح.", Toast.LENGTH_LONG).show();

            activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    setStatus(withAudio ? "● تسجيل فيديو مع الصوت... اضغط لإيقافه" : "● تسجيل فيديو صامت... اضغط لإيقافه");
                    setShutterRecordingVisual(true);
                } else if (event instanceof VideoRecordEvent.Status) {
                    long nanos = event.getRecordingStats().getRecordedDurationNanos();
                    long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos);
                    setStatus(String.format(Locale.US, "● تسجيل %02d:%02d", seconds / 60, seconds % 60));
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    activeRecording = null;
                    setShutterRecordingVisual(false);
                    if (finalize.hasError()) {
                        setStatus("تعذر حفظ الفيديو. رمز الخطأ: " + finalize.getError());
                        Toast.makeText(this, "فشل تسجيل الفيديو", Toast.LENGTH_LONG).show();
                    } else {
                        setStatus("تم حفظ الفيديو في Movies/RedmiCamera");
                        Toast.makeText(this, "تم حفظ الفيديو بنجاح", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception error) {
            activeRecording = null;
            setShutterRecordingVisual(false);
            setStatus("تعذر بدء الفيديو: " + safeMessage(error));
        }
    }

    private void setShutterRecordingVisual(boolean recording) {
        if (shutterCore != null) {
            GradientDrawable core = new GradientDrawable();
            core.setShape(GradientDrawable.OVAL);
            core.setColor(recording ? Color.rgb(235, 55, 55) : Color.rgb(248, 248, 246));
            shutterCore.setBackground(core);
        }
    }

    private void openGallery() {
        try {
            Intent intent;
            if (lastPhotoUri != null) {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(lastPhotoUri, "image/jpeg");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            }
            startActivity(intent);
        } catch (Exception error) {
            try {
                startActivity(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"));
            } catch (Exception ignored) {
                Toast.makeText(this, "لم يتم العثور على تطبيق للمعرض.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showProSettings() {
        if (camera == null) {
            Toast.makeText(this, "الكاميرا لم تجهز بعد.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(12));
        panel.setBackgroundColor(Color.rgb(17, 20, 26));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);

        TextView intro = settingText("تحكم بالصورة حسب ما تسمح به عدسة الهاتف. بعض الخيارات قد تختفي إذا لم يدعمها تعريف شاومي.", 12, false);
        panel.addView(intro);

        addSection(panel, "التعريض");
        android.util.Range<Integer> evRange = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
        float evStep = camera.getCameraInfo().getExposureState().getExposureCompensationStep().floatValue();
        int minEv = evRange == null ? 0 : evRange.getLower();
        int maxEv = evRange == null ? 0 : evRange.getUpper();
        int currentEv = camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
        TextView evLabel = settingText("تعويض الإضاءة: " + formatEv(currentEv, evStep), 13, true);
        panel.addView(evLabel);
        SeekBar evSeek = new SeekBar(this);
        evSeek.setMax(Math.max(1, maxEv - minEv));
        evSeek.setProgress(Math.max(0, Math.min(evSeek.getMax(), currentEv - minEv)));
        panel.addView(evSeek);
        evSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || camera == null) return;
                int index = minEv + progress;
                exposureCompensationIndex = index;
                camera.getCameraControl().setExposureCompensationIndex(index);
                evLabel.setText("تعويض الإضاءة: " + formatEv(index, evStep));
            }
        });

        addSection(panel, "توازن اللون الأبيض");
        List<String> wbLabels = new ArrayList<>();
        List<Integer> wbModes = new ArrayList<>();
        int[] supportedWb = getSupportedWhiteBalanceModes();
        for (int mode : supportedWb) {
            wbModes.add(mode);
            wbLabels.add(whiteBalanceName(mode));
        }
        Spinner wbSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, wbLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        wbSpinner.setAdapter(adapter);
        int wbPosition = wbModes.indexOf(whiteBalanceMode);
        wbSpinner.setSelection(Math.max(0, wbPosition));
        panel.addView(wbSpinner);
        wbSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < wbModes.size()) {
                    whiteBalanceMode = wbModes.get(position);
                    applyManualCameraOptions();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        addSection(panel, "التحكم اليدوي");
        CameraCharacteristics characteristics = getCurrentCharacteristics();
        boolean manualSupported = isManualExposureSupported(characteristics) && activeMode == MODE_PHOTO && !isFrontCamera;
        if (manualSupported) {
            CheckBox manualToggle = new CheckBox(this);
            manualToggle.setText("تفعيل ISO وسرعة الغالق يدويًا");
            manualToggle.setTextColor(Color.WHITE);
            manualToggle.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(230, 190, 102)));
            manualToggle.setChecked(manualExposureEnabled);
            panel.addView(manualToggle);

            android.util.Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            android.util.Range<Long> timeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);

            TextView isoLabel = settingText("ISO: " + manualIso, 13, true);
            panel.addView(isoLabel);
            SeekBar isoSeek = new SeekBar(this);
            isoSeek.setMax(100);
            int isoMin = isoRange.getLower();
            int isoMax = isoRange.getUpper();
            isoSeek.setProgress(toProgress(manualIso, isoMin, isoMax));
            panel.addView(isoSeek);

            TextView shutterLabel = settingText("سرعة الغالق: " + formatShutter(manualExposureNs), 13, true);
            panel.addView(shutterLabel);
            SeekBar shutterSeek = new SeekBar(this);
            shutterSeek.setMax(100);
            long timeMin = Math.max(1_000_000L, timeRange.getLower());
            long timeMax = Math.min(500_000_000L, timeRange.getUpper());
            shutterSeek.setProgress(toLogProgress(manualExposureNs, timeMin, timeMax));
            panel.addView(shutterSeek);

            setManualControlsEnabled(manualToggle.isChecked(), isoLabel, isoSeek, shutterLabel, shutterSeek);
            manualToggle.setOnCheckedChangeListener((button, checked) -> {
                manualExposureEnabled = checked;
                setManualControlsEnabled(checked, isoLabel, isoSeek, shutterLabel, shutterSeek);
                if (checked) {
                    flashMode = FLASH_OFF;
                    if (imageCapture != null) imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                    updateFlashLabel();
                }
                applyManualCameraOptions();
            });

            isoSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    manualIso = isoMin + Math.round((isoMax - isoMin) * (progress / 100f));
                    isoLabel.setText("ISO: " + manualIso);
                    if (manualExposureEnabled) applyManualCameraOptions();
                }
            });
            shutterSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    manualExposureNs = fromLogProgress(progress, timeMin, timeMax);
                    shutterLabel.setText("سرعة الغالق: " + formatShutter(manualExposureNs));
                    if (manualExposureEnabled) applyManualCameraOptions();
                }
            });
        } else {
            panel.addView(settingText("ISO والغالق اليدوي غير متاحين من واجهة أندرويد لهذه العدسة. اترك التعريض التلقائي يعمل؛ يمكن تعديل الإضاءة من شريط التعويض أعلاه.", 12, false));
        }

        addSection(panel, "التركيز");
        panel.addView(settingText("المس أي نقطة في المعاينة لضبط التركيز والتعريض عليها. عند عدم اللمس يستخدم الهاتف التركيز التلقائي المستمر.", 12, false));

        addSection(panel, "معالجة الصور");
        panel.addView(settingText("الأوضاع HDR والليل والبورتريه تستخدم معالجة الشركة المصنّعة عند دعمها. في وضع الليل ثبّت الهاتف حتى ينتهي دمج اللقطات.", 12, false));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إعدادات احترافية")
                .setView(scroll)
                .setPositiveButton("تم", (d, which) -> d.dismiss())
                .setNeutralButton("معلومات العدسات", (d, which) -> showDeviceReport())
                .create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundRect(Color.rgb(17, 20, 26), 20, 1, Color.rgb(64, 70, 80)));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(470)),
                    Math.min(getResources().getDisplayMetrics().heightPixels - dp(100), dp(650)));
        }
    }

    private abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    private TextView settingText(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(bold ? Color.WHITE : Color.rgb(177, 185, 197));
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(7), 0, dp(5));
        return view;
    }

    private void addSection(LinearLayout panel, String title) {
        TextView text = settingText(title, 14, true);
        text.setTextColor(Color.rgb(230, 190, 102));
        text.setPadding(0, dp(17), 0, dp(4));
        panel.addView(text);
    }

    private int[] getSupportedWhiteBalanceModes() {
        CameraCharacteristics c = getCurrentCharacteristics();
        int[] modes = c == null ? null : c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (modes == null || modes.length == 0) return new int[]{AWB_AUTO};
        List<Integer> values = new ArrayList<>();
        values.add(AWB_AUTO);
        for (int mode : modes) if (mode != AWB_AUTO) values.add(mode);
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private String whiteBalanceName(int mode) {
        switch (mode) {
            case CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT: return "ضوء النهار";
            case CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT: return "غائم";
            case CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT: return "فلورسنت";
            case CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT: return "مصباح دافئ";
            case CaptureRequest.CONTROL_AWB_MODE_SHADE: return "ظل";
            case CaptureRequest.CONTROL_AWB_MODE_TWILIGHT: return "غروب";
            default: return "تلقائي";
        }
    }

    private CameraCharacteristics getCurrentCharacteristics() {
        String id = isFrontCamera ? (frontCameraIds.isEmpty() ? null : frontCameraIds.get(0)) : selectedBackCameraId;
        if (id == null || cameraManager == null) return null;
        try { return cameraManager.getCameraCharacteristics(id); }
        catch (Exception ignored) { return null; }
    }

    private boolean isManualExposureSupported(CameraCharacteristics c) {
        if (c == null) return false;
        int[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        android.util.Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        android.util.Range<Long> time = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (ae == null || iso == null || time == null) return false;
        for (int mode : ae) if (mode == CaptureRequest.CONTROL_AE_MODE_OFF) return true;
        return false;
    }

    private void setManualControlsEnabled(boolean enabled, TextView isoLabel, SeekBar isoSeek,
                                          TextView shutterLabel, SeekBar shutterSeek) {
        isoLabel.setEnabled(enabled);
        isoSeek.setEnabled(enabled);
        shutterLabel.setEnabled(enabled);
        shutterSeek.setEnabled(enabled);
        float alpha = enabled ? 1f : 0.42f;
        isoLabel.setAlpha(alpha);
        isoSeek.setAlpha(alpha);
        shutterLabel.setAlpha(alpha);
        shutterSeek.setAlpha(alpha);
    }

    private int toProgress(int value, int min, int max) {
        if (max <= min) return 0;
        return Math.max(0, Math.min(100, Math.round((value - min) * 100f / (max - min))));
    }

    private int toLogProgress(long value, long min, long max) {
        if (max <= min || min <= 0) return 0;
        double p = Math.log(Math.max(min, Math.min(max, value)) / (double) min)
                / Math.log(max / (double) min);
        return (int) Math.round(p * 100.0);
    }

    private long fromLogProgress(int progress, long min, long max) {
        if (max <= min || min <= 0) return min;
        double value = min * Math.pow(max / (double) min, progress / 100.0);
        return Math.max(min, Math.min(max, Math.round(value)));
    }

    private String formatEv(int index, float step) {
        return String.format(Locale.US, "%+.1f EV", index * (step <= 0 ? 1f : step));
    }

    private String formatShutter(long ns) {
        double seconds = ns / 1_000_000_000.0;
        if (seconds <= 0) return "تلقائي";
        if (seconds < 1.0) return "1/" + Math.round(1.0 / seconds) + "s";
        return String.format(Locale.US, "%.1fs", seconds);
    }

    private void applyManualCameraOptions() {
        if (camera == null) return;
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            if (manualExposureEnabled && activeMode == MODE_PHOTO && !isFrontCamera) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureNs);
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode);
            Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(builder.build());
        } catch (Exception error) {
            setStatus("لم يقبل تعريف الكاميرا هذا التحكم اليدوي.");
        }
    }

    private void showDeviceReport() {
        StringBuilder report = new StringBuilder();
        report.append("تقرير الكاميرات\n");
        report.append("الهاتف: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        report.append("Android ").append(Build.VERSION.RELEASE).append(" • API ").append(Build.VERSION.SDK_INT).append("\n\n");

        try {
            String[] ids = cameraManager.getCameraIdList();
            report.append("الأجهزة التي كشفها النظام: ").append(ids.length).append("\n\n");
            for (String id : ids) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                String direction = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                        ? "خلفية" : facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? "أمامية" : "غير معروف";
                float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                report.append("كاميرا ID ").append(id).append(" • ").append(direction).append('\n');
                if (focal != null) report.append("البعد البؤري: ").append(Arrays.toString(focal)).append(" mm\n");
                java.util.Set<String> physical = c.getPhysicalCameraIds();
                if (physical != null && !physical.isEmpty()) {
                    report.append("مجموعة كاميرات مادية: ").append(physical).append('\n');
                }
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean logical = false;
                if (caps != null) for (int cap : caps) {
                    if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logical = true;
                }
                report.append("دعم دمج كاميرات منطقية: ").append(logical ? "نعم" : "لا").append("\n\n");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                report.append("التشغيل المتزامن المدعوم: ");
                report.append(cameraManager.getConcurrentCameraIds().isEmpty() ? "لم يعلن النظام عن أزواج متزامنة" : "يوجد زوج/أزواج مدعومة");
                report.append('\n');
            } else {
                report.append("فحص التشغيل المتزامن يحتاج Android 11 أو أحدث.\n");
            }
        } catch (Exception error) {
            report.append("تعذر قراءة كل بيانات الكاميرا: ").append(safeMessage(error));
        }

        report.append("\nملاحظة: عدسة العمق لا تلتقط صورة عادية مستقلة. الدمج بين الحساسات يتحكم به تعريف شاومي؛ لا يمكن للتطبيق فرض تشغيل أربع حساسات معًا.");

        TextView text = settingText(report.toString(), 12, false);
        text.setTextColor(Color.rgb(230, 234, 241));
        text.setTextIsSelectable(true);
        text.setPadding(dp(17), dp(10), dp(17), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 20, 26));
        scroll.addView(text);
        new AlertDialog.Builder(this)
                .setTitle("معلومات العدسات والدمج")
                .setView(scroll)
                .setPositiveButton("إغلاق", (d, which) -> d.dismiss())
                .show();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(levelListener, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(levelListener);
        if (activeRecording != null) activeRecording.stop();
        super.onPause();
    }

    private final SensorEventListener levelListener = new SensorEventListener() {
        private final float[] rotation = new float[9];
        private final float[] orientation = new float[3];
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR || gridOverlay == null) return;
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
            SensorManager.getOrientation(rotation, orientation);
            gridOverlay.setRollDegrees((float) Math.toDegrees(orientation[2]));
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private static final class GridOverlay extends View {
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean gridVisible = true;
        private float rollDegrees = 0f;
        GridOverlay(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            gridPaint.setColor(0x99FFFFFF);
            gridPaint.setStrokeWidth(1.2f);
            gridPaint.setStyle(Paint.Style.STROKE);
            levelPaint.setStrokeWidth(4f);
            levelPaint.setStrokeCap(Paint.Cap.ROUND);
        }
        void setGridVisible(boolean visible) { gridVisible = visible; invalidate(); }
        void setRollDegrees(float degrees) { rollDegrees = degrees; if (gridVisible) invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!gridVisible) return;
            float w = getWidth(), h = getHeight();
            canvas.drawLine(w / 3f, 0, w / 3f, h, gridPaint);
            canvas.drawLine(w * 2f / 3f, 0, w * 2f / 3f, h, gridPaint);
            canvas.drawLine(0, h / 3f, w, h / 3f, gridPaint);
            canvas.drawLine(0, h * 2f / 3f, w, h * 2f / 3f, gridPaint);
            levelPaint.setColor(Math.abs(rollDegrees) < 1.2f ? 0xFF40E07A : 0xFFFFC24A);
            canvas.save();
            canvas.rotate(-rollDegrees, w / 2f, h / 2f);
            canvas.drawLine(w * 0.40f, h / 2f, w * 0.60f, h / 2f, levelPaint);
            canvas.restore();
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        colorWorker.shutdown();
        super.onDestroy();
    }
}