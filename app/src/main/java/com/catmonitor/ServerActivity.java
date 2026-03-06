package com.catmonitor;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class ServerActivity extends AppCompatActivity {

    private static final int PORT = 9999;
    private static final int SAMPLE_RATE = 44100;
    private static final int PERMISSION_CODE = 101;

    private AudioRecord audioRecord;
    private ServerSocket serverSocket;
    private Thread streamThread;
    private boolean isStreaming = false;

    private TextView tvStatus;
    private TextView tvIp;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);

        tvStatus = findViewById(R.id.tvStatus);
        tvIp = findViewById(R.id.tvIp);
        btnToggle = findViewById(R.id.btnToggle);

        // Mostrar IP local
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        tvIp.setText("IP: " + ip + "\nPuerto: " + PORT);

        btnToggle.setOnClickListener(v -> {
            if (isStreaming) {
                stopStreaming();
            } else {
                checkPermissionAndStart();
            }
        });
    }

    private void checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_CODE);
        } else {
            startStreaming();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_CODE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startStreaming();
        } else {
            Toast.makeText(this, "Se necesita permiso de micrófono", Toast.LENGTH_SHORT).show();
        }
    }

    private void startStreaming() {
        isStreaming = true;
        btnToggle.setText("⏹ Detener");
        tvStatus.setText("⏳ Esperando conexión del cliente...");

        streamThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);

                runOnUiThread(() -> tvStatus.setText("🟢 Esperando conexión...\n(Abre el cliente en el otro teléfono)"));

                Socket client = serverSocket.accept(); // espera conexión

                runOnUiThread(() -> tvStatus.setText("😺 ¡Conectado! Transmitiendo audio del gato..."));

                int bufferSize = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 4);

                audioRecord.startRecording();

                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[bufferSize];

                while (isStreaming && !client.isClosed()) {
                    int read = audioRecord.read(buffer, 0, bufferSize);
                    if (read > 0) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }

                audioRecord.stop();
                audioRecord.release();
                client.close();
                serverSocket.close();

            } catch (Exception e) {
                if (isStreaming) {
                    runOnUiThread(() -> tvStatus.setText("❌ Error: " + e.getMessage()));
                }
            }
        });

        streamThread.start();
    }

    private void stopStreaming() {
        isStreaming = false;
        btnToggle.setText("▶ Iniciar");
        tvStatus.setText("⏹ Detenido");

        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
    }
}
