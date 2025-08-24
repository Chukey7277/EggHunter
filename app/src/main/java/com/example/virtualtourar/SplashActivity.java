package com.example.virtualtourar;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Use the animated egg vector drawable
        ImageView animatedEgg = findViewById(R.id.eggImageView);
        animatedEgg.setImageResource(R.drawable.animated_egg);

        // Create a more natural floating animation
        ObjectAnimator floatAnimator = ObjectAnimator.ofFloat(animatedEgg, "translationY", 0f, -30f);
        floatAnimator.setDuration(1500);
        floatAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        floatAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        floatAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

        // Gentle rotation animation
        ObjectAnimator rotateAnimator = ObjectAnimator.ofFloat(animatedEgg, "rotation", -5f, 5f);
        rotateAnimator.setDuration(2000);
        rotateAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        rotateAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        rotateAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

        // Subtle scale animation for bounce effect
        ObjectAnimator scaleXAnimator = ObjectAnimator.ofFloat(animatedEgg, "scaleX", 1f, 1.05f);
        scaleXAnimator.setDuration(1000);
        scaleXAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        scaleXAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        scaleXAnimator.setInterpolator(new BounceInterpolator());

        ObjectAnimator scaleYAnimator = ObjectAnimator.ofFloat(animatedEgg, "scaleY", 1f, 1.05f);
        scaleYAnimator.setDuration(1000);
        scaleYAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        scaleYAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        scaleYAnimator.setInterpolator(new BounceInterpolator());

        // Play all animations together
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(floatAnimator, rotateAnimator, scaleXAnimator, scaleYAnimator);
        animatorSet.start();

        // Set app name
        TextView appNameText = findViewById(R.id.appNameText);
        appNameText.setText("Egg Hunter");

        // Add loading messages
        TextView loadingText = findViewById(R.id.loadingText);
        final String[] loadingMessages = new String[] {
                "Loading AR Experience...",
                "Initializing Camera...",
                "Setting up Location Services...",
                "Preparing Easter Eggs...",
                "Ready to Hunt Eggs?..."
        };

        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] currentMessageIndex = {0};

        Runnable updateMessage = new Runnable() {
            @Override
            public void run() {
                if (currentMessageIndex[0] < loadingMessages.length) {
                    loadingText.setText(loadingMessages[currentMessageIndex[0]]);
                    currentMessageIndex[0]++;
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(updateMessage);

        // Navigate to HomeActivity after delay
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SplashActivity.this, HomeActivity.class));
                finish();
            }
        }, 5000); // 5 seconds total splash screen duration
    }
} 