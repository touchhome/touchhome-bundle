package org.touchhome.bundle.zigbee;

import org.touchhome.bundle.api.DynamicOptionLoader;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.json.Option;
import org.touchhome.bundle.zigbee.requireEndpoint.ZigbeeRequireEndpoints;

import java.util.List;
import java.util.stream.Collectors;

public class SelectModelIdentifierDynamicLoader implements DynamicOptionLoader {

    @Override
    public List<Option> loadOptions(Object parameter, EntityContext entityContext) {
        return ZigbeeRequireEndpoints.get().getZigbeeRequireEndpoints().stream().map(c ->
                Option.of(c.getModelId(), c.getName()).setImageRef(c.getImage())).collect(Collectors.toList());
    }
}