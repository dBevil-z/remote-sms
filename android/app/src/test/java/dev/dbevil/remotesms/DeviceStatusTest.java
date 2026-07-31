package dev.dbevil.remotesms;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DeviceStatusTest {
    @Test
    public void ssidFallsBackToConfiguredNetworkIdWhenDirectValueIsHidden() {
        Map<Integer, String> configured = new HashMap<>();
        configured.put(57, "\"YNZL_AP\"");

        assertEquals("YNZL_AP", DeviceStatus.pickSsid("<unknown ssid>", 57, configured));
        assertEquals("YNZL_AP", DeviceStatus.pickSsid("", 57, configured));
        assertEquals("Live_AP", DeviceStatus.pickSsid("\"Live_AP\"", 57, configured));
    }
}
