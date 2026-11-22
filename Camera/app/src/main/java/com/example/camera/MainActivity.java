package com.example.camera;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private PreviewView previewView;
    private TextView statusText;
    private Button captureButton;
    private Button flashButton;
    private Button switchButton;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private boolean frontCamera = false;
    private boolean flashOn = false;
    private boolean flashAvailable = false;

    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(9, 11, 15));
        getWindow().setNavigationBarColor(Color.rgb(9, 11, 15));

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startCamera();
                    } else {
                        statusText.setText("يلزم السماح للكاميرا حتى تعمل المعاينة والتصوير.");
                        Toast.makeText(this, "لم يتم منح إذن الكاميرا", Toast.LENGTH_LONG).show();
                    }
                });

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
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.rgb(9, 11, 15));
        root.setPadding(dp(14), dp(10), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(3), dp(2), dp(3), dp(10));

        TextView title = new TextView(this);
        title.setText("كاميرا Redmi");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("تصوير واضح • معاينة مباشرة • حفظ في معرض الصور");
        subtitle.setTextColor(Color.rgb(174, 178, 186));
        subtitle.setTextSize(11);
        header.addView(subtitle);
        root.addView(header);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.rgb(22, 25, 31));
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.bottomMargin = dp(12);
        root.addView(previewFrame, previewParams);

        statusText = new TextView(this);
        statusText.setText("جارٍ تجهيز الكاميرا...");
        statusText.setTextColor(Color.rgb(205, 208, 214));
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        statusText.setPadding(dp(4), dp(2), dp(4), dp(10));
        root.addView(statusText);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        switchButton = makeButton("تبديل", false);
        switchButton.setOnClickListener(v -> {
            frontCamera = !frontCamera;
            flashOn = false;
            startCamera();
        });
        actions.addView(switchButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        View spacerOne = new View(this);
        actions.addView(spacerOne, new LinearLayout.LayoutParams(dp(8), 1));

        flashButton = makeButton("الفلاش مغلق", false);
        flashButton.setEnabled(false);
        flashButton.setOnClickListener(v -> {
            if (!flashAvailable || imageCapture == null) return;
            flashOn = !flashOn;
            imageCapture.setFlashMode(flashOn
                    ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
            flashButton.setText(flashOn ? "الفلاش يعمل" : "الفلاش مغلق");
            statusText.setText(flashOn ? "تم تشغيل الفلاش للصورة التالية." : "تم إيقاف الفلاش.");
        });
        actions.addView(flashButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        View spacerTwo = new View(this);
        actions.addView(spacerTwo, new LinearLayout.LayoutParams(dp(8), 1));

        captureButton = makeButton("تصوير", true);
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(v -> capturePhoto());
        actions.addView(captureButton, new LinearLayout.LayoutParams(0, dp(54), 1.25f));
        root.addView(actions);

        Button reportButton = makeButton("معلومات كاميرات الجهاز", false);
        reportButton.setOnClickListener(v -> showDeviceReport());
        LinearLayout.LayoutParams reportParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        reportParams.topMargin = dp(8);
        root.addView(reportButton, reportParams);

        return root;
    }

    private Button makeButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.rgb(17, 18, 20) : Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? Color.rgb(224, 179, 79) : Color.rgb(37, 42, 50));
        background.setCornerRadius(dp(14));
        button.setBackground(background);
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startCamera() {
        if (previewView == null) return;
        captureButton.setEnabled(false);
        statusText.setText("جارٍ تشغيل الكاميرا...");
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector selector = frontCamera
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        this, selector, preview, imageCapture);

                flashAvailable = camera.getCameraInfo().hasFlashUnit() && !frontCamera;
                flashButton.setEnabled(flashAvailable);
                flashOn = false;
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                flashButton.setText("الفلاش مغلق");
                captureButton.setEnabled(true);
                switchButton.setEnabled(true);
                statusText.setText(frontCamera
                        ? "الكاميرا الأمامية جاهزة."
                        : "الكاميرا الخلفية جاهزة. اضغط تصوير لالتقاط صورة.");
            } catch (Exception exception) {
                captureButton.setEnabled(false);
                flashButton.setEnabled(false);
                statusText.setText("تعذر تشغيل الكاميرا: " + safeMessage(exception));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            statusText.setText("الكاميرا غير جاهزة بعد.");
            return;
        }

        captureButton.setEnabled(false);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "RedmiCamera_" + timestamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/RedmiCamera");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(
                        getContentResolver(),
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values).build();

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults result) {
                        captureButton.setEnabled(true);
                        statusText.setText("تم حفظ الصورة في مجلد Pictures/RedmiCamera.");
                        Toast.makeText(MainActivity.this, "تم حفظ الصورة", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        captureButton.setEnabled(true);
                        statusText.setText("فشل التصوير: " + safeMessage(exception));
                        Toast.makeText(MainActivity.this, "تعذر التقاط الصورة", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showDeviceReport() {
        StringBuilder report = new StringBuilder();
        report.append("تقرير كاميرات الجهاز\n");
        report.append("====================\n");
        report.append("الشركة: ").append(android.os.Build.MANUFACTURER).append('\n');
        report.append("الطراز: ").append(android.os.Build.MODEL).append('\n');
        report.append("إصدار Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n");

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = manager.getCameraIdList();
            report.append("عدد الكاميرات المكتشفة: ").append(ids.length).append("\n\n");
            for (String id : ids) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                Integer facing = info.get(CameraCharacteristics.LENS_FACING);
                String direction = facing == null ? "غير معروف"
                        : facing == CameraCharacteristics.LENS_FACING_BACK ? "خلفية"
                        : facing == CameraCharacteristics.LENS_FACING_FRONT ? "أمامية" : "خارجية";
                report.append("الكاميرا ").append(id).append(" — ").append(direction).append('\n');
                report.append("الفلاش: ").append(Boolean.TRUE.equals(
                        info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) ? "متوفر" : "غير متوفر").append('\n');
                Float zoom = info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                report.append("أقصى تقريب رقمي: ")
                        .append(zoom == null ? "غير معلن" : String.format(Locale.US, "%.1fx", zoom))
                        .append("\n\n");
            }
        } catch (Exception exception) {
            report.append("تعذر قراءة تفاصيل الكاميرا: ").append(safeMessage(exception));
        }

        TextView text = new TextView(this);
        text.setText(report.toString());
        text.setTextColor(Color.rgb(235, 237, 242));
        text.setTextSize(13);
        text.setPadding(dp(16), dp(14), dp(16), dp(14));
        text.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(20, 23, 29));
        scroll.addView(text);

        new android.app.AlertDialog.Builder(this)
                .setTitle("معلومات الجهاز")
                .setView(scroll)
                .setPositiveButton("إغلاق", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onDestroy();
    }
}
