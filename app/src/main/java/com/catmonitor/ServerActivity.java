package com.catmonitor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.format.Formatter;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ServerActivity extends AppCompatActivity {

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int PERMISSION_CODE = 101;
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    // Audio
    private AudioRecord audioRecord;
    private ServerSocket audioServerSocket;
    private Thread audioThread;

    // Video
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ServerSocket videoServerSocket;
    private Thread videoThread;
    private volatile OutputStream videoOutputStream;

    private boolean isStreaming = false;

    private TextView tvStatus;
    private TextView tvIp;
    private Button btnToggle;
    private TextureView textureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);

        tvStatus   = findViewById(R.id.tvStatus);
        tvIp       = findViewById(R.id.tvIp);
        btnToggle  = findViewById(R.id.btnToggle);
        textureView = findViewById(R.id.textureView);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        tvIp.setText("IP: " + ip + "\nPuertos: " + PORT_AUDIO + " (audio) / " + PORT_VIDEO + " (video)");

        btnToggle.setOnClickListener(v -> {
            if (isStreaming) stopStreaming();
            else checkPermissionsAndStart();
        });
    }

    private void checkPermissionsAndStart() {
        String[] perms = {Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA};
        boolean allGranted = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) ActivityCompat.requestPermissions(this, perms, PERMISSION_CODE);
        else startStreaming();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (ok) startStreaming();
        else Toast.makeText(this, "Se necesitan permisos de cámara y micrófono", Toast.LENGTH_SHORT).show();
    }

    private void startStreaming() {
        isStreaming = true;
        btnToggle.setText("⏹ Detener");
        tvStatus.setText("⏳ Iniciando cámara y micrófono...");
        startBackgroundThread();
        startAudioServer();
        startCamera();
    }

    // ───────── AUDIO ─────────
    private void startAudioServer() {
        audioThread = new Thread(() -> {
            try {
                audioServerSocket = new ServerSocket(PORT_AUDIO);
                runOnUiThread(() -> tvStatus.setText("🟡 Esperando cliente de audio..."));
                Socket client = audioServerSocket.accept();
                runOnUiThread(() -> tvStatus.setText("😺 ¡Conectado! Transmitiendo audio + video..."));

                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4);
                audioRecord.startRecording();

                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[bufferSize];
                while (isStreaming && !client.isClosed()) {
                    int read = audioRecord.read(buffer, 0, bufferSize);
                    if (read > 0) { out.write(buffer, 0, read); out.flush(); }
                }
                audioRecord.stop(); audioRecord.release(); client.close(); audioServerSocket.close();
            } catch (Exception e) {
                if (isStreaming) runOnUiThread(() -> tvStatus.setText("❌ Error audio: " + e.getMessage()));
            }
        });
        audioThread.start();
    }

    // ───────── VIDEO ─────────
    private void startCamera() {
        // Configurar ImageReader para capturar frames JPEG
        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            android.media.Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                sendVideoFrame(bytes);
            } finally {
                image.close();
            }
        }, backgroundHandler);

        // Abrir cámara
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0]; // cámara trasera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        startVideoServer();
                        startPreview();
                    }
                    @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                    @Override public void onError(CameraDevice camera, int error) { camera.close(); }
                }, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture != null) texture.setDefaultBufferSize(WIDTH, HEIGHT);
            Surface previewSurface = texture != null ? new Surface(texture) : null;
            Surface readerSurface = imageReader.getSurface();

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (previewSurface != null) builder.addTarget(previewSurface);
            builder.addTarget(readerSurface);

            java.util.List<Surface> surfaces = previewSurface != null
                    ? Arrays.asList(previewSurface, readerSurface)
                    : Arrays.asList(readerSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) { e.printStackTrace(); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void startVideoServer() {
        videoThread = new Thread(() -> {
            try {
                videoServerSocket = new ServerSocket(PORT_VIDEO);
                Socket client = videoServerSocket.accept();
                videoOutputStream = client.getOutputStream();
            } catch (Exception e) {
                if (isStreaming) e.printStackTrace();
            }
        });
        videoThread.start();
    }

    private void sendVideoFrame(byte[] jpegBytes) {
        if (videoOutputStream == null) return;
        try {
            // Protocolo: 4 bytes tamaño + datos JPEG
            int len = jpegBytes.length;
            byte[] header = new byte[]{
                (byte)(len >> 24), (byte)(len >> 16), (byte)(len >> 8), (byte)(len)
            };
            videoOutputStream.write(header);
            videoOutputStream.write(jpegBytes);
            videoOutputStream.flush();
        } catch (Exception e) {
            videoOutputStream = null;
        }
    }

    // ───────── STOP ─────────
    private void stopStreaming() {
        isStreaming = false;
        btnToggle.setText("▶ Iniciar");
        tvStatus.setText("⏹ Detenido");
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (audioServerSocket != null && !audioServerSocket.isClosed()) audioServerSocket.close();
            if (videoServerSocket != null && !videoServerSocket.isClosed()) videoServerSocket.close();
            if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
        } catch (Exception e) { e.printStackTrace(); }
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); backgroundThread = null; backgroundHandler = null; }
            catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); stopStreaming(); }
}
