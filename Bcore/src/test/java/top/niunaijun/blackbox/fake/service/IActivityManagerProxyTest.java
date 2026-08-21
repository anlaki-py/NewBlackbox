package top.niunaijun.blackbox.fake.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class IActivityManagerProxyTest {

    @Test
    public void proxyBindServiceInstanceDropsGuestInstanceName() {
        Object[] args = new Object[9];
        args[6] = "0";

        IActivityManagerProxy.sanitizeProxyServiceInstanceName("bindServiceInstance", args);

        assertNull(args[6]);
    }

    @Test
    public void ordinaryProxyBindKeepsArgumentAtSamePosition() {
        Object[] args = new Object[8];
        args[6] = "host.package";

        IActivityManagerProxy.sanitizeProxyServiceInstanceName("bindService", args);

        assertEquals("host.package", args[6]);
    }

    @Test
    public void shortSignatureIsIgnored() {
        Object[] args = new Object[6];

        IActivityManagerProxy.sanitizeProxyServiceInstanceName("bindServiceInstance", args);

        assertEquals(6, args.length);
    }
}
