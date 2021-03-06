/**
 * Copyright 2012 Kjetil Ørbekk <kjetil.orbekk@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orbekk.same.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import com.google.protobuf.RpcCallback;
import com.orbekk.protobuf.Rpc;
import com.orbekk.same.SameController;
import com.orbekk.same.Services;
import com.orbekk.same.Services.MasterState;
import com.orbekk.same.Services.NetworkDirectory;
import com.orbekk.same.State;
import com.orbekk.same.State.Component;
import com.orbekk.same.StateChangedListener;
import com.orbekk.same.android.net.Networking;
import com.orbekk.same.config.Configuration;
import com.orbekk.util.DelayedOperation;

public class SameService extends Service {
    public final static int CREATE_NETWORK = 3;
    
    /**
     * masterUrl: getData().getString("masterUrl")
     */
    public final static int JOIN_NETWORK = 4;
    public final static int ADD_STATE_RECEIVER = 5;
    public final static int REMOVE_STATE_RECEIVER = 6;

    /**
     * arg1: Operation number.
     * bundle: A Bundle created with ComponentBundle
     */
    public final static int SET_STATE = 7;
    
    /**
     * bundle: A Bundle created with ComponentBundle.
     */
    public final static int UPDATED_STATE_CALLBACK = 8;
    
    /**
     * arg1: Operation number.
     * arg2: Status code.
     * obj: Status message.
     */
    public final static int OPERATION_STATUS_CALLBACK = 9;

    public final static int KILL_MASTER = 10;

    public final static int PPORT = 15070;
    public final static int SERVICE_PORT = 15068;
    
    public final static String DIRECTORY_HOST = "flode.pvv.ntnu.no";
    public final static int DIRECTORY_PORT = 15072;

    private Logger logger = LoggerFactory.getLogger(getClass());
    private SameController sameController = null;
    private Configuration configuration = null;
    private Vector<Messenger> stateReceivers = new Vector<Messenger>();
    
    private ArrayList<String> networkNames = new ArrayList<String>();
    private ArrayList<String> networkUrls = new ArrayList<String>();
    
    class InterfaceHandler extends Handler {
        @Override public void handleMessage(Message message) {
            switch (message.what) {
                case CREATE_NETWORK:
                    logger.info("CREATE_NETWORK");
                    create();
                    break;
                case JOIN_NETWORK:
                    logger.info("JOIN_NETWORK");
                    String masterUrl = message.getData().getString("masterLocation");
                    MasterState master = MasterState.newBuilder()
                            .setMasterLocation(masterUrl).build();
                    sameController.getClient().joinNetwork(master);
                    break;
                case ADD_STATE_RECEIVER:
                    logger.info("ADD_STATE_RECEIVER: {}", message);
                    Messenger messenger = message.replyTo;
                    if (messenger != null) {
                        stateReceivers.add(messenger);
                        sendAllState(messenger);
                    } else {
                        logger.error("ADD_STATE_RECEIVER: Missing Messenger.");
                    }
                    break;
                case REMOVE_STATE_RECEIVER:
                    logger.info("REMOVE_STATE_RECEIVER: {}", message);
                    Messenger droppedMessenger = (Messenger)message.obj;
                    stateReceivers.remove(droppedMessenger);
                    break;
                case SET_STATE:
                    State.Component updatedComponent =
                            new ComponentBundle(message.getData()).getComponent();
                    int id = message.arg1;
                    DelayedOperation op = sameController.getClient().getInterface()
                            .set(updatedComponent);
                    operationStatusCallback(op, id, message.replyTo);
                    break;
                case KILL_MASTER:
                    logger.info("Kill master.");
                    sameController.killMaster();
                default:
                    super.handleMessage(message);
            }
        }
    }
    
    private final Messenger messenger = new Messenger(new InterfaceHandler());

    private StateChangedListener stateListener = new StateChangedListener() {
        @Override
        public void stateChanged(Component component) {
            synchronized (stateReceivers) {
                ArrayList<Messenger> dropped = new ArrayList<Messenger>();
                for (Messenger messenger : stateReceivers) {
                    Message message = Message.obtain(null, UPDATED_STATE_CALLBACK);
                    message.setData(new ComponentBundle(component).getBundle());
                    try {
                        messenger.send(message);
                    } catch (RemoteException e) {
                        logger.warn("Failed to send update. Dropping state receiver.");
                        e.printStackTrace();
                        dropped.add(messenger);
                    }
                }
                stateReceivers.removeAll(dropped);
            }
        }
    };
    
    private void operationStatusCallback(DelayedOperation op, int id, Messenger replyTo) {
        op.waitFor();
        synchronized (stateReceivers) {
            Message message = Message.obtain(null,
                    OPERATION_STATUS_CALLBACK);
            message.arg1 = id;
            message.getData().putInt("statusCode", op.getStatus().getStatusCode());
            message.getData().putString("statusMessage", op.getStatus().getMessage());
            try {
                replyTo.send(message);
            } catch (RemoteException e) {
                logger.warn("Unable to send update result: " + 
                        op.getStatus());
                e.printStackTrace();
            }
        }
    }
    
    private void sendAllState(Messenger messenger) {
        State state = sameController.getClient().getInterface().getState();
        for (Component c : state.getComponents()) {
            Message message = Message.obtain(null, UPDATED_STATE_CALLBACK);
            message.setData(new ComponentBundle(c).getBundle());
            try {
                messenger.send(message);
            } catch (RemoteException e) {
                logger.warn("Failed to send state.");
                e.printStackTrace();
                return;
            }
        }
    }

    private void initializeConfiguration() {
        Properties properties = new Properties();
        String localIp = new Networking(this)
                .getWlanAddress().getHostAddress();
        String baseUrl = "http://" + localIp + ":" + SERVICE_PORT + "/";
        properties.setProperty("port", ""+SERVICE_PORT);
        properties.setProperty("pport", ""+PPORT);
        properties.setProperty("localIp", localIp);
        properties.setProperty("baseUrl", baseUrl);
        properties.setProperty("directoryLocation", DIRECTORY_HOST + ":" +
                DIRECTORY_PORT);
        properties.setProperty("networkName", "AndroidNetwork");
        configuration = new Configuration(properties);
    }
    
    /** Create a public network. */
    private void create() {
        sameController.createNetwork(configuration.get("networkName"));
        sameController.registerCurrentNetwork();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        logger.info("onBind()");
        
        // Make sure service continues to run after it is unbound.
        Intent service = new Intent(this, getClass());
        startService(service);
        
        return messenger.getBinder();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.info("onStartCommand()");
        return START_NOT_STICKY;
    }
    
    @Override
    public void onCreate() {
        logger.info("onCreate()");
        
        if (sameController == null) {
            initializeConfiguration();
            sameController = SameController.create(configuration);
            try {
                sameController.start();
                sameController.getClient().getInterface()
                    .addStateListener(stateListener);
            } catch (Exception e) {
                logger.error("Failed to start server", e);
            }
        }
    }
    
    @Override
    public void onDestroy() {
        logger.info("onDestroy()");
        if (sameController != null) {
            sameController.stop();
        }
    }

}
