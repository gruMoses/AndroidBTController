package com.example.androidbtcontroller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.example.androidbtcontroller.databinding.ActivityControlBinding;
import java.util.Locale;

public class ControlActivity extends AppCompatActivity {

    private ActivityControlBinding binding;
    private float centerX, centerY;
    private Vibrator vibrator;
    private boolean boundaryHit = false;

    private static final float EXPO_FACTOR = 0.4f;
    private static final float DEAD_ZONE_RADIUS = 20f;

    @SuppressLint({"ClickableViewAccessibility", "ServiceCast"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityControlBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        vibrator = vibratorManager.getDefaultVibrator();

        binding.joystickArea.post(() -> {
            centerX = binding.joystickArea.getWidth() / 2f;
            centerY = binding.joystickArea.getHeight() / 2f;
            binding.joystick.setX(centerX - binding.joystick.getWidth() / 2f);
            binding.joystick.setY(centerY - binding.joystick.getHeight() / 2f);
        });

        binding.joystickArea.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    vibrate(50);
                case MotionEvent.ACTION_MOVE:
                    handleJoystickMove(event);
                    break;
                case MotionEvent.ACTION_UP:
                    vibrate(50);
                    resetJoystick();
                    break;
            }
            return true;
        });
    }

    private void handleJoystickMove(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        float dx = x - centerX;
        float dy = y - centerY;

        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        float maxDistance = binding.joystickArea.getWidth() / 2f - binding.joystick.getWidth() / 2f;

        if (distance > maxDistance) {
            if (!boundaryHit) {
                vibrate(100);
                boundaryHit = true;
            }
            x = centerX + (dx / distance) * maxDistance;
            y = centerY + (dy / distance) * maxDistance;
        } else {
            boundaryHit = false;
        }

        binding.joystick.setX(x - binding.joystick.getWidth() / 2f);
        binding.joystick.setY(y - binding.joystick.getHeight() / 2f);

        calculateAndSendMotorSpeeds();
    }

    private void resetJoystick() {
        boundaryHit = false;
        binding.joystick.animate().x(centerX - binding.joystick.getWidth() / 2f).y(centerY - binding.joystick.getHeight() / 2f).setDuration(100).start();
        sendStopCommand();
        updatePowerDisplay(0, 0);
    }

    private void calculateAndSendMotorSpeeds() {
        float dx = binding.joystick.getX() + binding.joystick.getWidth() / 2f - centerX;
        float dy = binding.joystick.getY() + binding.joystick.getHeight() / 2f - centerY;

        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance < DEAD_ZONE_RADIUS) {
            sendStopCommand();
            updatePowerDisplay(0, 0);
            return;
        }

        float maxDistance = binding.joystickArea.getWidth() / 2f - binding.joystick.getWidth() / 2f;

        float normalizedDx = dx / maxDistance;
        float normalizedDy = -dy / maxDistance;

        float expoDx = (1 - EXPO_FACTOR) * normalizedDx + EXPO_FACTOR * (float) Math.pow(normalizedDx, 3);
        float expoDy = (1 - EXPO_FACTOR) * normalizedDy + EXPO_FACTOR * (float) Math.pow(normalizedDy, 3);

        float left = Math.max(-1, Math.min(1, expoDy + expoDx));
        float right = Math.max(-1, Math.min(1, expoDy - expoDx));

        updatePowerDisplay(left, right);

        if (MainActivity.sessionNonceHex != null) {
            int leftInt = WalleBtProtocol.INSTANCE.floatToInt(left);
            int rightInt = WalleBtProtocol.INSTANCE.floatToInt(right);
            long tsMs = System.currentTimeMillis();
            String message = WalleBtProtocol.INSTANCE.buildCmd2(leftInt, rightInt, MainActivity.sequenceNumber, tsMs, MainActivity.sessionNonceHex, MainActivity.SECRET);
            MainActivity.sendLine(message);
            MainActivity.sequenceNumber++;
        } else {
            String message = String.format(Locale.getDefault(), "V1:%f;%f;%d", left, right, (MainActivity.sequenceNumber++ & 0x7fffffff));
            MainActivity.sendLine(message);
        }
    }

    private void sendStopCommand() {
        if (MainActivity.sessionNonceHex != null) {
            long tsMs = System.currentTimeMillis();
            String message = WalleBtProtocol.INSTANCE.buildCmd2(0, 0, MainActivity.sequenceNumber, tsMs, MainActivity.sessionNonceHex, MainActivity.SECRET);
            MainActivity.sendLine(message);
            MainActivity.sequenceNumber++;
        } else {
            String message = String.format(Locale.getDefault(), "V1:0;0;%d", (MainActivity.sequenceNumber++ & 0x7fffffff));
            MainActivity.sendLine(message);
        }
    }

    private void updatePowerDisplay(float left, float right) {
        binding.leftPowerText.setText(String.format(Locale.getDefault(), "L: %d%%", (int) (left * 100)));
        binding.rightPowerText.setText(String.format(Locale.getDefault(), "R: %d%%", (int) (right * 100)));
    }

    private void vibrate(long milliseconds) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
