package com.orbekk.same;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class MasterTest {
    private State state = new State("TestNetwork");
    private TestConnectionManager connections = new TestConnectionManager();
    private Master master;
    
    @Before
    public void setUp() {
        String masterLocation = "master:1000";
        state.update(".masterUrl", "http://master/MasterService.json", 1);
        state.update(".masterLocation", masterLocation, 1);
        master = new Master(state, connections,
                "http://master/MasterService.json", masterLocation);
        connections.masterMap0.put("master:1000", master.getNewService());
    }

    @Test
    public void clientJoin() throws Exception {
        Client client = new Client(
                new State("ClientNetwork"), connections,
                "http://client/ClientService.json", "clientLocation");
        connections.clientMap0.put("clientLocation", client.getNewService());
        client.joinNetwork(master.getMasterInfo());
        master.performWork();
        System.out.println(state);
        System.out.println(master.state);
        assertTrue(state.getList(State.PARTICIPANTS)
                .contains("clientLocation"));
        assertEquals(state, client.testGetState());
    }

    @Test
    @Ignore
    public void updateStateRequest() throws Exception {
        // TODO: Implement this test.
        throw new IllegalStateException();
    }

    @Test
    @Ignore
    public void masterRemovesParticipant() throws Exception {
        // TODO: Implement this test.
        throw new IllegalStateException();
    }

}
