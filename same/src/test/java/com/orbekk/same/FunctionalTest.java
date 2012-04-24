package com.orbekk.same;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.orbekk.paxos.PaxosService;
import com.orbekk.paxos.PaxosServiceImpl;
import com.orbekk.util.DelayedOperation;

/** A functional test that runs with a master and several clients. */
public class FunctionalTest {
    Master master;
    String masterUrl = "http://master/MasterService.json";
    String masterLocation = "master:1";
    Client client1;
    Client client2;
    Client client3;
    VariableFactory vf1;
    VariableFactory vf2;
    VariableFactory vf3;
    List<Client> clients = new ArrayList<Client>();
    TestConnectionManager connections = new TestConnectionManager();
    TestBroadcaster broadcaster = new TestBroadcaster();
    
    @Before public void setUp() {
        master = Master.create(connections,
                broadcaster, masterUrl, "TestMaster", masterLocation);
        connections.masterMap0.put(masterLocation, master.getNewService());
        client1 = newClient("TestClient1", "http://client1/ClientService.json",
                "client1");
        vf1 = new VariableFactory(client1.getInterface());
        client2 = newClient("TestClient2", "http://client2/ClientService.json",
                "client2");
        vf2 = new VariableFactory(client2.getInterface());
        client3 = newClient("TestClient3", "http://client3/ClientService.json",
                "client3");
        vf3 = new VariableFactory(client3.getInterface());
    }
    
    Client newClient(String clientName, String clientUrl, String location) {
        Client client = new Client(new State(clientName), connections,
                clientUrl, location, broadcaster);
        connections.clientMap.put(clientUrl, client.getService());
        connections.clientMap0.put(location, client.getNewService());
        clients.add(client);
        String paxosUrl = clientUrl.replace("ClientService", "PaxosService");
        PaxosServiceImpl paxos = new PaxosServiceImpl(paxosUrl);
        connections.paxosMap.put(paxosUrl, paxos);
        connections.paxosMap0.put(location, paxos.getService());
        return client;
    }
    
    void performWork() {
        for (int i = 0; i < 2; i++) {
            master.performWork();
            for (Client c : clients) {
                c.performWork();
            }
        }
    }
    
    void joinClients() {
       for (Client c : clients) {
           c.joinNetwork(master.getMasterInfo());
       }
       performWork();
    }
    
    List<State> getStates() {
        List<State> states = new ArrayList<State>();
        states.add(master.state);
        for (Client c : clients) {
            states.add(c.state);
        }
        return states;
    }
    
    @Test public void testJoin() {
        joinClients();
        for (State s : getStates()) {
            List<String> participants = s.getList(State.PARTICIPANTS);
            assertThat(participants, hasItem("client1"));
            assertThat(participants, hasItem("client2"));
            assertThat(participants, hasItem("client3"));
        }
        for (Client c : clients) {
            assertThat(c.getConnectionState(), is(ConnectionState.STABLE));
            assertThat(c.getMaster().getMasterUrl(), is(masterUrl));
            assertThat(c.getMaster().getMasterLocation(), is(masterLocation));
        }
    }
    
    @Test public void setState() {
        joinClients();
        Variable<String> x1 = vf1.createString("x");
        Variable<String> x2 = vf2.createString("x");
        x1.set("TestValue1");
        performWork();
        x1.update();
        x2.update();
        assertThat(x1.get(), is("TestValue1"));
        assertThat(x2.get(), is("TestValue1"));
    }
    
    @Test public void clientBecomesMaster() {
        String newMasterUrl = "http://newMaster/MasterService.json";
        String newMasterLocation = "newMaster:1";
        final Master newMaster = Master.create(connections,
                broadcaster, newMasterUrl, "TestMaster", newMasterLocation);
        joinClients();
        MasterController controller = new MasterController() {
            @Override
            public void enableMaster(State lastKnownState, int masterId) {
                newMaster.resumeFrom(lastKnownState, masterId);
            }
            @Override
            public void disableMaster() {
            }
        };
        client1.setMasterController(controller);
        client2.setMasterController(controller);
        client3.setMasterController(controller);
        client1.startMasterElection();
        newMaster.performWork();
        assertThat(client1.getMaster().getMasterUrl(), is(newMasterUrl));
        assertThat(client2.getMaster().getMasterUrl(), is(newMasterUrl));
    }
    
    @Test public void onlyOneNewMaster() {
        String newMasterUrl = "http://newMaster/MasterService.json";
        String newMasterLocation = "newMaster:1";
        final Master newMaster = Master.create(connections,
                broadcaster, newMasterUrl, "TestMaster", newMasterLocation);
        joinClients();
        MasterController controller = new MasterController() {
            boolean firstMaster = true;
            @Override
            public synchronized void enableMaster(State lastKnownState,
                    int masterId) {
                assertThat(firstMaster, is(true));
                newMaster.resumeFrom(lastKnownState, masterId);
                firstMaster = false;
            }
            @Override
            public void disableMaster() {
            }
        };
        client1.setMasterController(controller);
        client2.setMasterController(controller);
        client3.setMasterController(controller);
        client1.startMasterElection();
        newMaster.performWork();
        assertThat(client1.getMaster().getMasterUrl(), is(newMasterUrl));
        assertThat(client2.getMaster().getMasterUrl(), is(newMasterUrl));
    }
    
    @Test public void masterFails() {
        String newMasterUrl = "http://newMaster/MasterService.json";
        String newMasterLocation = "newMaster:2";
        final Master newMaster = Master.create(connections,
                broadcaster, newMasterUrl, "TestMaster", newMasterLocation);
        joinClients();
        MasterController controller = new MasterController() {
            @Override
            public synchronized void enableMaster(State lastKnownState,
                    int masterId) {
                newMaster.resumeFrom(lastKnownState, masterId);
            }
            @Override
            public void disableMaster() {
            }
        };
        client1.setMasterController(controller);
        client2.setMasterController(controller);
        client3.setMasterController(controller);
        Variable<String> x1 = vf1.createString("TestMasterFailure");
        connections.masterMap0.put(masterLocation, null);
        assertThat(x1.set("Woop, woop").getStatus().getStatusCode(),
                is(DelayedOperation.Status.ERROR));
        performWork();
        newMaster.performWork();
        assertThat(client1.getMaster().getMasterUrl(), is(newMasterUrl));
        assertThat(client2.getMaster().getMasterUrl(), is(newMasterUrl));
    }
}
