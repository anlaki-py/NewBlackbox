package top.niunaijun.blackbox.root;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class VirtualRootPolicyTest {
    private static final String FOSS_PACKAGE = "com.xayah.databackup.foss";

    @Test
    public void approvedDataBackupReceivesCapabilities() {
        VirtualRootGrant grant = VirtualRootPolicy.evaluate(
                FOSS_PACKAGE,
                0,
                true,
                Collections.singleton(VirtualRootPolicy.DATABACKUP_CERTIFICATE_SHA256));

        assertTrue(grant.isApproved());
        assertTrue(grant.hasCapability(VirtualRootCapability.LIBSU_SHELL));
        assertTrue(grant.hasCapability(VirtualRootCapability.READ_VIRTUAL_APP_DATA));
    }

    @Test
    public void featureIsDisabledByDefault() {
        VirtualRootGrant grant = VirtualRootPolicy.evaluate(
                FOSS_PACKAGE,
                0,
                false,
                Collections.singleton(VirtualRootPolicy.DATABACKUP_CERTIFICATE_SHA256));

        assertFalse(grant.isApproved());
        assertEquals(VirtualRootGrant.DenialReason.FEATURE_DISABLED,
                grant.getDenialReason());
    }

    @Test
    public void matchingPackageWithWrongSignerIsRejected() {
        VirtualRootGrant grant = VirtualRootPolicy.evaluate(
                FOSS_PACKAGE,
                0,
                true,
                Collections.singleton("001122"));

        assertFalse(grant.isApproved());
        assertEquals(VirtualRootGrant.DenialReason.SIGNATURE_MISMATCH,
                grant.getDenialReason());
    }

    @Test
    public void unknownPackageIsRejected() {
        VirtualRootGrant grant = VirtualRootPolicy.evaluate(
                "example.backup",
                0,
                true,
                Collections.singleton(VirtualRootPolicy.DATABACKUP_CERTIFICATE_SHA256));

        assertFalse(grant.isApproved());
        assertEquals(VirtualRootGrant.DenialReason.PACKAGE_NOT_SUPPORTED,
                grant.getDenialReason());
    }
}
