
package com.example.androidbtcontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_BT = 1001;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEVICE_ADDRESS = "88:A2:9E:03:49:80";

    private Button connectButton, disconnectButton;
    private TextView statusText, selectedDeviceText;

    private BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static BluetoothSocket socket;
    private static OutputStream out;

    private BluetoothDevice selectedDevice;

    private ActivityResultLauncher<Intent> enableBtLauncher;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        statusText = findViewById(R.id.statusText);
        selectedDeviceText = findViewById(R.id.selectedDeviceText);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bluetoothManager.getAdapter();

        connectButton.setOnClickListener(v -> connect());
        disconnectButton.setOnClickListener(v -> disconnect());

        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        status("Bluetooth not enabled");
                    }
                });

        if (adapter != null) {
            selectedDevice = adapter.getRemoteDevice(DEVICE_ADDRESS);
            selectedDeviceText.setText(String.format(Locale.getDefault(), "%s [%s]", selectedDevice.getName(), selectedDevice.getAddress()));
        }
    }

    private boolean ensureBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
                return false;
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void connect() {
        if (!ensureBtPermissions()) return;
        if (adapter == null) {
            status("Bluetooth not available");
            return;
        }
        if (!adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtLauncher.launch(enableBtIntent);
            return;
        }
        if (selectedDevice == null) {
            status("Device not found");
            return;
        }
        new Thread(() -> {
            try {
                @SuppressLint("MissingPermission")
                BluetoothSocket tmp = selectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                tmp.connect();
                socket = tmp;
                out = socket.getOutputStream();
                status("Connected");
                Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                startActivity(intent);
            } catch (Exception e) {
                status("Connect failed: " + e.getMessage());
                closeQuietly();
            }
        }).start();
    }

    private void disconnect() {
        closeQuietly();
        status("Disconnected");
    }

    private void status(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    public static void sendLine(String line) {
        new Thread(() -> {
            try {
                if (out != null) {
                    out.write(line.getBytes());
                    out.flush();
                }
            } catch (IOException e) {
                //status("Send failed: " + e.getMessage());
                closeQuietly();
            }
        }).start();
    }

    private static void closeQuietly() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        out = null;
        socket = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
