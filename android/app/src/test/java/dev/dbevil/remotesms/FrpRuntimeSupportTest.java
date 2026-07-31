package dev.dbevil.remotesms;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

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
    public void publicHealthFailureRotatesDnsWithoutCachingPublicIp() {
        String[] fallbacks = {"114.114.114.114", "223.5.5.5", "8.8.8.8", "1.1.1.1"};

        assertEquals("114.114.114.114", FrpRuntimeSupport.nextDnsAfterFailure(
                "192.168.1.1", "192.168.1.1", fallbacks
        ));
        assertEquals("223.5.5.5", FrpRuntimeSupport.nextDnsAfterFailure(
                "114.114.114.114", "192.168.1.1", fallbacks
        ));
        assertEquals("192.168.1.1", FrpRuntimeSupport.nextDnsAfterFailure(
                "1.1.1.1", "192.168.1.1", fallbacks
        ));
    }

    @Test
    public void onlyRecoverablePublicHealthFailuresRefreshFrp() {
        assertTrue(FrpRuntimeSupport.isRecoverablePublicCheckError("SocketTimeoutException: timeout"));
        assertTrue(FrpRuntimeSupport.isRecoverablePublicCheckError("UnknownHostException: frp.example.test"));
        assertTrue(FrpRuntimeSupport.isRecoverablePublicCheckError("ConnectException: failed to connect"));
        assertFalse(FrpRuntimeSupport.isRecoverablePublicCheckError("HTTP 401"));
        assertFalse(FrpRuntimeSupport.isRecoverablePublicCheckError("HTTP 404"));
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

    @Test
    public void processStateCheckWorksBeforeApi26() {
        assertTrue(FrpRuntimeSupport.isProcessAlive(new StubProcess(true)));
        assertFalse(FrpRuntimeSupport.isProcessAlive(new StubProcess(false)));
        assertFalse(FrpRuntimeSupport.isProcessAlive(null));
    }

    private static final class StubProcess extends Process {
        private final boolean running;

        StubProcess(boolean running) {
            this.running = running;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            if (running) throw new IllegalThreadStateException("still running");
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
