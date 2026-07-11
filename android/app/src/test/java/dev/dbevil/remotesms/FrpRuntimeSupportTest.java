package dev.dbevil.remotesms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrpRuntimeSupportTest {
    @Test
    public void tomlKeepsHostnameAndRetriesInitialLogin() {
        String text = FrpRuntimeSupport.toml(
                "frp.example.test",
                7000,
                65439,
                "token-value",
                "192.0.2.53",
                "remote_sms_test",
                8787
        );

        assertTrue(text.contains("serverAddr = \"frp.example.test\""));
        assertTrue(text.contains("loginFailExit = false"));
        assertTrue(text.contains("log.to = \"console\""));
        assertTrue(text.contains("log.level = \"info\""));
        assertTrue(text.contains("log.disablePrintColor = true"));
        assertFalse(text.contains("resolvedServerIp"));
    }

    @Test
    public void transientErrorsAreClassifiedWithoutHidingAuthErrors() {
        assertTrue(FrpRuntimeSupport.isTransientNetworkError("connect: network is unreachable"));
        assertTrue(FrpRuntimeSupport.isTransientNetworkError("lookup failed: no such host"));
        assertFalse(FrpRuntimeSupport.isTransientNetworkError(
                "token in login doesn't match token from configuration"
        ));
    }

    @Test
    public void retryDelayIsBounded() {
        assertEquals(5_000L, FrpRuntimeSupport.retryDelayMs(0));
        assertEquals(10_000L, FrpRuntimeSupport.retryDelayMs(1));
        assertEquals(60_000L, FrpRuntimeSupport.retryDelayMs(20));
    }

    @Test
    public void networkRecoveryOrDnsChangeRequiresRestart() {
        assertTrue(FrpRuntimeSupport.shouldRestartForNetwork("", "192.0.2.53", true));
        assertTrue(FrpRuntimeSupport.shouldRestartForNetwork("192.0.2.53", "198.51.100.53", true));
        assertFalse(FrpRuntimeSupport.shouldRestartForNetwork("192.0.2.53", "", false));
        assertFalse(FrpRuntimeSupport.shouldRestartForNetwork("192.0.2.53", "192.0.2.53", true));
    }

    @Test
    public void tomlEscapesSecretsAndNames() {
        String text = FrpRuntimeSupport.toml(
                "host",
                7000,
                65439,
                "a\"b\\c\n",
                "192.0.2.53",
                "proxy\"name",
                8787
        );

        assertTrue(text.contains("auth.token = \"a\\\"b\\\\c\\n\""));
        assertTrue(text.contains("name = \"proxy\\\"name\""));
    }

    @Test
    public void repeatedTransientLogIsRateLimited() {
        assertTrue(FrpRuntimeSupport.shouldLogTransient(
                "network is unreachable", "", 0, 1_000
        ));
        assertFalse(FrpRuntimeSupport.shouldLogTransient(
                "network is unreachable", "network is unreachable", 1_000, 2_000
        ));
        assertTrue(FrpRuntimeSupport.shouldLogTransient(
                "network is unreachable", "network is unreachable", 1_000, 301_000
        ));
        assertTrue(FrpRuntimeSupport.shouldLogTransient(
                "no such host", "network is unreachable", 1_000, 2_000
        ));
    }
}
