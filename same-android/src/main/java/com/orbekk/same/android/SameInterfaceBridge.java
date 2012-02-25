package com.orbekk.same.android;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import com.orbekk.same.ClientInterface;
import com.orbekk.same.SameService;
import com.orbekk.same.State;
import com.orbekk.same.StateChangedListener;
import com.orbekk.same.UpdateConflict;

public class SameInterfaceBridge implements ClientInterface {
    class ResponseHandler extends Handler {
        @Override public void handleMessage(Message message) {
            switch (message.what) {
            case SameService.UPDATED_STATE_MESSAGE:
                State.Component component = (State.Component)message.obj;
                Toast.makeText(context, "Updated: " + component,
                        Toast.LENGTH_SHORT).show();
            default:
                logger.warn("Received unknown message from service: {}",
                        message);
            }
        }
    }

    private Logger logger = LoggerFactory.getLogger(getClass());
    private Messenger serviceMessenger = null;
    private Messenger responseMessenger = new Messenger(new ResponseHandler());
    private Context context;
    
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceMessenger = new Messenger(service);
            Message message = Message.obtain(null,
                    SameService.ADD_STATE_RECEIVER);
            message.replyTo = responseMessenger;
            try {
                serviceMessenger.send(message);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
        }
    };
    
    public SameInterfaceBridge(Context context) {
        this.context = context;
    }
    
    public void connect() {
        Intent intent = new Intent(context, SameService.class);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public void disconnect() {
        if (serviceMessenger != null) {
            context.unbindService(serviceConnection);
        }
    }

    @Override
    public State getState() {
        return null;
    }

    @Override
    public void set(String name, String data, long revision) throws UpdateConflict {
        logger.info("set({}, {}, {}",
                new Object[]{name, data, revision});
    }

    @Override
    public void addStateListener(StateChangedListener listener) {
        logger.info("addStateListener()");
    }

    @Override
    public void removeStateListener(StateChangedListener listener) {
        logger.info("removeStateListener()");
    }

}