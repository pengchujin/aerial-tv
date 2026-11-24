package org.example.aerialclock;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextClock;

public final class ClockActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextClock clock = new TextClock(this);
        clock.setGravity(Gravity.CENTER);
        clock.setTextSize(64);
        setContentView(clock);
    }
}
