package com.orbekk.same;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.os.Bundle;

import com.orbekk.same.android.SameInterfaceBridge;

public class VariableTestActivity extends Activity {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private SameInterfaceBridge client;
    
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
    
    @Override public void onResume() {
        super.onResume();
        client = new SameInterfaceBridge(this);
        client.connect();
    }
    
    @Override public void onStop() {
        super.onStop();
        client.disconnect();
    }
}