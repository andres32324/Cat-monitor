package com.catmonitor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.Socket;

public class ClientActivity extends AppCompatActivity {

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;

    private AudioTrack audioTrack;
    private Socket audioSocket, videoSocket;
    private Thread audioThread, videoThread;
    private boolean isListening = false;

    private EditText etIp;
    private Button btnToggle;
    private TextView tvStatus;
    private SeekBar seekVolume;
    private ImageView ivVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        etIp       = findViewById(R.id.etIp);
        btnToggle  = findViewById(R.id.btnToggle);
        tvStatus   = findViewById(R.id.tvStatus);
        seekVolume = findViewById(R.id.seekVolume);
        ivVideo    = findViewById(R.id.ivVideo);

        seekVolume.setMax(100);
        seekVolume.setProgress(80);
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (audioTrack != null) audioTrack.setVolume(p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        btnToggle.setOnClickListener(v -> {
            if (isListening) stopListening();
            else {
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
        tvStatus.setText("⏳ Conectando...");
        startAudioThread(ip);
        startVideoThread(ip);
    }

    private void startAudioThread(String ip) {
        audioThread = new Thread(() -> {
            try {
                audioSocket = new Socket(ip, PORT_AUDIO);
                int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 4, AudioTrack.MODE_STREAM);
                audioTrack.setVolume(seekVolume.getProgress() / 100f);
                audioTrack.play();
                runOnUiThread(() -> tvStatus.setText("😺 Recibiendo audio + video en vivo..."));

                InputStream in = audioSocket.getInputStream();
                byte[] buffer = new byte[bufferSize];
                while (isListening) {
                    int read = in.read(buffer, 0, buffer.length);
                    if (read > 0) audioTrack.write(buffer, 0, read);
                }
                audioTrack.stop(); audioTrack.release(); audioSocket.close();
            } catch (Exception e) {
                if (isListening) runOnUiThread(() -> {
                    tvStatus.setText("❌ Error: " + e.getMessage());
                    isListening = false;
                    btnToggle.setText("▶ Conectar");
                });
            }
        });
        audioThread.start();
    }

    private void startVideoThread(String ip) {
        videoThread = new Thread(() -> {
            try {
                Thread.sleep(500);
                videoSocket = new Socket(ip, PORT_VIDEO);
                DataInputStream din = new DataInputStream(videoSocket.getInputStream());

                while (isListening) {
                    int len = din.readInt();
                    if (len <= 0 || len > 5_000_000) continue;
                    byte[] jpegBytes = new byte[len];
                    din.readFully(jpegBytes);
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                    if (bmp != null) runOnUiThread(() -> ivVideo.setImageBitmap(bmp));
                }
                videoSocket.close();
            } catch (Exception e) {
                if (isListening) e.printStackTrace();
            }
        });
        videoThread.start();
    }

    private void stopListening() {
        isListening = false;
        btnToggle.setText("▶ Conectar");
        tvStatus.setText("⏹ Desconectado");
        try {
            if (audioSocket != null && !audioSocket.isClosed()) audioSocket.close();
            if (videoSocket != null && !videoSocket.isClosed()) videoSocket.close();
            if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override protected void onDestroy() { super.onDestroy(); stopListening(); }
}
