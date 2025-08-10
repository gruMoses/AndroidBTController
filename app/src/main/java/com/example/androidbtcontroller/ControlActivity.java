package com.example.androidbtcontroller;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class ControlActivity extends AppCompatActivity {

    private FrameLayout joystickArea;
    private ImageView joystick;

    private float centerX, centerY;
    private int seq = 0;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        joystickArea = findViewById(R.id.joystick_area);
        joystick = findViewById(R.id.joystick);

        joystickArea.post(() -> {
            centerX = joystickArea.getWidth() / 2f;
            centerY = joystickArea.getHeight() / 2f;
            joystick.setX(centerX - joystick.getWidth() / 2f);
            joystick.setY(centerY - joystick.getHeight() / 2f);
        });

        joystickArea.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    float x = event.getX();
                    float y = event.getY();

                    float dx = x - centerX;
                    float dy = y - centerY;

                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    float maxDistance = joystickArea.getWidth() / 2f - joystick.getWidth() / 2f;

                    if (distance > maxDistance) {
                        x = centerX + (dx / distance) * maxDistance;
                        y = centerY + (dy / distance) * maxDistance;
                    }

                    joystick.setX(x - joystick.getWidth() / 2f);
                    joystick.setY(y - joystick.getHeight() / 2f);

                    calculateAndSendMotorSpeeds();
                    break;
                case MotionEvent.ACTION_UP:
                    joystick.animate().x(centerX - joystick.getWidth() / 2f).y(centerY - joystick.getHeight() / 2f).setDuration(100).start();
                    sendStopCommand();
                    break;
            }
            return true;
        });
    }

    private void calculateAndSendMotorSpeeds() {
        float dx = joystick.getX() + joystick.getWidth() / 2f - centerX;
        float dy = joystick.getY() + joystick.getHeight() / 2f - centerY;

        float maxDistance = joystickArea.getWidth() / 2f - joystick.getWidth() / 2f;

        float normalizedDx = dx / maxDistance;
        float normalizedDy = -dy / maxDistance; // Invert Y-axis

        float left = normalizedDy + normalizedDx;
        float right = normalizedDy - normalizedDx;

        left = Math.max(-1, Math.min(1, left));
        right = Math.max(-1, Math.min(1, right));

        String line = String.format(Locale.getDefault(), "V1:%f;%f;%d\n", left, right, (seq++ & 0x7fffffff));
        MainActivity.sendLine(line);
    }

    private void sendStopCommand() {
        String line = String.format(Locale.getDefault(), "V1:0;0;%d\n", (seq++ & 0x7fffffff));
        MainActivity.sendLine(line);
    }
}
