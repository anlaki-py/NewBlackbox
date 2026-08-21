package top.niunaijun.blackbox.root;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class VirtualRootGrant {
    public enum DenialReason {
        NONE,
        FEATURE_DISABLED,
        PACKAGE_NOT_INSTALLED,
        PACKAGE_NOT_SUPPORTED,
        SIGNATURE_MISSING,
        SIGNATURE_MISMATCH
    }

    private final VirtualRootCaller caller;
    private final Set<VirtualRootCapability> capabilities;
    private final DenialReason denialReason;

    static VirtualRootGrant approved(VirtualRootCaller caller,
                                     Set<VirtualRootCapability> capabilities) {
        return new VirtualRootGrant(caller, capabilities, DenialReason.NONE);
    }

    static VirtualRootGrant denied(VirtualRootCaller caller, DenialReason reason) {
        return new VirtualRootGrant(caller, Collections.emptySet(), reason);
    }

    private VirtualRootGrant(VirtualRootCaller caller,
                             Set<VirtualRootCapability> capabilities,
                             DenialReason denialReason) {
        this.caller = caller;
        this.capabilities = capabilities.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.denialReason = denialReason;
    }

    public boolean isApproved() {
        return denialReason == DenialReason.NONE;
    }

    public boolean hasCapability(VirtualRootCapability capability) {
        return capabilities.contains(capability);
    }

    public VirtualRootCaller getCaller() {
        return caller;
    }

    public Set<VirtualRootCapability> getCapabilities() {
        return capabilities;
    }

    public DenialReason getDenialReason() {
        return denialReason;
    }
}
