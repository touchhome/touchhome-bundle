package org.touchhome.bundle.arduino;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.touchhome.bundle.api.BundleEntryPoint;

@Log4j2
@Component
@RequiredArgsConstructor
public class ArduinoBundleEntryPoint implements BundleEntryPoint {

    private final ArduinoConsolePlugin arduinoConsolePlugin;

    public void init() {
        arduinoConsolePlugin.init();
    }

    @Override
    public int order() {
        return 700;
    }

}
