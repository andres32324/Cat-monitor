package com.catmonitor;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.net.Socket;

public class ClientActivity extends AppCompatActivity {

    private static final int PORT = 9999;
    private static final int SAMPLE_RATE = 44100;

    private AudioTrack audioTrack;
    private Socket socket;
    private Thread listenThread;
    private boolean isListening = false;

    private EditText etIp;
    private Button btnToggle;
    private TextView tvStatus;
    private SeekBar seekVolume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        etIp = findViewById(R.id.etIp);
        btnToggle = findViewById(R.id.btnToggle);
        tvStatus = findViewById(R.id.tvStatus);
        seekVolume = findViewById(R.id.seekVolume);

        seekVolume.setMax(100);
        seekVolume.setProgress(80);
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (audioTrack != null) {
                    float vol = progress / 100f;
                    audioTrack.setVolume(vol);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnToggle.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                String ip = etIp.getText().toString().trim();
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Ingresa la IP del teléfono del gato", Toast.LENGTH_SHORT).show();
                    return;
                }
                startListening(ip);
            }
        });
    }

    private void startListening(String ip) {
        isListening = true;
        btnToggle.setText("⏹ Detener");
        tvStatus.setText("⏳ Conectando a " + ip + "...");

        listenThread = new Thread(() -> {
            try {
                socket = new Socket(ip, PORT);

                int bufferSize = AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 4,
                        AudioTrack.MODE_STREAM);

                audioTrack.setVolume(seekVolume.getProgress() / 100f);
                audioTrack.play();

                runOnUiThread(() -> tvStatus.setText("🎧 Escuchando al gato en vivo... 🐱"));

                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[bufferSize];

                while (isListening) {
                    int bytesRead = in.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        audioTrack.write(buffer, 0, bytesRead);
                    }
                }

                audioTrack.stop();
                audioTrack.release();
                socket.close();

            } catch (Exception e) {
                if (isListening) {
                    runOnUiThread(() -> {
                        tvStatus.setText("❌ Error de conexión: " + e.getMessage() +
                                "\n\nVerifica que:\n• El servidor esté activo\n• Misma red WiFi\n• IP correcta");
                        btnToggle.setText("▶ Conectar");
                        isListening = false;
                    });
                }
            }
        });

        listenThread.start();
    }

    private void stopListening() {
        isListening = false;
        btnToggle.setText("▶ Conectar");
        tvStatus.setText("⏹ Desconectado");

        try {
            if (socket != null && !socket.isClosed()) socket.close();
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
    }
}
