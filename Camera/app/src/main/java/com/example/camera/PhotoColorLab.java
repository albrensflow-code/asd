package com.example.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class PhotoColorLab {
    private final Activity activity;
    private final Executor worker;

    private PhotoColorLab(Activity activity, Executor worker) {
        this.activity = activity;
        this.worker = worker;
    }

    public static void show(Activity activity, Uri source, Executor worker, Consumer<Uri> onSaved) {
        if (activity == null || source == null || worker == null) return;
        new PhotoColorLab(activity, worker).open(source, onSaved);
    }

    private void open(Uri source, Consumer<Uri> onSaved) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(16));
        panel.setBackgroundColor(Color.rgb(18, 21, 27));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);

        TextView note = label("التعديل ينشئ نسخة جديدة ويحافظ على الصورة الأصلية. جرّب preset ثم اضبط كل لون لوحده.", false);
        panel.addView(note);

        TextView presetTitle = label("ستايل جاهز", true);
        panel.addView(presetTitle);
        Spinner presets = new Spinner(activity);
        String[] presetNames = {
                "طبيعي", "ألوان زاهية", "سينمائي", "دافئ ذهبي",
                "أزرق بارد", "إبراز الأحمر", "إبراز الأصفر", "أبيض وأسود"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, presetNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presets.setAdapter(adapter);
        panel.addView(presets);

        SeekBar[] bars = new SeekBar[7];
        TextView[] values = new TextView[7];
        String[] names = {
                "التباين", "تشبع الألوان", "سطوع الصورة",
                "دفء اللون", "تقوية الأحمر", "تقوية الأزرق", "تقوية الأصفر"
        };
        int[] defaults = {50, 50, 50, 50, 0, 0, 0};

        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView title = label(names[i], true);
            panel.addView(title);
            values[i] = label(valueText(i, defaults[i]), false);
            panel.addView(values[i]);
            bars[i] = new SeekBar(activity);
            bars[i].setMax(100);
            bars[i].setProgress(defaults[i]);
            panel.addView(bars[i]);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    values[index].setText(valueText(index, progress));
                    if (fromUser) presets.setSelection(0);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
        }

        presets.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean initial = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (initial) { initial = false; return; }
                int[][] profiles = {
                        {50, 50, 50, 50, 0, 0, 0},
                        {63, 76, 52, 50, 0, 0, 0},
                        {65, 43, 47, 43, 13, 8, 12},
                        {58, 61, 53, 72, 0, 0, 52},
                        {58, 62, 50, 30, 0, 68, 0},
                        {57, 64, 51, 50, 78, 0, 0},
                        {58, 65, 52, 61, 0, 0, 80},
                        {63, 0, 49, 50, 0, 0, 0}
                };
                int[] selected = profiles[Math.max(0, Math.min(position, profiles.length - 1))];
                for (int i = 0; i < bars.length; i++) bars[i].setProgress(selected[i]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("استوديو الألوان")
                .setView(scroll)
                .setNegativeButton("إلغاء", (d, which) -> d.dismiss())
                .setPositiveButton("حفظ نسخة محسّنة", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(28), dp(480)),
                    Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(100), dp(720)));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int[] settings = new int[bars.length];
                for (int i = 0; i < bars.length; i++) settings[i] = bars[i].getProgress();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                TextView busy = label("جارٍ معالجة الصورة الأصلية بدقتها الكاملة...", true);
                panel.addView(busy, 0);
                worker.execute(() -> {
                    Uri edited = null;
                    String error = null;
                    try {
                        edited = renderCopy(source, settings);
                    } catch (OutOfMemoryError memory) {
                        error = "الصورة كبيرة والذاكرة المتاحة لا تكفي لمعالجتها. الأصل محفوظ كما هو.";
                    } catch (Exception exception) {
                        error = exception.getMessage() == null ? "تعذرت معالجة الصورة." : exception.getMessage();
                    }
                    Uri finalEdited = edited;
                    String finalError = error;
                    activity.runOnUiThread(() -> {
                        if (finalEdited != null) {
                            Toast.makeText(activity, "تم حفظ نسخة الألوان في Pictures/RedmiCamera/Edits", Toast.LENGTH_LONG).show();
                            if (onSaved != null) onSaved.accept(finalEdited);
                            dialog.dismiss();
                        } else {
                            busy.setText(finalError == null ? "تعذرت معالجة الصورة." : finalError);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        }
                    });
                });
            });
        });
        dialog.show();
    }

    private Uri renderCopy(Uri source, int[] setting) throws Exception {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream exifInput = activity.getContentResolver().openInputStream(source)) {
            if (exifInput != null) {
                ExifInterface exif = new ExifInterface(exifInput);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;

        Bitmap bitmap;
        try (InputStream input = activity.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("لم أستطع فتح الصورة الأصلية.");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("صيغة الصورة غير مدعومة.");

        try {
            applyColorGrade(bitmap, setting);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "Redmi_Edit_" + stamp + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/RedmiCamera/Edits");

            Uri destination = activity.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("لم أستطع إنشاء ملف النسخة المعدلة.");

            boolean saved = false;
            try (OutputStream output = activity.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("لم أستطع حفظ النسخة المعدلة.");
                saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output);
                output.flush();
            } catch (Exception error) {
                activity.getContentResolver().delete(destination, null, null);
                throw error;
            }
            if (!saved) {
                activity.getContentResolver().delete(destination, null, null);
                throw new IOException("تعذر ضغط الصورة إلى JPEG.");
            }

            try (ParcelFileDescriptor descriptor = activity.getContentResolver().openFileDescriptor(destination, "rw")) {
                if (descriptor != null) {
                    ExifInterface outputExif = new ExifInterface(descriptor.getFileDescriptor());
                    outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
                    outputExif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Edited with Redmi Camera Color Studio");
                    outputExif.saveAttributes();
                }
            } catch (Exception ignored) {
                // The edited image itself is already saved; some providers do not allow EXIF rewrites.
            }
            return destination;
        } finally {
            bitmap.recycle();
        }
    }

    private void applyColorGrade(Bitmap bitmap, int[] s) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] row = new int[width];

        float contrast = 0.55f + (s[0] / 100f) * 0.90f;
        float saturation = (s[1] / 100f) * 1.90f;
        float brightness = (s[2] - 50) * 1.15f;
        float warmth = (s[3] - 50) * 0.72f;
        float redBoost = s[4] / 100f;
        float blueBoost = s[5] / 100f;
        float yellowBoost = s[6] / 100f;

        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int color = row[x];
                int alpha = Color.alpha(color);
                float r = Color.red(color);
                float g = Color.green(color);
                float b = Color.blue(color);

                r = (r - 128f) * contrast + 128f + brightness + warmth;
                g = (g - 128f) * contrast + 128f + brightness;
                b = (b - 128f) * contrast + 128f + brightness - warmth;

                float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = luminance + (r - luminance) * saturation;
                g = luminance + (g - luminance) * saturation;
                b = luminance + (b - luminance) * saturation;

                float redMask = clamp01((r - Math.max(g, b) * 0.86f) / 75f);
                float blueMask = clamp01((b - Math.max(r, g) * 0.86f) / 75f);
                float yellowMask = clamp01((Math.min(r, g) - b * 0.90f) / 65f);
                r += redMask * redBoost * 42f;
                b += blueMask * blueBoost * 42f;
                r += yellowMask * yellowBoost * 22f;
                g += yellowMask * yellowBoost * 18f;

                row[x] = Color.argb(alpha, clamp255(r), clamp255(g), clamp255(b));
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }

    private TextView label(String text, boolean strong) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(strong ? 13 : 12);
        view.setTextColor(strong ? Color.WHITE : Color.rgb(183, 191, 202));
        if (strong) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(7), 0, dp(2));
        return view;
    }

    private String valueText(int index, int value) {
        switch (index) {
            case 0: return "القيمة: " + String.format(Locale.US, "%.2f", 0.55f + value / 100f * 0.90f);
            case 1: return "القيمة: " + value + "%";
            case 2: return "القيمة: " + (value - 50);
            case 3: return value < 50 ? "أبرد " + (50 - value) : value > 50 ? "أدفأ " + (value - 50) : "محايد";
            default: return "قوة التأثير: " + value + "%";
        }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}