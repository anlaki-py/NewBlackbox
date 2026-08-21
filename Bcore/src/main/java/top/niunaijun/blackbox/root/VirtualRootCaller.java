package top.niunaijun.blackbox.root;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class VirtualRootCaller {
    private final String packageName;
    private final int userId;
    private final Set<String> certificateDigests;

    public VirtualRootCaller(String packageName, int userId, Set<String> certificateDigests) {
        this.packageName = packageName;
        this.userId = userId;
        this.certificateDigests = Collections.unmodifiableSet(
                new LinkedHashSet<>(certificateDigests));
    }

    public String getPackageName() {
        return packageName;
    }

    public int getUserId() {
        return userId;
    }

    public Set<String> getCertificateDigests() {
        return certificateDigests;
    }
}
