package com.orbekk.same;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.orbekk.same.android.ClientInterfaceBridge;
import com.orbekk.util.DelayedOperation;

public class VariableTestActivity extends Activity {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private ClientInterfaceBridge client;
    private Variable<String> variable;
    
    private Variable.OnChangeListener<String> onChangeListener =
            new Variable.OnChangeListener<String>() {
        @Override
        public void valueChanged(Variable<String> unused) {
            variable.update();
            displayVariable();
        }
    };
    
    private void displayVariable() {
        TextView tv = (TextView)findViewById(R.id.variable_text);
        if (variable.get() != null) {
            tv.setText(variable.get());
        }
    }
    
    public void setVariable(View unused) {
        EditText et = (EditText)findViewById(R.id.set_variable_text);
        String newValue = et.getText().toString();
        logger.info("Setting variable.");
        DelayedOperation op = variable.set(newValue);
        logger.info("Waiting for delayed operation.");
        if (!op.getStatus().isOk()) {
            Toast.makeText(this, "Failed to update: " + op.getStatus(),
                    Toast.LENGTH_SHORT)
                .show();
        }
    }
    
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.variable_test);
    }
    
    @Override public void onResume() {
        super.onResume();
        client = new ClientInterfaceBridge(this);
        client.connect();
        variable = client.createVariableFactory()
                .createString("TestVariable");
        variable.setOnChangeListener(onChangeListener);
        displayVariable();
    }
    
    @Override public void onStop() {
        super.onStop();
        client.disconnect();
    }
}
