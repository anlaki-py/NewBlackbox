package top.niunaijun.blackbox.core.system.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ComponentResolverTest {

    @Test
    public void componentIdentityRequiresExactPackageAndClass() {
        assertTrue(ComponentResolver.componentMatches(
                "example.app", "example.Provider", "example.app", "example.Provider"));
        assertFalse(ComponentResolver.componentMatches(
                "example.app", "example.Provider", "other.app", "example.Provider"));
        assertFalse(ComponentResolver.componentMatches(
                "example.app", "example.Provider", "example.app", "example.OtherProvider"));
    }
}
