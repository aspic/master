package com.orbekk.same;

import static com.orbekk.same.StackTraceUtil.throwableToString;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbekk.paxos.MasterProposer;
import com.orbekk.same.State.Component;
import com.orbekk.util.DelayedOperation;
import com.orbekk.util.WorkQueue;

public class Client {
    public static long MASTER_TAKEOVER_TIMEOUT = 4000l;
    private Logger logger = LoggerFactory.getLogger(getClass());
    /** TODO: Not really useful yet. Remove? */
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private ConnectionManager connections;
    State state;
    private String myUrl;
    String masterUrl;
    private int masterId = 0;
    private MasterController masterController = null;
    private Broadcaster broadcaster;
    private Future<Integer> currentMasterProposal = null;
    
    private List<StateChangedListener> stateListeners =
            new ArrayList<StateChangedListener>();
    private NetworkNotificationListener networkListener;

    public class ClientInterfaceImpl implements ClientInterface {
        private ClientInterfaceImpl() {
        }

        /** Get a copy of all the client state.
         */
        @Override
        public State getState() {
            return new State(state);
        }

        // TODO: Do this asynchronously? Currently this is already achieved
        // on Android, which makes the Java and Android versions different.
        @Override
        public DelayedOperation set(Component component) {
            DelayedOperation op = new DelayedOperation();
            if (connectionState != ConnectionState.STABLE) {
                op.complete(DelayedOperation.Status.createError(
                        "Not connected to master: " + connectionState));
                return op;
            }
            MasterService master = connections.getMaster(masterUrl);
            try {
                boolean success = master.updateStateRequest(
                        component.getName(), component.getData(),
                        component.getRevision());
                if (success) {
                    op.complete(DelayedOperation.Status.createOk());
                } else {
                    op.complete(DelayedOperation.Status
                            .createConflict("Conflict from master"));
                }
            } catch (Exception e) {
                logger.error("Unable to contact master. Update fails.", e);
                String e_ = throwableToString(e);
                op.complete(DelayedOperation.Status.createError(
                        "Error contacting master. Update fails: " + e_));
                startMasterElection();
            }
            return op;
        }

        @Override
        public void addStateListener(StateChangedListener listener) {
            stateListeners.add(listener);
        }

        @Override
        public void removeStateListener(StateChangedListener listener) {
            stateListeners.remove(listener);
        }

        @Override
        public ConnectionState getConnectionState() {
            return Client.this.getConnectionState();
        }
    }

    private ClientInterface clientInterface = new ClientInterfaceImpl();

    private ClientService serviceImpl = new ClientService() {
        @Override
        public void setState(String component, String data, long revision) throws Exception {
            boolean status = state.update(component, data, revision);
            if (status) {
                for (StateChangedListener listener : stateListeners) {
                    listener.stateChanged(state.getComponent(component));
                }
            } else {
                logger.warn("Ignoring update: {}",
                        new State.Component(component, revision, data));
            }            
        }

        @Override
        public void notifyNetwork(String networkName, String masterUrl) throws Exception {
            logger.info("NotifyNetwork(networkName={}, masterUrl={})", 
                    networkName, masterUrl);
            if (networkListener != null) {
                networkListener.notifyNetwork(networkName, masterUrl);
            }            
        }

        @Override
        public synchronized void masterTakeover(String masterUrl, String networkName, 
                int masterId) throws Exception {
            logger.info("MasterTakeover({}, {}, {})",
                    new Object[]{masterUrl, networkName, masterId});
            if (masterId <= Client.this.masterId) {
                logger.warn("{}:{} tried to take over, but current master is " +
                		"{}:{}. Ignoring", new Object[]{masterUrl, masterId,
                                state.getDataOf(".masterUrl"),
                                Client.this.masterId}); 
                return;
            }
            abortMasterElection();
            Client.this.masterUrl = masterUrl;
            Client.this.masterId = masterId;
            connectionState = ConnectionState.STABLE;
        }

        @Override
        public void masterDown(int masterId) throws Exception {
            if (masterId < Client.this.masterId) {
                logger.info("Master {} is down, but current master is {}. Ignoring.",
                        masterId, Client.this.masterId);
                return;
            }
            logger.warn("Master down.");
            connectionState = ConnectionState.UNSTABLE;
            tryBecomeMaster(masterId);
        }
    };

    public Client(State state, ConnectionManager connections,
            String myUrl, Broadcaster broadcaster) {
        this.state = state;
        this.connections = connections;
        this.myUrl = myUrl;
        this.broadcaster = broadcaster;
    }

    public void start() {
    }

    public void interrupt() {
        connectionState = ConnectionState.DISCONNECTED;
    }

    void performWork() {
    }
    
    public String getUrl() {
        return myUrl;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }
    
    public void setMasterController(MasterController masterController) {
        this.masterController = masterController;
    }
    
    private synchronized void reset() {
        state.clear();
        masterId = 0;
    }
    
    public void joinNetwork(String masterUrl) {
        logger.info("joinNetwork({})", masterUrl);
        connectionState = ConnectionState.UNSTABLE;
        MasterService master = connections.getMaster(masterUrl);
        reset();
        try {
            master.joinNetworkRequest(myUrl);
        } catch (Exception e) {
            logger.error("Unable to connect to master.", e);
        }          
    }

    public ClientInterface getInterface() {
        return clientInterface;
    }

    public State.Component getState(String name) {
        return state.getComponent(name);
    }

    State testGetState() {
        return state;
    }

    public void setNetworkListener(NetworkNotificationListener listener) {
        this.networkListener = listener;
    }

    public ClientService getService() {
        return serviceImpl;
    }
    
    private List<String> getPaxosUrlsNoMaster() {
        List<String> paxosUrls = new ArrayList<String>();
        for (String participant : state.getList(".participants")) {
            String masterPaxos = state.getDataOf(".masterUrl")
                    .replace("MasterService", "PaxosService");
            String paxos = participant.replace("ClientService", "PaxosService");
            if (!paxos.equals(masterPaxos)) {
                paxosUrls.add(participant.replace("ClientService", "PaxosService"));
            }
        }
        logger.info("Paxos urls: {}", paxosUrls);
        return paxosUrls;
    }
    
    private void tryBecomeMaster(int failedMasterId) {
        List<String> paxosUrls = getPaxosUrlsNoMaster();
        MasterProposer proposer = new MasterProposer(getUrl(), paxosUrls,
                connections);
        if (masterController == null) {
            logger.warn("Could not become master: No master controller.");
            return;
        }
        Runnable sleeperTask = new Runnable() {
            @Override public synchronized void run() {
                try {
                    wait(MASTER_TAKEOVER_TIMEOUT);
                } catch (InterruptedException e) {
                }
            }
        };
        synchronized (this) {
            if (failedMasterId < masterId) {
                logger.info("Master election aborted. Master already chosen.");
                return;
            }
            currentMasterProposal = proposer.startProposalTask(masterId + 1,
                    sleeperTask);
        }
        Integer result = null;
        try {
            result = currentMasterProposal.get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        } catch (CancellationException e) {
        }
        if (!currentMasterProposal.isCancelled() && result != null) {
            masterController.enableMaster(state, result);
        }
    }
    
    private synchronized void abortMasterElection() {
        if (currentMasterProposal != null && !currentMasterProposal.isDone()) {
            boolean status = currentMasterProposal.cancel(true);
            logger.info("Abort status: {}", status);
        }
    }
    
    private int getMasterIdEstimate() {
        return masterId;
    }
    
    public void startMasterElection() {
        List<String> participants = state.getList(".participants");
        final int masterId = getMasterIdEstimate();
        broadcaster.broadcast(participants, new ServiceOperation() {
            @Override public void run(String url) {
                ClientService client = connections.getClient(url);
                try {
                    client.masterDown(masterId);
                } catch (Exception e) {
                    logger.info("{}.masterDown() did not respond (ignored): " +
                    		url, e);
                }
            }
        });
    }
}
