package top.niunaijun.blackbox.root;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class VirtualRootRuntimeTest {
    @Test
    public void tokenizesQuotedPackageInstallCommand() {
        assertEquals(Arrays.asList(
                        "pm", "install", "--user", "0", "-r", "-t",
                        "/data/user/0/com.xayah.databackup.foss/cache/base.apk"),
                VirtualRootRuntime.tokenize(
                        "pm install --user '0' -r -t "
                                + "'/data/user/0/com.xayah.databackup.foss/cache/base.apk'"));
    }

    @Test
    public void tokenizesSplitInstallWriteCommand() {
        assertEquals(Arrays.asList(
                        "pm", "install-write", "12", "config.arm64_v8a.apk",
                        "/data/user/0/com.xayah.databackup.foss/cache/config.arm64_v8a.apk"),
                VirtualRootRuntime.tokenize(
                        "pm install-write '12' 'config.arm64_v8a.apk' "
                                + "'/data/user/0/com.xayah.databackup.foss/cache/config.arm64_v8a.apk'"));
    }
}
