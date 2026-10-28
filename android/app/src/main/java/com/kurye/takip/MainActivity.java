package com.kurye.takip;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeTrackerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
