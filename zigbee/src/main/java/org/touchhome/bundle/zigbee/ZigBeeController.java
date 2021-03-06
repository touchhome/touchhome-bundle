package org.touchhome.bundle.zigbee;

import com.zsmartsystems.zigbee.zcl.clusters.ZclIasZoneCluster;
import com.zsmartsystems.zigbee.zcl.clusters.ZclOnOffCluster;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.bind.annotation.*;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.model.OptionModel;
import org.touchhome.bundle.zigbee.converter.impl.ZigBeeConverterEndpoint;
import org.touchhome.bundle.zigbee.model.ZigBeeDeviceEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Log4j2
@RestController
@RequestMapping("/rest/zigbee")
@RequiredArgsConstructor
public class ZigBeeController {
    private final EntityContext entityContext;
    private final ZigBeeBundleEntryPoint zigbeeBundleContext;

    @GetMapping("option/zcl/{clusterId}")
    public Collection<OptionModel> filterByClusterId(@PathVariable("clusterId") int clusterId,
                                                @RequestParam(value = "includeClusterName", required = false) boolean includeClusterName) {
        return filterByClusterIdAndEndpointCount(clusterId, null, includeClusterName);
    }

    @GetMapping("option/clusterName/{clusterName}")
    public Collection<OptionModel> filterByClusterName(@PathVariable("clusterName") String clusterName,
                                            @RequestParam(value = "includeClusterName", required = false) boolean includeClusterName) {
        List<OptionModel> list = new ArrayList<>();
        for (ZigBeeDevice zigBeeDevice : zigbeeBundleContext.getCoordinatorHandler().getZigBeeDevices().values()) {
            ZigBeeConverterEndpoint zigBeeConverterEndpoint = zigBeeDevice.getZigBeeConverterEndpoints().keySet()
                    .stream().filter(f -> f.getClusterName().equals(clusterName)).findAny().orElse(null);
            // add zigBeeDevice
            if (zigBeeConverterEndpoint != null) {
                String key = zigBeeDevice.getNodeIeeeAddress() + (includeClusterName ? "/" + zigBeeConverterEndpoint.getClusterName() : "");
                list.add(OptionModel.of(key, zigBeeConverterEndpoint.getClusterDescription() + " - " +
                        entityContext.getEntity(ZigBeeDeviceEntity.PREFIX + zigBeeDevice.getNodeIeeeAddress()).getTitle()));
            }
        }
        return list;
    }

    private Collection<OptionModel> filterByClusterIdAndEndpointCount(Integer clusterId, Integer endpointCount, boolean includeClusterName) {
        List<OptionModel> list = new ArrayList<>();
        for (ZigBeeDevice zigBeeDevice : zigbeeBundleContext.getCoordinatorHandler().getZigBeeDevices().values()) {
            List<ZigBeeConverterEndpoint> endpoints = getZigBeeConverterEndpointsByClusterId(zigBeeDevice, clusterId);

            if (!endpoints.isEmpty()) {
                if (endpointCount == null || endpointCount == endpoints.size()) {
                    String key = zigBeeDevice.getNodeIeeeAddress() + (includeClusterName ? "/" + endpoints.iterator().next().getClusterName() : "");
                    list.add(OptionModel.of(key, entityContext.getEntity(ZigBeeDeviceEntity.PREFIX + zigBeeDevice.getNodeIeeeAddress()).getTitle()));
                }
            }
        }
        return list;
    }

    @GetMapping("option/alarm")
    public Collection<OptionModel> getAlarmSensors() {
        return filterByClusterId(ZclIasZoneCluster.CLUSTER_ID, true);
    }

    @GetMapping("option/buttons")
    public Collection<OptionModel> getButtons() {
        Collection<OptionModel> options = filterByClusterIdAndEndpointCount(ZclOnOffCluster.CLUSTER_ID, 1, false);
        options.addAll(filterByModelIdentifier("lumi.remote"));
        return options;
    }

    @GetMapping("option/doubleButtons")
    public Collection<OptionModel> getDoubleButtons() {
        return filterByClusterIdAndEndpointCount(ZclOnOffCluster.CLUSTER_ID, 2, false);
    }

    @GetMapping("option/model/{modelIdentifier}")
    public Collection<OptionModel> filterByModelIdentifier(@PathVariable("modelIdentifier") String modelIdentifier) {
        List<OptionModel> list = new ArrayList<>();
        for (ZigBeeDevice zigBeeDevice : zigbeeBundleContext.getCoordinatorHandler().getZigBeeDevices().values()) {
            String deviceMI = zigBeeDevice.getZigBeeNodeDescription().getModelIdentifier();
            if (deviceMI != null && deviceMI.startsWith(modelIdentifier)) {
                list.add(OptionModel.of(zigBeeDevice.getZigBeeNodeDescription().getIeeeAddress(),
                        entityContext.getEntity(ZigBeeDeviceEntity.PREFIX + zigBeeDevice.getNodeIeeeAddress()).getTitle()));
            }
        }

        return list;
    }

    private List<ZigBeeConverterEndpoint> getZigBeeConverterEndpointsByClusterId(ZigBeeDevice zigBeeDevice, Integer clusterId) {
        List<ZigBeeConverterEndpoint> endpoints = new ArrayList<>();
        for (ZigBeeConverterEndpoint zigBeeConverterEndpoint : zigBeeDevice.getZigBeeConverterEndpoints().keySet()) {
            if (containsAny(zigBeeConverterEndpoint.getZigBeeConverter().clientClusters(), clusterId)) {
                endpoints.add(zigBeeConverterEndpoint);
            }
        }
        return endpoints;
    }

    public static boolean containsAny(int[] array, Integer value) {
        for (int i : array) {
            if (i == value) {
                return true;
            }
        }
        return false;
    }
}
