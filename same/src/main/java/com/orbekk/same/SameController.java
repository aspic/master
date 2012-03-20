package com.orbekk.same;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbekk.net.BroadcastListener;
import com.orbekk.net.BroadcasterFactory;
import com.orbekk.net.BroadcasterInterface;
import com.orbekk.net.DefaultBroadcasterFactory;
import com.orbekk.paxos.PaxosService;
import com.orbekk.paxos.PaxosServiceImpl;
import com.orbekk.same.config.Configuration;
import com.orbekk.same.discovery.DirectoryService;
import com.orbekk.same.discovery.DiscoveryService;
import com.orbekk.same.http.ServerContainer;
import com.orbekk.same.http.StateServlet;
import com.orbekk.same.http.JettyServerBuilder;
import com.orbekk.same.http.TjwsServerBuilder;

public class SameController {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private ServerContainer server;
    private MasterServiceProxy masterService;
    private Master master;
    private Client client;
    private PaxosServiceImpl paxos;
    private DiscoveryService discoveryService;
    private BroadcasterFactory broadcasterFactory;
    private Configuration configuration;
    private ConnectionManager connections;
    private Broadcaster serviceBroadcaster;

    /**
     * Timeout for remote operations in milliseconds.
     */
    private static final int timeout = 10000;

    private MasterController masterController = new MasterController() {
        @Override
        public void enableMaster(State lastKnownState) {
            String masterUrl = configuration.get("baseUrl") +
                    "MasterService.json";
            master = Master.create(connections, serviceBroadcaster,
                    masterUrl, configuration.get("networkName"));
            if (lastKnownState != null) {
                master.resumeFrom(lastKnownState);
            }
            master.start();
            masterService.setService(master.getService());
        }

        @Override
        public void disableMaster() {
            masterService.setService(null);
            if (master != null) {
                master.interrupt();
            }
        }
    };
    
    public static SameController create(BroadcasterFactory broadcasterFactory,
            Configuration configuration) {
        int port = configuration.getInt("port");
        ConnectionManagerImpl connections = new ConnectionManagerImpl(
                timeout, timeout);
        State clientState = new State(".InvalidClientNetwork");
        Broadcaster broadcaster = BroadcasterImpl.getDefaultBroadcastRunner();

        String baseUrl = String.format("http://%s:%s/",
                configuration.get("localIp"), configuration.getInt("port"));

        String masterUrl = baseUrl + "MasterService.json";
        String clientUrl = baseUrl + "ClientService.json";

        MasterServiceProxy master = new MasterServiceProxy();
//        Master master = Master.create(connections, broadcaster,
//                masterUrl, configuration.get("networkName"));

        Client client = new Client(clientState, connections,
                clientUrl, BroadcasterImpl.getDefaultBroadcastRunner());
        PaxosServiceImpl paxos = new PaxosServiceImpl("");

        DiscoveryService discoveryService = null;
        if ("true".equals(configuration.get("enableDiscovery"))) {
            BroadcastListener broadcastListener = new BroadcastListener(
                    configuration.getInt("discoveryPort"));
            discoveryService = new DiscoveryService(client, broadcastListener);
        }

        StateServlet stateServlet = new StateServlet(client.getInterface(),
                new VariableFactory(client.getInterface()));

        ServerContainer server = new JettyServerBuilder(port)
            .withServlet(stateServlet, "/_/state")
            .withService(client.getService(), ClientService.class)
            .withService(master, MasterService.class)
            .withService(paxos, PaxosService.class)
            .build();

        SameController controller = new SameController(
                configuration, connections, server, master, client,
                paxos, discoveryService, broadcaster, broadcasterFactory);
        return controller;
    }

    public static SameController create(Configuration configuration) {
        return create(new DefaultBroadcasterFactory(), configuration);
    }

    public SameController(
            Configuration configuration,
            ConnectionManager connections,
            ServerContainer server,
            MasterServiceProxy master,
            Client client,
            PaxosServiceImpl paxos,
            DiscoveryService discoveryService,
            Broadcaster serviceBroadcaster,
            BroadcasterFactory broadcasterFactory) {
        this.configuration = configuration;
        this.connections = connections;
        this.server = server;
        this.masterService = master;
        this.client = client;
        this.paxos = paxos;
        this.discoveryService = discoveryService;
        this.serviceBroadcaster = serviceBroadcaster;
        this.broadcasterFactory = broadcasterFactory;
    }

    public void start() throws Exception {
        server.start();
        client.setMasterController(masterController);
        client.start();
        if (discoveryService != null) {
            discoveryService.start();
        }
    }

    public void stop() {
        try {
            client.interrupt();
            if (master != null) {
                master.interrupt();
            }
            server.stop();
            if (discoveryService != null) {
                discoveryService.interrupt();
            }
        } catch (Exception e) {
            logger.error("Failed to stop webserver", e);
        }
    }

    public void join() {
        try {
            server.join();
            client.interrupt();
            if (master != null) {
                master.interrupt();
            }
            if (discoveryService != null) {
                discoveryService.join();
            }
        } catch (InterruptedException e) {
            try {
                server.stop();
            } catch (Exception e1) {
                logger.error("Failed to stop server", e);
            }
        }
    }

    public void createNetwork(String networkName) {
        masterController.disableMaster();
        masterController.enableMaster(null);
        String masterUrl = configuration.get("baseUrl") +
                "MasterService.json";
        joinNetwork(masterUrl);
    }
    
    public void searchNetworks() {
        BroadcasterInterface broadcaster = broadcasterFactory.create();
        String message = "Discover " + client.getUrl();
        broadcaster.sendBroadcast(configuration.getInt("discoveryPort"),
                message.getBytes());
    }

    public void joinNetwork(String url) {
        client.joinNetwork(url);
    }

    public Client getClient() {
        return client;
    }

    public Master getMaster() {
        return master;
    }
    
    public DirectoryService getDirectory() {
        String directoryUrl = configuration.get("directoryUrl");
        DirectoryService directory = null;
        if (directoryUrl != null) {
            directory = connections.getDirectory(directoryUrl);
        }
        return directory;
    }

    public VariableFactory createVariableFactory() {
        return new VariableFactory(client.getInterface());
    }
}
