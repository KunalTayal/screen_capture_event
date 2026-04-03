package id.nizwar.screen_capture_event;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import android.util.Log;

public class ScreenCaptureEventPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    static int SCREEN_CAPTURE_PERMISSION = 101;
    private MethodChannel channel;
    private FileObserver fileObserver;
    private Timer timeout = new Timer();
    private final Map<String, FileObserver> watchModifier = new HashMap<>();
    private ActivityPluginBinding activityPluginBinding;
    private Handler handler;
    private boolean screenRecording = false;
    private long tempSize = 0;

    // ✅ FIX: dedicated background thread for all disk I/O
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "screencapture_method");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "prevent_screenshot":
                if ((boolean) call.arguments) {
                    activityPluginBinding.getActivity().getWindow()
                        .addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    activityPluginBinding.getActivity().getWindow()
                        .clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
                break;
            case "isRecording":
                result.success(screenRecording);
                break;
            case "request_permission":
                if (ContextCompat.checkSelfPermission(activityPluginBinding.getActivity(),
                        Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activityPluginBinding.getActivity(),
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 101);
                }
                break;
            case "watch":
                handler = new Handler(Looper.getMainLooper());

                // ✅ FIX: run initial disk scan on background thread
                ioExecutor.execute(this::updateScreenRecordStatus);

                if (Build.VERSION.SDK_INT >= 29) {
                    final List<File> files = new ArrayList<>();
                    final List<String> paths = new ArrayList<>();
                    for (Path path : Path.values()) {
                        files.add(new File(path.getPath()));
                        paths.add(path.getPath());
                    }
                    fileObserver = new FileObserver(files) {
                        @Override
                        public void onEvent(int event, final String filename) {
                            if (filename == null) return;
                            for (String fullPath : paths) {
                                File file = new File(fullPath + filename);
                                if (file.exists()) {
                                    String mime = getMimeType(file.getPath());
                                    if (mime != null) {
                                        if (event == FileObserver.CREATE || event == FileObserver.MODIFY) {
                                            if (mime.contains("video")) {
                                                setScreenRecordStatus(true);
                                                // ✅ FIX: background thread for disk scan
                                                ioExecutor.execute(ScreenCaptureEventPlugin.this::updateScreenRecordStatus);
                                            } else if (mime.contains("image")) {
                                                handler.post(() -> channel.invokeMethod("screenshot", file.getPath()));
                                            }
                                        } else {
                                            if (mime.contains("video")) {
                                                stopAllRecordWatcher();
                                                setScreenRecordStatus(false);
                                                ioExecutor.execute(ScreenCaptureEventPlugin.this::updateScreenRecordStatus);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    };
                    fileObserver.startWatching();
                } else {
                    for (final Path path : Path.values()) {
                        fileObserver = new FileObserver(path.getPath()) {
                            @Override
                            public void onEvent(int event, final String filename) {
                                if (filename == null) return;
                                File file = new File(path.getPath() + filename);
                                if (file.exists()) {
                                    String mime = getMimeType(file.getPath());
                                    if (mime != null) {
                                        if (event == FileObserver.CREATE || event == FileObserver.MODIFY) {
                                            if (mime.contains("video")) {
                                                setScreenRecordStatus(true);
                                                ioExecutor.execute(ScreenCaptureEventPlugin.this::updateScreenRecordStatus);
                                            } else if (mime.contains("image")) {
                                                handler.post(() -> channel.invokeMethod("screenshot", file.getPath()));
                                            }
                                        } else {
                                            if (mime.contains("video")) {
                                                stopAllRecordWatcher();
                                                setScreenRecordStatus(false);
                                                ioExecutor.execute(ScreenCaptureEventPlugin.this::updateScreenRecordStatus);
                                            }
                                        }
                                    }
                                }
                            }
                        };
                        fileObserver.startWatching();
                    }
                }
                break;
            case "dispose":
                if (fileObserver != null) fileObserver.stopWatching();
                for (Map.Entry<String, FileObserver> entry : watchModifier.entrySet()) {
                    entry.getValue().stopWatching();
                }
                watchModifier.clear();
                // ✅ FIX: shut down executor cleanly on dispose
                ioExecutor.shutdown();
                break;
            default:
                break;
        }
    }

    private void stopAllRecordWatcher() {
        for (Map.Entry<String, FileObserver> entry : watchModifier.entrySet()) {
            entry.getValue().stopWatching();
        }
        watchModifier.clear();
        setScreenRecordStatus(false);
    }

    // ✅ This method now runs ONLY on ioExecutor (background thread)
    private void updateScreenRecordStatus() {
        List<String> paths = new ArrayList<>();
        for (Path path : Path.values()) {
            paths.add(path.getPath());
        }
        for (String fullPath : paths) {
            // getLastModified does disk I/O — safe here because we're on ioExecutor
            File newFile = getLastModified(fullPath);
            if (newFile != null) {
                String mime = getMimeType(newFile.getPath());
                if (mime != null && mime.contains("video")) {
                    if (!watchModifier.containsKey(newFile.getPath())) {
                        FileObserver observer;
                        if (Build.VERSION.SDK_INT >= 29) {
                            observer = new FileObserver(newFile) {
                                @Override
                                public void onEvent(int event, @Nullable String path) {
                                    handleUpdateScreenRecordEvent(event, newFile);
                                }
                            };
                        } else {
                            observer = new FileObserver(newFile.getPath()) {
                                @Override
                                public void onEvent(int event, @Nullable String path) {
                                    handleUpdateScreenRecordEvent(event, newFile);
                                }
                            };
                        }
                        // ✅ watchModifier mutation must be done carefully —
                        //    post back to main handler to stay thread-safe
                        final FileObserver finalObserver = observer;
                        handler.post(() -> {
                            watchModifier.put(newFile.getPath(), finalObserver);
                            finalObserver.startWatching();
                        });
                    }
                }
            }
        }
    }

    private void handleUpdateScreenRecordEvent(int event, File newFile) {
        // ✅ FIX: file.length() is disk I/O — move to background
        ioExecutor.execute(() -> {
            long curSize = newFile.length();
            if (curSize > tempSize) {
                if (timeout != null) {
                    try {
                        timeout.cancel();
                        timeout = null;
                    } catch (Exception ignored) {}
                }
                setScreenRecordStatus(event == FileObserver.MODIFY);
                tempSize = newFile.length();
            }
            if (timeout == null) {
                timeout = new Timer();
                timeout.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (watchModifier.containsKey(newFile.getPath())) {
                            setScreenRecordStatus(curSize != tempSize);
                        }
                    }
                }, 1500);
            }
        });
    }

    void setScreenRecordStatus(boolean value) {
        if (screenRecording != value) {
            // ✅ Always dispatch to main thread for Flutter channel calls
            new Handler(Looper.getMainLooper()).post(() -> {
                screenRecording = value;
                channel.invokeMethod("screenrecord", value);
            });
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        ioExecutor.shutdown(); // ✅ clean up if detached
    }

    public static String getMimeType(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < url.length() - 1) {
            String extension = url.substring(lastDotIndex + 1);
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return null;
    }

    // ✅ This is disk I/O — only call from ioExecutor
    public static File getLastModified(String directoryFilePath) {
        File directory = new File(directoryFilePath);
        if (directory.listFiles() == null) return null;
        File[] files = directory.listFiles(File::isFile);
        long lastModifiedTime = Long.MIN_VALUE;
        File chosenFile = null;
        if (files != null) {
            for (File file : files) {
                if (file.lastModified() > lastModifiedTime) {
                    chosenFile = file;
                    lastModifiedTime = file.lastModified();
                }
            }
        }
        return chosenFile;
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activityPluginBinding = binding;
    }

    @Override public void onDetachedFromActivityForConfigChanges() {}

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activityPluginBinding = binding;
    }

    @Override public void onDetachedFromActivity() {}

    public enum Path {
        DCIMSAMSUNG(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                + File.separator + "Screen recordings" + File.separator),
        DCIM(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                + File.separator + "Screenshots" + File.separator),
        PICTURES(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                + File.separator + "Screenshots" + File.separator);

        final private String path;

        public String getPath() { return path; }

        Path(String path) { this.path = path; }
    }
}
