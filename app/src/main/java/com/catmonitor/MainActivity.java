package com.catmonitor;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnServer = findViewById(R.id.btnServer);
        Button btnClient = findViewById(R.id.btnClient);

        // Modo servidor: el telefono viejo junto al gato
        btnServer.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ServerActivity.class);
            startActivity(intent);
        });

        // Modo cliente: tu telefono para escuchar
        btnClient.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, ClientActivity.class);
            startActivity(intent);
        });
    }
}
