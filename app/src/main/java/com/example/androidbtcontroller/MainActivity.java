
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
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String DEBUG_TAG = "BT_DEBUG";
    private static final int REQ_BT_PERMISSIONS = 1001;
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
                    } else {
                        status("Bluetooth enabled, ready to connect");
                    }
                });

        if (ensureBtPermissions()) {
            initializeBluetoothDevice();
        }
    }

    @SuppressLint("MissingPermission")
    private void initializeBluetoothDevice() {
        if (adapter != null) {
            try {
                selectedDevice = adapter.getRemoteDevice(DEVICE_ADDRESS);
                String deviceName = selectedDevice.getName();
                if (deviceName == null) {
                    deviceName = "Unknown Device";
                }
                selectedDeviceText.setText(String.format(Locale.getDefault(), "%s [%s]", deviceName, selectedDevice.getAddress()));
                status("Device selected. Ready to connect.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to get remote device", e);
                status("Failed to get remote device");
            }
        }
    }

    private boolean ensureBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            List<String> permissionsToRequest = new ArrayList<>();
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQ_BT_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMISSIONS) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                initializeBluetoothDevice();
            } else {
                status("Bluetooth permissions not granted");
            }
        }
    }

    private void connect() {
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
            status("Device not found or permissions denied");
            if (ensureBtPermissions()) {
                initializeBluetoothDevice();
            }
            return;
        }
        if (readerThread != null && readerThread.isAlive()) {
            status("Connection attempt already in progress");
            return;
        }
        readerThread = new Thread(this::connectionLoop);
        readerThread.start();
    }

    @SuppressLint({"MissingPermission", "HardwareIds"})
    private void connectionLoop() {
        BluetoothSocket tmpSocket = null;
        try {
            status("Connecting (Debug Mode)...");

            Log.d(DEBUG_TAG, "Cancelling discovery...");
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            Log.d(DEBUG_TAG, "Discovery cancelled.");

            Log.d(DEBUG_TAG, "Attempting connection with standard SPP UUID: " + SPP_UUID.toString());
            status("Trying standard SPP connection...");

            tmpSocket = selectedDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID);

            Log.d(DEBUG_TAG, "Socket created. Calling connect()...");
            tmpSocket.connect();
            Log.d(DEBUG_TAG, "connect() returned successfully.");

            socket = tmpSocket;
            out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            String helloLine = reader.readLine();
            Log.d(TAG, "Received: " + helloLine);
            WalleBtProtocol.ServerHello hello = WalleBtProtocol.INSTANCE.parseServerHello(helloLine);

            if (hello != null && hello.getVersion() == 2) {
                sessionNonceHex = hello.getSessionNonceHex();
                sequenceNumber = 1L;
                status("Connected (V2)");

                Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                startActivity(intent);

                while (socket != null && socket.isConnected()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    Log.d(TAG, "Received: " + line);
                    WalleBtProtocol.AckResult result = WalleBtProtocol.INSTANCE.parseAckOrNak(line);
                    if (result instanceof WalleBtProtocol.AckResult.Nak) {
                        String code = ((WalleBtProtocol.AckResult.Nak) result).getCode();
                        Log.w(TAG, "Received NAK with code: " + code);
                        if ("bad_nonce".equals(code) || "bad_hmac".equals(code)) {
                            status("Authentication error, disconnecting");
                            break;
                        }
                    }
                }

            } else {
                status("Connected (V1)");
                Intent intent = new Intent(MainActivity.this, ControlActivity.class);
                startActivity(intent);
                while (socket != null && socket.isConnected()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    Log.d(TAG, "V1 Received: " + line);
                }
            }

        } catch (Exception e) {
            Log.e(DEBUG_TAG, "Connection failed in simplified loop", e);
            status("Connection failed: " + e.getMessage());
        } finally {
            Log.d(DEBUG_TAG, "Connection loop finished.");
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
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
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
