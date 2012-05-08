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
package com.orbekk.same;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.orbekk.protobuf.Rpc;
import com.orbekk.same.Services.ClientState;
import com.orbekk.same.Services.Empty;
import com.orbekk.same.Services.MasterTakeoverResponse;
import com.orbekk.same.State.Component;

public class Master {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final ConnectionManager connections;
    private String myLocation; // Protobuf server location, i.e., myIp:port
    private String networkName;
    private AtomicLong revision = new AtomicLong(1);
    State state;
    private volatile int masterId = 1;
    private final RpcFactory rpcf;
    
    class RemoveParticipantIfFailsCallback<T> implements RpcCallback<T> {
        private final String participantLocation;
        private final Rpc rpc;

        public RemoveParticipantIfFailsCallback(
                String participantLocation, Rpc rpc) {
            this.participantLocation = participantLocation;
            this.rpc = rpc;
        }

        @Override
        public void run(T unused) {
            if (rpc.failed()) {
                removeParticipant(participantLocation);
            }
        }
    }
    
    public static Master create(ConnectionManager connections,
            String myUrl, String networkName,
            String myLocation, RpcFactory rpcf) {
        State state = new State();
        return new Master(state, connections, networkName, myLocation, rpcf);
    }

    Master(State initialState, ConnectionManager connections,
            String networkName, String myLocation, RpcFactory rpcf) {
        this.state = initialState;
        this.connections = connections;
        this.myLocation = myLocation;
        this.networkName = networkName;
        this.rpcf = rpcf;
    }
    
    public String getNetworkName() {
        return networkName;
    }
    
    public String getLocation() {
        return myLocation;
    }

    public Services.MasterState getMasterInfo() {
        return Services.MasterState.newBuilder()
                .setMasterLocation(getLocation())
                .setNetworkName(getNetworkName())
                .setMasterId(masterId)
                .setRevision(revision.get())
                .build();
    }
    
    private Services.Master newMasterImpl = new Services.Master() {
        @Override public void joinNetworkRequest(RpcController controller,
                ClientState request, RpcCallback<Empty> done) {
            sendInitialMasterTakeover(request.getLocation());
            sendFullState(request.getLocation());
            addParticipant(request.getLocation());
            done.run(Empty.getDefaultInstance());
        }

        @Override public void updateStateRequest(RpcController controller,
                Services.Component request,
                RpcCallback<Services.UpdateComponentResponse> done) {
            logger.info("updateStateRequest({})", request);
            boolean success = false;
            if (state.checkRevision(request.getId(), request.getRevision())) {
                success = true;
                long newRevision = revision.incrementAndGet();
                state.forceUpdate(request.getId(), request.getData(), newRevision);
                sendStateToClients(state.getComponent(request.getId()));
            }
            done.run(Services.UpdateComponentResponse.newBuilder()
                    .setSuccess(success).build());
        }
    };
    
    private void sendStateToClients(State.Component component) {
        for (String clientLocation : state.getList(
                com.orbekk.same.State.PARTICIPANTS)) {
            sendComponent(clientLocation, component);
        }
    }
    
    private void sendComponent(String clientLocation, Component component) {
        Services.Client client = connections.getClient0(clientLocation);
        if (client == null) {
            removeParticipant(clientLocation);
        }

        Services.Component componentProto = ServicesPbConversion.componentToPb(component);
        Rpc rpc = rpcf.create();
        RpcCallback<Empty> done =
                new RemoveParticipantIfFailsCallback<Empty>(clientLocation,
                        rpc);
        client.setState(rpc, componentProto, done);
    }
    
    private void sendComponents(String clientLocation,
            List<Component> components) {
        Services.Client client = connections.getClient0(clientLocation);
        if (client == null) {
            removeParticipant(clientLocation);
        }

        for (Component component : components) {
            Services.Component componentProto = ServicesPbConversion.componentToPb(component);
            Rpc rpc = rpcf.create();
            RpcCallback<Empty> done =
                    new RemoveParticipantIfFailsCallback<Empty>(clientLocation,
                            rpc);
            client.setState(rpc, componentProto, done);
        }
    }
    
    private void sendFullState(String clientLocation) {
        List<Component> components = state.getComponents();
        sendComponents(clientLocation, components);
    }
    
    private void sendInitialMasterTakeover(String clientLocation) {
        Services.Client client = connections.getClient0(clientLocation);
        Rpc rpc = rpcf.create();
        RpcCallback<MasterTakeoverResponse> done =
                new RemoveParticipantIfFailsCallback<MasterTakeoverResponse>(
                        clientLocation, rpc);
        client.masterTakeover(rpc, getMasterInfo(), done);
    }
    
    void performWork() {
    }

    public void start() {
    }

    public void interrupt() {
    }

    public Services.Master getNewService() {
        return newMasterImpl;
    }
    
    private synchronized void addParticipant(String location) {
        List<String> participants = state.getList(State.PARTICIPANTS);
        if (!participants.contains(location)) {
            participants.add(location);
            state.updateFromObject(State.PARTICIPANTS, participants,
                    state.getRevision(State.PARTICIPANTS) + 1);
            sendStateToClients(state.getComponent(State.PARTICIPANTS));
        }
        
    }

    private synchronized void removeParticipant(String url) {
        List<String> participants0 = state.getList(State.PARTICIPANTS);
        if (participants0.contains(url)) {
            logger.info("removeParticipant({})", url);
            participants0.remove(url);
            state.updateFromObject(State.PARTICIPANTS, participants0, 
                    state.getRevision(State.PARTICIPANTS) + 1);
            sendStateToClients(state.getComponent(State.PARTICIPANTS));
        }
    }
    
    /** This master should take over from an earlier master. */
    public void resumeFrom(State lastKnownState, final int masterId) {
        state = lastKnownState;
        this.masterId = masterId;
        
        for (final String location : state.getList(State.PARTICIPANTS)) {
            Services.Client client = connections.getClient0(location);
            final Rpc rpc = rpcf.create();
            RpcCallback<MasterTakeoverResponse> done = new RemoveParticipantIfFailsCallback<Services.MasterTakeoverResponse>(location, rpc);
            if (client == null) {
                removeParticipant(location);
                continue;
            }
            client.masterTakeover(rpc, getMasterInfo(), done);
        }
    }
}
