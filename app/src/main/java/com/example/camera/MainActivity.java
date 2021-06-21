package com.example.camera;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationManager;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.example.camera.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private static final int INITIALIZE = 0;
    private static final int ENABLE_LOCATION_WATERMARK = 1;
    private static final int ENABLE_LAT_LONG_WATERMARK = 2;

    private static final int ANIMATION_DURATION = 170;

    private ActivityMainBinding binding;
    private Handler daemon;
    private Handler ui;
    private ImageReader imageReader;
    private CameraDevice camera;
    private String thumbnail;

    private boolean datetimeWatermarkEnabled = true;
    private boolean locationWatermarkEnabled = false;
    private boolean latLongWatermarkEnabled = false;

    private int aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
    private int flashMode = CaptureRequest.FLASH_MODE_TORCH;
    private int delay = 0;
    private String cameraId;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.textureView.setKeepScreenOn(true);

        binding.getRoot().setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (binding.txt.isFocused()) {
                    final Rect outRect = new Rect();
                    binding.txt.getGlobalVisibleRect(outRect);
                    if (!outRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                        binding.txt.clearFocus();
                        ((InputMethodManager) v.getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
                    }
                }
            }
            return false;
        });

        binding.watermarkSwitch.setOnClickListener(v -> {
            if (binding.watermarkSwitches.getVisibility() == View.VISIBLE) {
                hideWatermarkSwitches();
            } else {
                showWatermarkSwitches();
            }
        });

        binding.datetimeWatermarkSwitch.setOnClickListener(v -> {
            datetimeWatermarkEnabled = !datetimeWatermarkEnabled;
            if (datetimeWatermarkEnabled) {
                binding.datetimeWatermark.setVisibility(View.VISIBLE);
                binding.datetimeWatermarkSwitch.setImageResource(R.drawable.ic_clock_highlight);
            } else {
                binding.datetimeWatermark.setVisibility(View.GONE);
                binding.datetimeWatermarkSwitch.setImageResource(R.drawable.ic_clock);
            }
            adjustWatermark();
        });
        ui = new Handler();
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                ui.post(() -> binding.datetime.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date())));
            }
        }, 0, 10000);

        binding.locationWatermarkSwitch.setOnClickListener(v -> {
            if (locationWatermarkEnabled) {
                disableLocationWatermark();
            } else {
                enableLocationWatermark();
            }
        });

        binding.latLongWatermarkSwitch.setOnClickListener(v -> {
            if (latLongWatermarkEnabled) {
                disableLatLongWatermark();
            } else {
                enableLatLongWatermark();
            }
        });

        binding.txtWatermarkSwitch.setOnClickListener(v -> {
            if (binding.txt.getVisibility() == View.GONE) {
                binding.txt.setVisibility(View.VISIBLE);
                binding.txtWatermarkSwitch.setImageResource(R.drawable.txt_highlight);
            } else {
                binding.txt.setVisibility(View.GONE);
                binding.txtWatermarkSwitch.setImageResource(R.drawable.txt);
            }
            adjustWatermark();
        });

        binding.thumbnail.setOnClickListener(v -> {
            if (thumbnail != null) {
                final Intent intent = new Intent();
                intent.setAction(Intent.ACTION_VIEW);
                intent.setDataAndType(FileProvider.getUriForFile(this, getPackageName() + ".provider", new File(thumbnail)), "image/jpeg");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        });

        binding.flash.setOnClickListener(v -> {
            if (aeMode == CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH) {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON;
                flashMode = CaptureRequest.FLASH_MODE_OFF;
                binding.flash.setImageResource(R.drawable.ic_flash_off);
            } else if (flashMode == CaptureRequest.FLASH_MODE_OFF) {
                flashMode = CaptureRequest.FLASH_MODE_TORCH;
                binding.flash.setImageResource(R.drawable.ic_flash_on);
            } else {
                aeMode = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
                binding.flash.setImageResource(R.drawable.ic_flash_auto);
            }
        });

        binding.timer.setOnClickListener(v -> {
            switch (delay) {
                case 0:
                    delay = 5;
                    binding.timer.setImageResource(R.drawable.ic_timer_5);
                    break;
                case 5:
                    delay = 10;
                    binding.timer.setImageResource(R.drawable.ic_timer_10);
                    break;
                case 10:
                    delay = 0;
                    binding.timer.setImageResource(R.drawable.ic_timer);
                    break;
            }
        });

        binding.switchCamera.setOnClickListener(v -> {
            if (camera != null) {
                camera.close();
                final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                try {
                    final String[] cameraIdList = manager.getCameraIdList();
                    if (cameraIdList.length == 0) {
                        makeToast("No camera available");
                        finishAffinity();
                    }
                    binding.frameLayout.animate().alpha(.5f).scaleX(.8f).scaleY(.8f).setDuration(ANIMATION_DURATION);
                    binding.frameLayout
                            .animate()
                            .alpha(0f)
                            .scaleX(0f)
                            .setDuration(ANIMATION_DURATION)
                            .withStartAction(() -> {
                                if (Arrays.stream(cameraIdList).anyMatch(id -> id.equals(cameraId))) {
                                    for (int i = 0; i < cameraIdList.length; ++i) {
                                        if (cameraIdList[i].equals(cameraId)) {
                                            cameraId = cameraIdList[i == cameraIdList.length - 1 ? 0 : i + 1];
                                            return;
                                        }
                                    }
                                }
                                cameraId = cameraIdList[0];
                            })
                            .withEndAction(() -> {
                                initializeCamera(binding.textureView.getWidth(), binding.textureView.getHeight());
                                binding.frameLayout
                                        .animate()
                                        .alpha(.5f)
                                        .scaleX(.8f)
                                        .setDuration(ANIMATION_DURATION);
                                binding.frameLayout.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIMATION_DURATION);
                            });
                } catch (CameraAccessException e) {
                    makeToast(e);
                    finishAffinity();
                }
            }
        });

        final HandlerThread thread = new HandlerThread("daemon");
        thread.start();
        daemon = new Handler(thread.getLooper());

        initialize();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (camera != null) {
            camera.close();
            camera = null;
        }
        imageReader.close();
        imageReader = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        initialize();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case INITIALIZE:
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    makeToast("No permission to access camera");
                    finishAffinity();
                } else if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                    makeToast("No permission to write external storage");
                    finishAffinity();
                } else if (grantResults[2] != PackageManager.PERMISSION_GRANTED) {
                    makeToast("No permission to read external storage");
                    finishAffinity();
                } else {
                    initialize();
                }
                break;
            case ENABLE_LOCATION_WATERMARK:
                if (Arrays.stream(grantResults).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
                    enableLocationWatermark();
                } else {
                    makeToast("No permission to access location");
                }
                break;
            case ENABLE_LAT_LONG_WATERMARK:
                if (Arrays.stream(grantResults).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
                    enableLatLongWatermark();
                } else {
                    makeToast("No permission to access location");
                }
                break;
        }
    }

    private void showWatermarkSwitches() {
        binding.watermarkSwitches.animate()
                .translationY(0)
                .setDuration(ANIMATION_DURATION)
                .withStartAction(() -> {
                    binding.watermarkSwitches.setVisibility(View.VISIBLE);
                    final float translationY = getHeightWithMargin(binding.watermarkSwitches);
                    binding.watermarkSwitches.setTranslationY(translationY);
                    binding.watermark.setTranslationY(translationY);
                });
        binding.watermark
                .animate()
                .translationY(0)
                .setDuration(ANIMATION_DURATION);
        binding.watermarkSwitch.setImageResource(R.drawable.ic_watermark_highlight);
    }

    private void hideWatermarkSwitches(final int duration) {
        binding.watermark
                .animate()
                .translationY(getHeightWithMargin(binding.watermarkSwitches))
                .setDuration(duration);
        binding.watermarkSwitches.animate()
                .translationY(getHeightWithMargin(binding.watermarkSwitches))
                .setDuration(duration)
                .withEndAction(() -> {
                    binding.watermarkSwitches.setVisibility(View.GONE);
                    binding.watermark.setTranslationY(0f);
                });
        binding.watermarkSwitch.setImageResource(R.drawable.ic_watermark);
    }

    private void hideWatermarkSwitches() {
        hideWatermarkSwitches(ANIMATION_DURATION);
    }

    private void makeToast(final String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void makeToast(final Exception e) {
        makeToast(e.getMessage());
    }

    private void enableLocationWatermark() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.INTERNET}, ENABLE_LOCATION_WATERMARK);
            return;
        }

        final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationWatermarkEnabled = true;
            binding.locationWatermark.setVisibility(View.VISIBLE);
            binding.locationWatermarkSwitch.setImageResource(R.drawable.ic_location_highlight);
            binding.location.setText("加载中...");
            adjustWatermark();
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, location -> {
                if (Geocoder.isPresent()) {
                    try {
                        final double latitude = location.getLatitude();
                        final double longitude = location.getLongitude();
                        final List<Address> addresses = new Geocoder(MainActivity.this).getFromLocation(latitude, longitude, 1);
                        if (addresses == null || addresses.isEmpty()) {
                            makeToast("No matching addresses were found or there is no backend service available");
                        } else {
                            final Address address = addresses.get(0);
                            final String loc = address.getLocality() + " " +
                                    address.getSubLocality() + " " +
                                    address.getFeatureName();
                            binding.location.setText(loc);
                            adjustWatermark();
                            return;
                        }
                    } catch (IOException e) {
                        makeToast(e);
                    }
                } else {
                    makeToast("Geocoder is not present");
                }
                disableLocationWatermark();
            }, null);
        } else {
            makeToast("Network provider is not enabled");
        }
    }

    private void disableLocationWatermark() {
        locationWatermarkEnabled = false;
        binding.locationWatermark.setVisibility(View.GONE);
        binding.locationWatermarkSwitch.setImageResource(R.drawable.ic_location);
        adjustWatermark();
    }

    private void enableLatLongWatermark() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.INTERNET}, ENABLE_LAT_LONG_WATERMARK);
            return;
        }

        final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            latLongWatermarkEnabled = true;
            binding.latLongWatermark.setVisibility(View.VISIBLE);
            binding.latLongWatermarkSwitch.setImageResource(R.drawable.ic_lat_long_highlight);
            binding.latLong.setText("加载中...");
            adjustWatermark();
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, location -> {
                if (Geocoder.isPresent()) {
                    final double longitude = location.getLongitude();
                    final double latitude = location.getLatitude();
                    binding.latLong.setText(String.format(Locale.getDefault(),
                            "%.2f° %c, %.2f° %c",
                            longitude, longitude >= 0 ? 'E' : 'W',
                            latitude, latitude >= 0 ? 'N' : 'S'));
                    adjustWatermark();
                    return;
                } else {
                    makeToast("Geocoder is not present");
                }
                disableLatLongWatermark();
            }, null);
        } else {
            makeToast("Network provider is not enabled");
        }
    }

    private void disableLatLongWatermark() {
        latLongWatermarkEnabled = false;
        binding.latLongWatermark.setVisibility(View.GONE);
        binding.latLongWatermarkSwitch.setImageResource(R.drawable.ic_lat_long);
        adjustWatermark();
    }

    private void adjustWatermark() {
        binding.fixedWatermark.setVisibility(datetimeWatermarkEnabled || locationWatermarkEnabled || latLongWatermarkEnabled ? View.VISIBLE : View.GONE);
        if (binding.fixedWatermark.getVisibility() == View.VISIBLE || binding.txt.getVisibility() == View.VISIBLE) {
            if (binding.watermark.getVisibility() == View.GONE) {
                binding.watermark.animate()
                        .alpha(1.f)
                        .translationY(0)
                        .setDuration(ANIMATION_DURATION)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                binding.watermark.setVisibility(View.VISIBLE);
                            }
                        });
            }
        } else {
            if (binding.watermark.getVisibility() == View.VISIBLE) {
                binding.watermark.animate()
                        .alpha(0.f)
                        .translationY(getHeightWithMargin(binding.watermark))
                        .setDuration(ANIMATION_DURATION)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                binding.watermark.setVisibility(View.GONE);
                            }
                        });
            }
        }
    }

    private void capture(final CameraCaptureSession session, final CaptureRequest preview) {
        try {
            final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            builder.set(CaptureRequest.FLASH_MODE, flashMode);
            builder.addTarget(imageReader.getSurface());
            session.stopRepeating();
            session.abortCaptures();
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    try {
                        session.setRepeatingRequest(preview, null, daemon);
                    } catch (CameraAccessException e) {
                        makeToast(e);
                    }
                }
            }, daemon);
        } catch (CameraAccessException e) {
            makeToast(e);
        }
    }

    private void initializeImageReader(final int width, final int height) {
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> daemon.post(() -> {
            final File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            final String basename = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(new Date());
            final String filename = Paths.get(path.toString(), "IMG" + basename + ".jpg").toString();
            binding.frameLayout.setDrawingCacheEnabled(true);
            binding.frameLayout.buildDrawingCache();
            final Bitmap photo = binding.textureView.getBitmap();
            new Canvas(photo).drawBitmap(binding.frameLayout.getDrawingCache(), 0, 0, null);
            try {
                final FileOutputStream stream = new FileOutputStream(filename);
                photo.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                stream.close();
                ui.post(() -> setThumbnail(filename));
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.parse("file://" + filename)));
            } catch (IOException e) {
                makeToast(e);
            }
            binding.frameLayout.setDrawingCacheEnabled(false);
            binding.frameLayout.destroyDrawingCache();
        }), daemon);
    }

    private void initializeCamera(final int width, final int height) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, INITIALIZE);
            return;
        }

        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    MainActivity.this.camera = camera;
                    final SurfaceTexture surfaceTexture = binding.textureView.getSurfaceTexture();
                    surfaceTexture.setDefaultBufferSize(width, height);
                    final Surface surface = new Surface(surfaceTexture);
                    try {
                        final CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        builder.addTarget(surface);
                        camera.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    final CaptureRequest preview = builder.build();
                                    session.setRepeatingRequest(preview, null, daemon);
                                    binding.capture.setOnClickListener(v -> {
                                        if (binding.watermarkSwitches.getVisibility() == View.VISIBLE) {
                                            hideWatermarkSwitches(1);
                                        }
                                        binding.txt.clearFocus();
                                        if (delay == 0) {
                                            capture(session, preview);
                                        } else {
                                            binding.flash.animate().alpha(0f).setDuration(50);
                                            binding.timer.animate().alpha(0f).setDuration(50);
                                            binding.switchCamera.animate().alpha(0f).setDuration(50);
                                            binding.thumbnail.animate().alpha(0f).setDuration(50);
                                            binding.watermarkSwitch.animate().alpha(0f).setDuration(50);
                                            binding.flash.setClickable(false);
                                            binding.timer.setClickable(false);
                                            binding.switchCamera.setClickable(false);
                                            binding.thumbnail.setClickable(false);
                                            binding.watermarkSwitch.setClickable(false);
                                            binding.txt.setEnabled(false);
                                            binding.capture.setClickable(false);
                                            final Timer timer = new Timer();
                                            timer.schedule(new TimerTask() {
                                                int remain = delay;

                                                @Override
                                                public void run() {
                                                    ui.post(() -> {
                                                        binding.capture.setText(String.valueOf(remain));
                                                        if (--remain < 0) {
                                                            timer.cancel();
                                                            binding.capture.setText("");
                                                            if (MainActivity.this.camera == camera) {
                                                                capture(session, preview);
                                                                binding.flash.animate().alpha(1f).setDuration(50);
                                                                binding.timer.animate().alpha(1f).setDuration(50);
                                                                binding.switchCamera.animate().alpha(1f).setDuration(50);
                                                                binding.thumbnail.animate().alpha(1f).setDuration(50);
                                                                binding.watermarkSwitch.animate().alpha(1f).setDuration(50);
                                                                binding.flash.setClickable(true);
                                                                binding.timer.setClickable(true);
                                                                binding.switchCamera.setClickable(true);
                                                                binding.thumbnail.setClickable(true);
                                                                binding.watermarkSwitch.setClickable(true);
                                                                binding.txt.setEnabled(true);
                                                                binding.capture.setClickable(true);
                                                            }
                                                        }
                                                    });
                                                }
                                            }, 0, 1000);
                                        }
                                    });
                                } catch (CameraAccessException e) {
                                    makeToast(e);
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {

                            }
                        }, daemon);
                    } catch (CameraAccessException e) {
                        makeToast(e);
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {

                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {

                }
            }, daemon);
        } catch (CameraAccessException e) {
            makeToast(e);
        }
    }

    private void initialize() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            final String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList.length == 0) {
                makeToast("No camera available");
                finishAffinity();
            }
            cameraId = cameraId != null && Arrays.stream(cameraIdList).anyMatch(id -> id.equals(cameraId))
                    ? cameraId
                    : cameraIdList[0];
        } catch (CameraAccessException e) {
            makeToast(e);
            finishAffinity();
        }
        if (binding.textureView.isAvailable()) {
            final int width = binding.textureView.getWidth();
            final int height = binding.textureView.getHeight();
            initializeImageReader(width, height);
            initializeCamera(width, height);
        } else {
            binding.textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    initializeImageReader(width, height);
                    initializeCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

                }
            });
        }

        final File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        try {
            final File[] files = path.listFiles();
            if (files != null && files.length > 0) {
                final Optional<File> latestFile = Arrays
                        .stream(files)
                        .filter(File::isFile)
                        .filter(file -> file.getName().toLowerCase().endsWith(".jpg"))
                        .max(File::compareTo);
                if (latestFile.isPresent()) {
                    setThumbnail(latestFile.get().toString());
                    return;
                }
            }
        } catch (SecurityException e) {
            makeToast(e);
        }
        final int size = (int) (38.f * getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.DKGRAY);
        thumbnail = null;
        setThumbnail(bitmap);
    }

    private void setThumbnail(final Bitmap bitmap) {
        final RoundedBitmapDrawable roundedBitmapDrawable = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
        roundedBitmapDrawable.setCornerRadius(bitmap.getWidth() * .15f);
        binding.thumbnail.animate()
                .scaleY(0f)
                .setDuration(30)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        binding.thumbnail.setImageDrawable(roundedBitmapDrawable);
                        binding.thumbnail.animate()
                                .scaleY(1f)
                                .setDuration(30);
                    }
                });
    }

    private void setThumbnail(final String filename) {
        thumbnail = filename;
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inMutable = true;
        final Bitmap bitmap = BitmapFactory.decodeFile(thumbnail, options);
        final int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        final int start = (Math.max(bitmap.getWidth(), bitmap.getHeight()) - size) / 2;
        setThumbnail(Bitmap.createBitmap(bitmap,
                bitmap.getWidth() > bitmap.getHeight() ? start : 0,
                bitmap.getWidth() > bitmap.getHeight() ? 0 : start,
                size, size));
    }

    private int getHeightWithMargin(final View view) {
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return view.getHeight() + lp.topMargin + lp.bottomMargin;
    }
}