
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
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQ_BT = 1001;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEVICE_ADDRESS = "88:A2:9E:03:1A:07";

    private Button connectButton, disconnectButton;
    private TextView statusText, selectedDeviceText;

    private BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static BluetoothSocket socket;
    private static OutputStream out;
    private Thread readerThread;

    public static String sessionNonceHex;
    public static final String SECRET = "your_secret_key_here";
    public static long sequenceNumber = 1L;

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
        // Start the connection and reader thread
        readerThread = new Thread(this::connectionLoop);
        readerThread.start();
    }

    @SuppressLint("MissingPermission")
    private void connectionLoop() {
        try {
            status("Connecting...");
            BluetoothSocket tmp = selectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            tmp.connect();
            socket = tmp;
            out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            // --- V2 Handshake ---
            String helloLine = reader.readLine();
            Log.d(TAG, "Received: " + helloLine);
            WalleBtProtocol.ServerHello hello = WalleBtProtocol.INSTANCE.parseServerHello(helloLine);

            if (hello != null && hello.getVersion() == 2) {
                sessionNonceHex = hello.getSessionNonceHex();
                sequenceNumber = 1L; // Reset sequence number on new session
                status("Connected (V2)");

                // Launch ControlActivity after successful handshake
                Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                startActivity(intent);

                // --- ACK/NAK Loop ---
                while (socket != null && socket.isConnected()) {
                    String line = reader.readLine();
                    if (line == null) break; // End of stream
                    Log.d(TAG, "Received: " + line);
                    WalleBtProtocol.AckResult result = WalleBtProtocol.INSTANCE.parseAckOrNak(line);
                    if (result instanceof WalleBtProtocol.AckResult.Nak) {
                        String code = ((WalleBtProtocol.AckResult.Nak) result).getCode();
                        Log.w(TAG, "Received NAK with code: " + code);
                        if ("bad_nonce".equals(code) || "bad_hmac".equals(code)) {
                            status("Authentication error, disconnecting");
                            closeQuietly();
                            break; // Exit loop
                        }
                    }
                }

            } else {
                // --- V1 Fallback ---
                status("Connected (V1)");
                Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                startActivity(intent);
                // For V1, we don't need to listen for ACKs, so the thread can exit.
            }

        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
            status("Connection failed: " + e.getMessage());
        } finally {
            Log.d(TAG, "Connection loop finished, closing socket.");
            closeQuietly();
        }
    }

    private void disconnect() {
        closeQuietly();
        status("Disconnected");
    }

    private void status(String s) {
        Log.d(TAG, "Status: " + s);
        runOnUiThread(() -> statusText.setText(s));
    }

    public static void sendLine(String line) {
        if (out == null) {
            Log.e(TAG, "sendLine failed: output stream is null");
            return;
        }
        new Thread(() -> {
            try {
                Log.d(TAG, "Sending: " + line);
                out.write((line + "\n").getBytes());
                out.flush();
            } catch (IOException e) {
                Log.e(TAG, "Send failed", e);
                cleanupStaticConnection();
            }
        }).start();
    }

    private static void cleanupStaticConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {}
        // Setting to null helps garbage collection and signals that the connection is gone
        out = null;
        socket = null;
        sessionNonceHex = null;
        sequenceNumber = 1L;
    }

    private void closeQuietly() {
        cleanupStaticConnection();
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
