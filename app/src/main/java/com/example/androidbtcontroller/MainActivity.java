package com.example.androidbtcontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_BT = 1001;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private Button connectButton, disconnectButton, scanButton;
    private SeekBar leftSeek, rightSeek;
    private CheckBox armCheck;
    private TextView statusText, selectedDeviceText;
    private RecyclerView scanResultView;

    private BluetoothAdapter adapter;
    private BluetoothSocket socket;
    private OutputStream out;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seq = 0;

    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private ScanResultAdapter scanResultAdapter;
    private BluetoothDevice selectedDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        scanButton = findViewById(R.id.scanButton);
        leftSeek = findViewById(R.id.leftSeek);
        rightSeek = findViewById(R.id.rightSeek);
        armCheck = findViewById(R.id.armCheck);
        statusText = findViewById(R.id.statusText);
        selectedDeviceText = findViewById(R.id.selectedDeviceText);
        scanResultView = findViewById(R.id.scanResultView);

        scanResultView.setLayoutManager(new LinearLayoutManager(this));
        scanResultAdapter = new ScanResultAdapter(discoveredDevices, this::onDeviceSelected);
        scanResultView.setAdapter(scanResultAdapter);

        adapter = BluetoothAdapter.getDefaultAdapter();

        connectButton.setOnClickListener(v -> connect());
        disconnectButton.setOnClickListener(v -> disconnect());
        scanButton.setOnClickListener(v -> startScan());

        View.OnClickListener sendArm = v -> sendArm();
        armCheck.setOnClickListener(sendArm);

        SeekBar.OnSeekBarChangeListener onSeek = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { sendDrive(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
        leftSeek.setOnSeekBarChangeListener(onSeek);
        rightSeek.setOnSeekBarChangeListener(onSeek);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(receiver, filter);
    }

    private boolean ensureBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            String[] perms = new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            boolean need = false;
            for (String p : perms) {
                if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    need = true; break;
                }
            }
            if (need) {
                ActivityCompat.requestPermissions(this, perms, REQ_BT);
                return false;
            }
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!ensureBtPermissions()) return;
        if (adapter == null || !adapter.isEnabled()) {
            status("Bluetooth not available or disabled");
            return;
        }
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }
        discoveredDevices.clear();
        scanResultAdapter.notifyDataSetChanged();
        if (!adapter.startDiscovery()) {
            status("Scan failed to start");
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device != null && device.getName() != null && !discoveredDevices.contains(device)) {
                    discoveredDevices.add(device);
                    scanResultAdapter.notifyDataSetChanged();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                status("Scanning for devices...");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                status("Scan finished.");
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void onDeviceSelected(BluetoothDevice device) {
        selectedDevice = device;
        selectedDeviceText.setText(device.getName() + " [" + device.getAddress() + "]");
    }

    private void connect() {
        if (!ensureBtPermissions()) return;
        if (adapter == null || !adapter.isEnabled()) {
            status("Bluetooth not available or disabled");
            return;
        }
        if (selectedDevice == null) {
            status("No device selected");
            return;
        }
        new Thread(() -> {
            try {
                @SuppressLint("MissingPermission")
                BluetoothSocket tmp = selectedDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                adapter.cancelDiscovery();
                tmp.connect();
                socket = tmp;
                out = socket.getOutputStream();
                status("Connected");
                // Kick off a periodic ping to keep link fresh
                handler.post(pingRunnable);
            } catch (Exception e) {
                status("Connect failed: " + e.getMessage());
                closeQuietly();
            }
        }).start();
    }

    private void disconnect() {
        handler.removeCallbacks(pingRunnable);
        closeQuietly();
        status("Disconnected");
    }

    private void status(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    private final Runnable pingRunnable = new Runnable() {
        @Override public void run() {
            sendLine("PING\n");
            handler.postDelayed(this, 300);
        }
    };

    private void sendDrive() {
        float left = (leftSeek.getProgress() - 100) / 100f;  // [-1,1]
        float right = (rightSeek.getProgress() - 100) / 100f; // [-1,1]
        String line = String.format("V1:%f;%f;%d\n", left, right, (seq++ & 0x7fffffff));
        sendLine(line);
    }

    private void sendArm() {
        String line = "ARM:" + (armCheck.isChecked() ? "1" : "0") + "\n";
        sendLine(line);
    }

    @SuppressLint("MissingPermission")
    private void sendLine(String line) {
        new Thread(() -> {
            try {
                if (out != null) {
                    out.write(line.getBytes());
                    out.flush();
                }
            } catch (IOException e) {
                status("Send failed: " + e.getMessage());
                closeQuietly();
            }
        }).start();
    }

    private void closeQuietly() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        out = null;
        socket = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        disconnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            // Try scan again if granted
            boolean granted = true;
            for (int r : grantResults) { if (r != PackageManager.PERMISSION_GRANTED) { granted = false; break; } }
            if (granted) startScan();
        }
    }

    // RecyclerView Adapter for scan results
    private static class ScanResultAdapter extends RecyclerView.Adapter<ScanResultAdapter.ViewHolder> {
        private final List<BluetoothDevice> devices;
        private final OnDeviceSelectedListener listener;

        public interface OnDeviceSelectedListener {
            void onDeviceSelected(BluetoothDevice device);
        }

        public ScanResultAdapter(List<BluetoothDevice> devices, OnDeviceSelectedListener listener) {
            this.devices = devices;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            return new ViewHolder(view);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BluetoothDevice device = devices.get(position);
            holder.textView.setText(device.getName() + "\n" + device.getAddress());
            holder.itemView.setOnClickListener(v -> listener.onDeviceSelected(device));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            public TextView textView;
            public ViewHolder(View view) {
                super(view);
                textView = (TextView) view;
            }
        }
    }
}
