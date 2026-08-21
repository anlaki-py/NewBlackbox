package top.niunaijun.blackbox.root;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;

public final class VirtualRootPolicy {
    public static final String DATABACKUP_CERTIFICATE_SHA256 =
            "7FB9EC207705A0FBB9CA61253E596364C72762868A83815239E7CA1923ACB39F";

    private static final Set<String> DATABACKUP_PACKAGES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "com.xayah.databackup",
                    "com.xayah.databackup.foss",
                    "com.xayah.databackup.premium")));

    private static final Set<VirtualRootCapability> DATABACKUP_CAPABILITIES =
            Collections.unmodifiableSet(EnumSet.allOf(VirtualRootCapability.class));

    private VirtualRootPolicy() {
    }

    public static VirtualRootGrant evaluateInstalledPackage(String packageName,
                                                             int userId,
                                                             boolean featureEnabled) {
        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(
                packageName, PackageManager.GET_SIGNATURES, userId);
        if (packageInfo == null) {
            VirtualRootCaller caller = new VirtualRootCaller(
                    packageName, userId, Collections.emptySet());
            return VirtualRootGrant.denied(
                    caller, VirtualRootGrant.DenialReason.PACKAGE_NOT_INSTALLED);
        }
        return evaluate(packageInfo.packageName, userId, featureEnabled,
                certificateDigests(packageInfo.signatures));
    }

    public static VirtualRootGrant evaluate(String packageName,
                                            int userId,
                                            boolean featureEnabled,
                                            Set<String> certificateDigests) {
        Set<String> normalizedDigests = normalizeDigests(certificateDigests);
        VirtualRootCaller caller = new VirtualRootCaller(
                packageName, userId, normalizedDigests);

        if (!featureEnabled) {
            return VirtualRootGrant.denied(
                    caller, VirtualRootGrant.DenialReason.FEATURE_DISABLED);
        }
        if (!DATABACKUP_PACKAGES.contains(packageName)) {
            return VirtualRootGrant.denied(
                    caller, VirtualRootGrant.DenialReason.PACKAGE_NOT_SUPPORTED);
        }
        if (normalizedDigests.isEmpty()) {
            return VirtualRootGrant.denied(
                    caller, VirtualRootGrant.DenialReason.SIGNATURE_MISSING);
        }
        if (normalizedDigests.size() != 1
                || !normalizedDigests.contains(DATABACKUP_CERTIFICATE_SHA256)) {
            return VirtualRootGrant.denied(
                    caller, VirtualRootGrant.DenialReason.SIGNATURE_MISMATCH);
        }
        return VirtualRootGrant.approved(caller, DATABACKUP_CAPABILITIES);
    }

    private static Set<String> certificateDigests(Signature[] signatures) {
        if (signatures == null || signatures.length == 0) {
            return Collections.emptySet();
        }
        Set<String> digests = new LinkedHashSet<>();
        for (Signature signature : signatures) {
            if (signature != null) {
                digests.add(sha256(signature.toByteArray()));
            }
        }
        return digests;
    }

    private static Set<String> normalizeDigests(Set<String> certificateDigests) {
        if (certificateDigests == null || certificateDigests.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String digest : certificateDigests) {
            if (digest != null) {
                String value = digest.replace(":", "").trim().toUpperCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
        }
        return normalized;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format(Locale.ROOT, "%02X", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }
}
