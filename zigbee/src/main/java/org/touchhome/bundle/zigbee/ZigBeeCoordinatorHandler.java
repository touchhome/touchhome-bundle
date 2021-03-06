package org.touchhome.bundle.zigbee;

import com.zsmartsystems.zigbee.*;
import com.zsmartsystems.zigbee.app.discovery.ZigBeeDiscoveryExtension;
import com.zsmartsystems.zigbee.app.iasclient.ZigBeeIasCieExtension;
import com.zsmartsystems.zigbee.app.otaserver.ZigBeeOtaUpgradeExtension;
import com.zsmartsystems.zigbee.security.MmoHash;
import com.zsmartsystems.zigbee.security.ZigBeeKey;
import com.zsmartsystems.zigbee.serialization.DefaultDeserializer;
import com.zsmartsystems.zigbee.serialization.DefaultSerializer;
import com.zsmartsystems.zigbee.transport.*;
import com.zsmartsystems.zigbee.zcl.clusters.ZclBasicCluster;
import com.zsmartsystems.zigbee.zcl.clusters.ZclOtaUpgradeCluster;
import com.zsmartsystems.zigbee.zdo.field.NeighborTable;
import com.zsmartsystems.zigbee.zdo.field.NodeDescriptor;
import com.zsmartsystems.zigbee.zdo.field.RoutingTable;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.model.Status;
import org.touchhome.bundle.api.setting.SettingPluginStatus;
import org.touchhome.bundle.zigbee.converter.impl.ZigBeeChannelConverterFactory;
import org.touchhome.bundle.zigbee.internal.ZigBeeDataStore;
import org.touchhome.bundle.zigbee.setting.ZigBeeNetworkIdSetting;
import org.touchhome.bundle.zigbee.setting.ZigBeeStatusSetting;
import org.touchhome.bundle.zigbee.setting.advanced.*;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The {@link ZigBeeCoordinatorHandler} is responsible for handling commands,
 * which are sent to one of the zigbeeRequireEndpoints.
 * <p>
 * This is the base coordinator handler. It handles the majority of the interaction
 * with the ZigBeeNetworkManager.
 * <p>
 * The interface coordinators are responsible for opening a ZigBeeTransport implementation
 * and passing this to the {@link ZigBeeCoordinatorHandler}.
 */
@Log4j2
public abstract class ZigBeeCoordinatorHandler
        implements ZigBeeNetworkStateListener, ZigBeeNetworkNodeListener {

    /**
     * Default ZigBeeAlliance09 link key
     */
    private final static ZigBeeKey KEY_ZIGBEE_ALLIANCE_O9 = new ZigBeeKey(new int[]{0x5A, 0x69, 0x67, 0x42, 0x65,
            0x65, 0x41, 0x6C, 0x6C, 0x69, 0x61, 0x6E, 0x63, 0x65, 0x30, 0x39});
    protected final EntityContext entityContext;
    private final Set<ZigBeeNetworkNodeListener> nodeListeners = new CopyOnWriteArraySet<>();
    private final Set<ZigBeeAnnounceListener> announceListeners = new CopyOnWriteArraySet<>();

    @Getter
    private final Map<String, ZigBeeDevice> zigBeeDevices = new ConcurrentHashMap<>();
    /**
     * The factory to create the converters for the different zigbeeRequireEndpoints.
     */
    private final ZigBeeChannelConverterFactory channelFactory;

    private ZigBeeTransportTransmit zigBeeTransport;
    private ZigBeeKey linkKey;
    private ZigBeeKey networkKey;
    private Integer panId;
    private Integer channelId;
    private ExtendedPanId extendedPanId;
    @Getter
    @Setter
    private IeeeAddress nodeIeeeAddress = null;
    private ZigBeeNetworkManager networkManager;
    private Class<?> serializerClass = DefaultSerializer.class;
    private Class<?> deserializerClass = DefaultDeserializer.class;
    private ZigBeeDataStore networkDataStore;
    private TransportConfig transportConfig;

    private ZigBeeCoordinatorDescription zigBeeCoordinatorDescription = new ZigBeeCoordinatorDescription();

    /**
     * Set to true on startup if we want to reinitialize the network
     */
    private boolean initializeNetwork = false;

    public ZigBeeCoordinatorHandler(ZigBeeChannelConverterFactory channelFactory, EntityContext entityContext) {
        this.channelFactory = channelFactory;
        this.entityContext = entityContext;

        entityContext.setting().listenValue(ZigBeePortBaudSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeNetworkIdSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeLinkKeySetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeExtendedPanIdSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeNetworkKeySetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeePanIdSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeChannelIdSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeePowerModeSetting.class, "zb-init", this::reInitialize);
        entityContext.setting().listenValue(ZigBeeResetNetworkButtonSetting.class, "zb-init", () -> {
            entityContext.setting().setValue(ZigBeeNetworkIdSetting.class, null);
            this.reInitialize();
        });
        // TODO: do we need this/????

        entityContext.setting().listenValue(ZigBeeTrustCenterModeSetting.class, "zb-init", linkMode -> {
            TransportConfig transportConfig = new TransportConfig();
            transportConfig.addOption(TransportConfigOption.TRUST_CENTRE_JOIN_MODE, linkMode);
            zigBeeTransport.updateTransportConfig(transportConfig);
        });

        entityContext.setting().listenValue(ZigBeeTxPowerSetting.class, "zb-init", txPower -> {
            TransportConfig transportConfig = new TransportConfig();
            transportConfig.addOption(TransportConfigOption.RADIO_TX_POWER, txPower);
            zigBeeTransport.updateTransportConfig(transportConfig);
        });

        entityContext.setting().listenValue(ZigBeeInstallCodeSetting.class, "zb-init", this::addInstallCode);
    }

    void initialize() {
        log.info("Initializing ZigBee network.");

        panId = entityContext.setting().getValue(ZigBeePanIdSetting.class);
        channelId = entityContext.setting().getValue(ZigBeeChannelIdSetting.class);
        extendedPanId = new ExtendedPanId(entityContext.setting().getValue(ZigBeeExtendedPanIdSetting.class));
        String linkKeyString = entityContext.setting().getValue(ZigBeeLinkKeySetting.class);
        String networkKeyString = entityContext.setting().getValue(ZigBeeNetworkKeySetting.class);

        if (extendedPanId == null || extendedPanId.equals(new ExtendedPanId()) || panId == 0) {
            initializeNetwork = true;
            log.debug("ExtendedPanId or PanId not set: initializeNetwork=true");
        }

        // Process the network key
        try {
            log.debug("Network Key String {}", networkKeyString);
            networkKey = new ZigBeeKey(networkKeyString);
        } catch (IllegalArgumentException e) {
            networkKey = new ZigBeeKey();
            log.debug("Network Key String has invalid format. Revert to default key.");
        }

        // If no key exists, generateBooleanLink a random key and save it back to the configuration
        if (!networkKey.isValid()) {
            networkKey = ZigBeeKey.createRandom();
            entityContext.setting().setValueSilence(ZigBeeNetworkKeySetting.class, networkKey.toString());
            log.debug("Network key initialised {}", networkKey);
        }

        log.debug("Network key final array {}", networkKey);

        // Process the link key
        try {
            log.debug("Link Key String {}", linkKeyString);
            linkKey = new ZigBeeKey(linkKeyString);
        } catch (IllegalArgumentException e) {
            linkKey = KEY_ZIGBEE_ALLIANCE_O9;
            entityContext.setting().setValueSilence(ZigBeeLinkKeySetting.class, linkKey.toString());
            log.debug("Link Key String has invalid format. Revert to default key.");
        }

        log.debug("Initialising network");

        if (panId == 0) {
            panId = (int) Math.floor((Math.random() * 65534));
            log.debug("Created random ZigBee PAN ID [{}].", String.format("%04X", panId));
            entityContext.setting().setValueSilence(ZigBeePanIdSetting.class, panId);
        }

        if (extendedPanId != null && !extendedPanId.isValid()) {
            int[] pan = new int[8];
            for (int cnt = 0; cnt < 8; cnt++) {
                pan[cnt] = (int) Math.floor((Math.random() * 255));
            }
            extendedPanId = new ExtendedPanId(pan);
            log.debug("Created random ZigBee extended PAN ID [{}].", extendedPanId);

            entityContext.setting().setValueSilence(ZigBeeExtendedPanIdSetting.class, extendedPanId.toString());
        }

        log.debug("Link key final array {}", linkKey);
        initializeNetwork = false;

        initializeDongle();
    }

    protected abstract void initializeDongle();

    /**
     * A dongle specific initialisation method. This can be overridden by coordinator handlers and is called just before
     * the {@link ZigBeeTransportTransmit#startup(boolean)} is called.
     */
    protected void initializeDongleSpecific() {
        // Can be overridden to provide dongle specific configuration
    }

    public void dispose() {
        log.warn("Dispose zigbee node");

        if (networkManager != null) {
            for (ZigBeeNetworkNodeListener listener : nodeListeners) {
                networkManager.removeNetworkNodeListener(listener);
            }
            for (ZigBeeAnnounceListener listener : announceListeners) {
                networkManager.removeAnnounceListener(listener);
            }

            // Shut down the ZigBee library
            networkManager.shutdown();
        }

        if (networkDataStore != null) {
            networkDataStore.delete();
        }

        log.debug("ZigBee network closed.");
    }

    /**
     * Common initialisation point for all ZigBee coordinators.
     * Called by bridge implementations after they have initialised their interfaces.
     */
    protected void startZigBee(ZigBeeTransportTransmit zigbeeTransport, TransportConfig transportConfig) {
        updateStatus(Status.UNKNOWN, "");

        this.zigBeeTransport = zigbeeTransport;
        this.transportConfig = transportConfig;

        // Start the network. This is a scheduled task to ensure we give the coordinator some time to initialise itself!
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                initialiseZigBee();
            }
        }, 1000);
    }

    /**
     * Initialise the ZigBee network
     * <p>
     * synchronized to avoid executing this if a reconnect is still in progress
     */
    private synchronized void initialiseZigBee() {
        log.debug("Initialising ZigBee coordinator");

        String networkId = Optional.ofNullable(entityContext.setting().getValue(ZigBeeNetworkIdSetting.class))
                .map(path -> path.getFileName().toString()).orElse(null);
        if (StringUtils.isEmpty(networkId)) {
            networkId = UUID.randomUUID().toString();
            entityContext.setting().setValueSilence(ZigBeeNetworkIdSetting.class, Paths.get(networkId));
        }

        log.warn("ZigBee use networkID: <{}>", networkId);

        networkManager = new ZigBeeNetworkManager(zigBeeTransport);
        networkDataStore = new ZigBeeDataStore(networkId, entityContext);

        // Configure the network manager
        networkManager.setNetworkDataStore(networkDataStore);
        networkManager.setSerializer(serializerClass, deserializerClass);
        networkManager.addNetworkStateListener(this);
        networkManager.addNetworkNodeListener(this);

        // Initialise the network
        ZigBeeStatus initializeResponse = networkManager.initialize();

        if (zigBeeTransport instanceof ZigBeeTransportFirmwareUpdate) {
            ZigBeeTransportFirmwareUpdate firmwareTransport = (ZigBeeTransportFirmwareUpdate) zigBeeTransport;
            zigBeeCoordinatorDescription.firmwareVersion = firmwareTransport.getFirmwareVersion();
            zigBeeCoordinatorDescription.macAddress = networkManager.getLocalIeeeAddress().toString();
        }

        switch (initializeResponse) {
            case SUCCESS:
                break;
            case BAD_RESPONSE:
                updateStatus(Status.OFFLINE, "zigbee.OFFLINE_BAD_RESPONSE");
                return;
            case COMMUNICATION_ERROR:
                updateStatus(Status.OFFLINE, "zigbee.OFFLINE_COMMS_FAIL");
                return;
            default:
                updateStatus(Status.OFFLINE, "zigbee.OFFLINE_INITIALIZE_FAIL");
                return;
        }

        int meshUpdateTime = entityContext.setting().getValue(ZigBeeMeshUpdatePeriodSetting.class);

        // Add the extensions to the network
        ZigBeeDiscoveryExtension discoveryExtension = new ZigBeeDiscoveryExtension();
        discoveryExtension.setUpdatePeriod(meshUpdateTime);
        networkManager.addExtension(discoveryExtension);

        networkManager.addExtension(new ZigBeeIasCieExtension());
        networkManager.addExtension(new ZigBeeOtaUpgradeExtension());

        // Add any listeners that were registered before the manager was registered
        for (ZigBeeNetworkNodeListener listener : nodeListeners) {
            networkManager.addNetworkNodeListener(listener);
        }

        synchronized (announceListeners) {
            for (ZigBeeAnnounceListener listener : announceListeners) {
                networkManager.addAnnounceListener(listener);
            }
        }

        // Add all the clusters that we are supporting.
        // If we don't do this, the framework will reject any packets for clusters we have not stated support for.
        channelFactory.getImplementedClientClusters()
                .forEach(clusterId -> networkManager.addSupportedClientCluster(clusterId));
        channelFactory.getImplementedServerClusters()
                .forEach(clusterId -> networkManager.addSupportedServerCluster(clusterId));

        networkManager.addSupportedClientCluster(ZclBasicCluster.CLUSTER_ID);
        networkManager.addSupportedClientCluster(ZclOtaUpgradeCluster.CLUSTER_ID);
        networkManager.addSupportedServerCluster(ZclBasicCluster.CLUSTER_ID);

        // Show the initial network configuration for debugging
        ZigBeeChannel currentChannel = networkManager.getZigBeeChannel();
        int currentPanId = networkManager.getZigBeePanId();
        ExtendedPanId currentExtendedPanId = networkManager.getZigBeeExtendedPanId();

        log.debug("ZigBee Initialise: Previous device configuration was: channel={}, PanID={}, EPanId={}",
                currentChannel, currentPanId, currentExtendedPanId);

        if (initializeNetwork) {
            log.debug("Link key initialise {}", linkKey);
            log.debug("Network key initialise {}", networkKey);
            networkManager.setZigBeeLinkKey(linkKey);
            networkManager.setZigBeeNetworkKey(networkKey);
            networkManager.setZigBeeChannel(ZigBeeChannel.create(channelId == 0 ? 11 : channelId));
            networkManager.setZigBeePanId(panId);
            networkManager.setZigBeeExtendedPanId(extendedPanId);
        }

        TrustCentreJoinMode linkMode = entityContext.setting().getValue(ZigBeeTrustCenterModeSetting.class);
        if (linkMode != null) {
            log.debug("Config zigbee trustcentremode: {}", linkMode);
            transportConfig.addOption(TransportConfigOption.TRUST_CENTRE_JOIN_MODE, linkMode);
        }

        zigBeeTransport.updateTransportConfig(transportConfig);

        // Call startup. The setting of the bring to ONLINE will be done via the state listener.
        if (networkManager.startup(initializeNetwork) != ZigBeeStatus.SUCCESS) {
            updateStatus(Status.OFFLINE, "OFFLINE_STARTUP_FAIL");
            return;
        }

        // Get the final network configuration
        zigBeeCoordinatorDescription.channel = networkManager.getZigBeeChannel();
        zigBeeCoordinatorDescription.panId = networkManager.getZigBeePanId();
        zigBeeCoordinatorDescription.extendedPanId = networkManager.getZigBeeExtendedPanId();
        log.debug("ZigBee initialise done. <{}>", zigBeeCoordinatorDescription.toString());

        initializeDongleSpecific();
    }

    protected void updateStatus(Status deviceStatus, String statusMessage) {
        log.info("Coordinator status: <{}>", deviceStatus);
        entityContext.setting().setValue(ZigBeeStatusSetting.class, SettingPluginStatus.of(deviceStatus, statusMessage));

        for (ZigBeeDevice zigBeeDevice : zigBeeDevices.values()) {
            zigBeeDevice.tryInitializeDevice(entityContext.setting().getValue(ZigBeeStatusSetting.class).getStatus());
        }
    }

    private void reInitialize() {
        dispose();
        initialize();
    }

    /**
     * Process the adding of an install code
     *
     * @param installCode the string representation of the install code
     *                    //     * @param transportConfig the {@link TransportConfig} to populate with the configuration
     */
    private void addInstallCode(String installCode) {
        if (installCode == null || installCode.isEmpty()) {
            return;
        }

        // Split the install code and the address
        String[] codeParts = installCode.split(":");
        if (codeParts.length != 2) {
            log.warn("{}: Incorrectly formatted install code configuration {}", nodeIeeeAddress, installCode);
            return;
        }

        MmoHash mmoHash = new MmoHash(codeParts[1].replace("-", ""));
        ZigBeeKey key = new ZigBeeKey(mmoHash.getHash());
        key.setAddress(new IeeeAddress(codeParts[0]));

        networkManager.setZigBeeInstallKey(key);
    }

    /**
     * Adds a {@link ZigBeeNetworkNodeListener} to receive updates on node status
     *
     * @param listener the {@link ZigBeeNetworkNodeListener} to add
     */
    public void addNetworkNodeListener(ZigBeeNetworkNodeListener listener) {
        // Save the listeners until the network is initialised
        synchronized (nodeListeners) {
            nodeListeners.add(listener);
        }

        if (networkManager != null) {
            networkManager.addNetworkNodeListener(listener);
        }
    }

    /**
     * Removes a {@link ZigBeeNetworkNodeListener} to receive updates on node status
     *
     * @param listener the {@link ZigBeeNetworkNodeListener} to remove
     */
    public void removeNetworkNodeListener(ZigBeeNetworkNodeListener listener) {
        nodeListeners.remove(listener);

        if (networkManager != null) {
            networkManager.removeNetworkNodeListener(listener);
        }
    }

    /**
     * Adds a {@link ZigBeeAnnounceListener} to receive node announce messages
     *
     * @param listener the {@link ZigBeeAnnounceListener} to add
     */
    public void addAnnounceListener(ZigBeeAnnounceListener listener) {
        // Save the listeners until the network is initialised
        announceListeners.add(listener);

        if (networkManager != null) {
            networkManager.addAnnounceListener(listener);
        }
    }

    /**
     * Removes a {@link ZigBeeAnnounceListener}
     *
     * @param listener the {@link ZigBeeAnnounceListener} to remove
     */
    public void removeAnnounceListener(ZigBeeAnnounceListener listener) {
        synchronized (announceListeners) {
            announceListeners.remove(listener);
        }

        if (networkManager == null) {
            return;
        }
        networkManager.removeAnnounceListener(listener);
    }

    @Override
    public void nodeAdded(ZigBeeNode node) {
        nodeUpdated(node);
    }

    @Override
    public void nodeUpdated(ZigBeeNode node) {
        // We're only interested in the coordinator here.
        if (node.getNetworkAddress() != 0) {
            return;
        }

        zigBeeCoordinatorDescription.neighbors = node.getNeighbors();
        zigBeeCoordinatorDescription.routes = node.getRoutes();
        zigBeeCoordinatorDescription.associatedDevices = node.getAssociatedDevices();
        zigBeeCoordinatorDescription.lastUpdateTime = node.getLastUpdateTime();
        zigBeeCoordinatorDescription.networkAddress = node.getNetworkAddress();
        zigBeeCoordinatorDescription.logicalType = node.getLogicalType();

        if (zigBeeTransport instanceof ZigBeeTransportFirmwareUpdate) {
            ZigBeeTransportFirmwareUpdate firmwareTransport = (ZigBeeTransportFirmwareUpdate) zigBeeTransport;
            zigBeeCoordinatorDescription.firmwareVersion = firmwareTransport.getFirmwareVersion();
        }
    }

    public ZigBeeEndpoint getEndpoint(IeeeAddress address, int endpointId) {
        if (networkManager == null) {
            return null;
        }
        ZigBeeNode node = networkManager.getNode(address);
        if (node == null) {
            return null;
        }
        return node.getEndpoint(endpointId);
    }

    public Collection<ZigBeeEndpoint> getNodeEndpoints(IeeeAddress nodeIeeeAddress) {
        if (networkManager == null) {
            return Collections.emptySet();
        }
        ZigBeeNode node = networkManager.getNode(nodeIeeeAddress);
        if (node == null) {
            return Collections.emptySet();
        }

        return node.getEndpoints();
    }

    public IeeeAddress getLocalIeeeAddress() {
        return networkManager.getLocalIeeeAddress();
    }

    /**
     * Gets the local endpoint associated with the specified {@link ZigBeeProfileType}
     *
     * @param profile the {@link ZigBeeProfileType} of the endpoint
     * @return the endpoint ID
     */
    public int getLocalEndpointId(ZigBeeProfileType profile) {
        return 1;
    }

    @Override
    public void networkStateUpdated(final ZigBeeNetworkState state) {
        log.debug("{}: networkStateUpdated called with state={}", nodeIeeeAddress, state);
        switch (state) {
            case ONLINE:
                updateStatus(Status.ONLINE, "");
                break;
            case OFFLINE:
                /*Bridge bridge = getThing();

                // do not try to reconnect if there is a firmware update in progress
                if (bridge.getStatus() == Status.OFFLINE
                        && bridge.getStatusInfo().getStatusDetail() == ThingStatusDetail.FIRMWARE_UPDATING) {
                    break;
                }*/

                // - Do not set the status to OFFLINE when the bridge is in one of these statuses. According to the
                // documentation https://www.eclipse.org/smarthome/documentation/concepts/things.html#status-transitions
                // the thing must not change from these statuses to OFFLINE
                // - Do not try to reconnect if the bridge is being removed.
              /*  if (Arrays.asList(Status.UNINITIALIZED, Status.REMOVING, Status.REMOVED)
                        .contains(bridge.getStatus())) {
                    break;
                }*/

                updateStatus(Status.OFFLINE, "COMMUNICATION_ERROR");

                break;
        }
    }

    /**
     * Gets a node given the long address
     *
     * @param nodeIeeeAddress the {@link IeeeAddress} of the device
     * @return the {@link ZigBeeNode} or null if the node is not found
     */
    public ZigBeeNode getNode(IeeeAddress nodeIeeeAddress) {
        if (networkManager == null) {
            return null;
        }
        return networkManager.getNode(nodeIeeeAddress);
    }

    /**
     * Gets the nodes in this network manager
     *
     * @return the set of {@link ZigBeeNode}s
     */
    public Set<ZigBeeNode> getNodes() {
        if (networkManager == null) {
            return Collections.emptySet();
        }
        return networkManager.getNodes();
    }

    /**
     * Removes a node from the network manager. This does not cause the network manager to tell the node to leave the
     * network, but will only remove the node from the network manager lists. Thus, if the node is still alive, it may
     * be able to rejoin the network.
     * <p>
     *
     * @param nodeIeeeAddress the {@link IeeeAddress} of the node to remove
     */
    public void removeNode(IeeeAddress nodeIeeeAddress) {
        ZigBeeNode node = networkManager.getNode(nodeIeeeAddress);
        if (node == null) {
            return;
        }
        networkManager.removeNode(node);

        ZigBeeDevice zigBeeDevice = zigBeeDevices.get(nodeIeeeAddress.toString());
        zigBeeDevice.dispose();
        zigBeeDevices.remove(nodeIeeeAddress.toString());
    }

    /**
     * Permit joining only for the specified node
     *
     * @param address  the 16 bit network address of the node to enable joining
     * @param duration the duration of the join
     */
    public boolean permitJoin(IeeeAddress address, int duration) {
        log.debug("{}: ZigBee join command", address);
        ZigBeeNode node = networkManager.getNode(address);
        if (node == null) {
            log.debug("{}: ZigBee join command - node not found", address);
            return false;
        }

        log.debug("{}: ZigBee join command to {}", address, node.getNetworkAddress());

        networkManager.permitJoin(new ZigBeeEndpointAddress(node.getNetworkAddress()), duration);
        return true;
    }

    /**
     * Sends a ZDO Leave Request to a device requesting that an end device leave the network.
     * <p>
     * This method will send the ZDO message to the device itself requesting it leave the network
     *
     * @param address      the network address to leave
     * @param forceRemoval true to remove the node from the network, even if the leave request fails
     * @return true if the command is sent
     */
    public boolean leave(IeeeAddress address, boolean forceRemoval) {
        // First we want to make sure that join is disabled
        networkManager.permitJoin(0);

        log.debug("{}: ZigBee leave command", address);
        ZigBeeNode node = networkManager.getNode(address);
        if (node == null) {
            log.debug("{}: ZigBee leave command - node not found", address);
            return false;
        }

        log.debug("{}: ZigBee leave command to {}", address, node.getNetworkAddress());

        networkManager.leave(node.getNetworkAddress(), node.getIeeeAddress(), forceRemoval);
        return true;
    }

    /**
     * Search for a node - will perform a discovery on the defined {@link IeeeAddress}
     *
     * @param nodeIeeeAddress {@link IeeeAddress} of the node to discover
     */
    public void rediscoverNode(IeeeAddress nodeIeeeAddress) {
        if (networkManager != null) {
            networkManager.rediscoverNode(nodeIeeeAddress);
        }
    }

    /**
     * Serialize the network state
     *
     * @param nodeAddress the {@link IeeeAddress} of the node to serialize
     */
    public void serializeNetwork(IeeeAddress nodeAddress) {
        if (networkManager != null) {
            networkManager.serializeNetworkDataStore(nodeAddress);
        }
    }

    public void scanStart(int duration) {
        if (!entityContext.setting().getValue(ZigBeeStatusSetting.class).isOnline()) {
            entityContext.ui().sendWarningMessage("DEVICE.OFFLINE", "Unable to ");
            log.debug("ZigBee coordinator is offline - aborted scan for");
        } else {
            networkManager.permitJoin(duration);
        }
    }

    public void addZigBeeDevice(ZigBeeDevice zigBeeDevice) {
        this.zigBeeDevices.put(zigBeeDevice.getNodeIeeeAddress().toString(), zigBeeDevice);
    }

    @ToString
    public static class ZigBeeCoordinatorDescription {
        public Set<NeighborTable> neighbors;
        public Collection<RoutingTable> routes;
        public Set<Integer> associatedDevices;
        public Date lastUpdateTime;
        public Integer networkAddress;
        public NodeDescriptor.LogicalType logicalType;
        ZigBeeChannel channel;
        int panId;
        ExtendedPanId extendedPanId;
        String macAddress;
        String firmwareVersion;
    }
}
