package com.bou.payhelper;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AutoCapturePlugin.class);
        super.onCreate(savedInstanceState);
        NightlySyncReceiver.scheduleNext(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        NightlySyncReceiver.scheduleNext(this);
    }
}
