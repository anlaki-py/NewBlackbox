package top.niunaijun.blackbox.core.system.pm;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BPackageComponentTest {

    @Test
    public void infoNameReplacesParserName() {
        assertEquals("manifest.Name", BPackage.Component.resolveClassName(
                "parser.Name", "manifest.Name"));
    }

    @Test
    public void emptyInfoNameKeepsParserName() {
        assertEquals("parser.Name", BPackage.Component.resolveClassName("parser.Name", ""));
        assertEquals("parser.Name", BPackage.Component.resolveClassName("parser.Name", null));
    }
}
