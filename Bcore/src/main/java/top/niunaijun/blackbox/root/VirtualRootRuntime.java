package top.niunaijun.blackbox.root;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.FileUtils;

/** Hosts libsu's regular RootService inside an approved BlackBox guest process. */
public final class VirtualRootRuntime {
    private static final String TAG = "VirtualRoot";
    private static final int MAX_COMMAND_BYTES = 16 * 1024;
    private static final int MAX_INSTALL_SESSIONS = 8;
    private static final int MAX_APKS_PER_SESSION = 64;
    private static final Pattern ROOT_SERVER_COMMAND = Pattern.compile(
            "RootServerMain\\s+'([^']+)'\\s+(\\d+)\\s+(start|stop|daemon)");

    private static VirtualRootRuntime sInstance;

    private final Context guestContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File commandFile;
    private final File responseFile;
    private final AtomicInteger nextInstallSession = new AtomicInteger(1);
    private final Map<Integer, List<File>> installSessions = new ConcurrentHashMap<>();
    private FileObserver observer;
    private Object rootService;

    private VirtualRootRuntime(Context guestContext, File commandFile, File responseFile) {
        this.guestContext = guestContext;
        this.commandFile = commandFile;
        this.responseFile = responseFile;
    }

    public static synchronized void initialize(Context guestContext) {
        String packageName = guestContext.getPackageName();
        int userId = BlackBoxCore.getUserId();
        VirtualRootGrant grant = BlackBoxCore.get().getVirtualRootGrant(packageName, userId);
        if (!grant.isApproved()) {
            Log.i(TAG, "Denied package=" + packageName + " reason=" + grant.getDenialReason());
            return;
        }
        if (sInstance != null) {
            return;
        }

        try {
            IOCore.get().enableVirtualRootRedirects(userId);
            File runtimeDir = BEnvironment.getVirtualRootRuntimeDir();
            File binDir = new File(guestContext.getFilesDir(), "virtual-root-bin");
            FileUtils.mkdirs(binDir);
            File commandFile = new File(runtimeDir, "root-service-command");
            File responseFile = new File(runtimeDir, "shell-response");
            File suFile = new File(binDir, "su");
            writeShellBridge(guestContext, suFile, commandFile, responseFile, userId);
            String currentPath = System.getenv("PATH");
            Os.setenv("PATH", binDir.getAbsolutePath() + ":" +
                    (currentPath == null ? "/system/bin" : currentPath), true);

            VirtualRootRuntime runtime = new VirtualRootRuntime(
                    guestContext, commandFile, responseFile);
            runtime.startCommandObserver();
            sInstance = runtime;
            Log.i(TAG, "Virtual root ready package=" + packageName + " user=" + userId);
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to initialize virtual root", throwable);
        }
    }

    private static void writeShellBridge(Context context, File suFile, File commandFile,
                                         File responseFile, int userId) throws IOException {
        String requestPath = shellQuote(commandFile.getAbsolutePath());
        String responsePath = shellQuote(responseFile.getAbsolutePath());
        String binPath = shellQuote(new File(context.getFilesDir(), "bin").getAbsolutePath());
        String userRoot = shellQuote(BEnvironment.getUserDir(userId).getAbsolutePath());
        String deRoot = shellQuote(BEnvironment.getDeUserDir(userId).getAbsolutePath());
        String mediaRoot = shellQuote(BEnvironment.getExternalUserDir(userId).getAbsolutePath());
        String appRoot = shellQuote(BEnvironment.getAppRootDir().getAbsolutePath());
        String script = "#!/system/bin/sh\n"
                + "status=0\n"
                + "PATH=" + binPath + ":$PATH; export PATH\n"
                + "while IFS= read -r line; do\n"
                + "  case \"$line\" in\n"
                + "    'echo SHELL_TEST') echo SHELL_TEST; status=0 ;;\n"
                + "    'id') echo 'uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0'; status=0 ;;\n"
                + "    cd\\ *|export\\ *|set\\ *|alias\\ *|nsenter\\ *) status=0 ;;\n"
                + "    *RootServerMain*) printf '%s\\n' \"$line\" > " + requestPath + "; status=0 ;;\n"
                + "    pm\\ *) rm -f " + responsePath + "; printf 'SHELL\\t%s\\n' \"$line\" > " + requestPath + "; "
                + "i=0; while [ ! -f " + responsePath + " ] && [ $i -lt 300 ]; do sleep 0.1; i=$((i+1)); done; "
                + "if [ -f " + responsePath + " ]; then status=$(sed -n '1p' " + responsePath + "); sed -n '2,$p' " + responsePath + "; rm -f " + responsePath + "; else status=124; fi ;;\n"
                + "    tar\\ *|zstd\\ *|tree\\ *) mapped=$line; "
                + "mapped=${mapped//\\/data\\/user\\/0/" + userRoot + "}; "
                + "mapped=${mapped//\\/data\\/user_de\\/0/" + deRoot + "}; "
                + "mapped=${mapped//\\/data\\/media\\/0/" + mediaRoot + "}; "
                + "mapped=${mapped//\\/storage\\/emulated\\/0/" + mediaRoot + "}; "
                + "mapped=${mapped//\\/data\\/app/" + appRoot + "}; "
                + "case \"$mapped\" in *'/proc/'*|*'/sys/'*) status=126 ;; *) eval \"$mapped\"; status=$? ;; esac ;;\n"
                + "    ls\\ -Zd\\ *) echo 'u:object_r:app_data_file:s0'; status=0 ;;\n"
                + "    chown\\ *|chcon\\ *|appops\\ *|ime\\ *) status=0 ;;\n"
                + "    settings\\ get\\ system\\ screen_off_timeout) echo 30000; status=0 ;;\n"
                + "    settings\\ get\\ *) echo null; status=0 ;;\n"
                + "    settings\\ put\\ *) status=0 ;;\n"
                + "    mount\\ *) status=0 ;;\n"
                + "    readlink\\ /proc/*/ns/mnt) echo 'virtual-root'; status=0 ;;\n"
                + "    su\\ -v) echo 'BlackBox virtual root'; status=0 ;;\n"
                + "    echo\\ *) eval \"$line\"; status=$? ;;\n"
                + "    __RET=*) uuid=$(printf '%s\\n' \"$line\" | sed -n 's/.*echo \\([0-9a-f-]\\{36\\}\\).*/\\1/p'); "
                + "if [ -n \"$uuid\" ]; then echo \"$uuid\"; echo \"$uuid\" >&2; echo \"$status\"; fi ;;\n"
                + "    exit*) exit 0 ;;\n"
                + "    '') status=0 ;;\n"
                + "    *) echo 'virtual root: unsupported command' >&2; status=126 ;;\n"
                + "  esac\n"
                + "done\n";
        FileUtils.mkdirs(suFile.getParentFile());
        try (FileOutputStream output = new FileOutputStream(suFile, false)) {
            output.write(script.getBytes(StandardCharsets.UTF_8));
        }
        if (!suFile.setExecutable(true, true)) {
            throw new IOException("Cannot make virtual su executable");
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void startCommandObserver() throws IOException {
        FileUtils.mkdirs(commandFile.getParentFile());
        if (!commandFile.exists() && !commandFile.createNewFile()) {
            throw new IOException("Cannot create RootService command file");
        }
        observer = new FileObserver(commandFile.getParentFile().getAbsolutePath(),
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (path != null && path.equals(commandFile.getName())) {
                    mainHandler.post(VirtualRootRuntime.this::consumeRootServiceCommand);
                }
            }
        };
        observer.startWatching();
    }

    private void consumeRootServiceCommand() {
        try {
            byte[] commandBytes = FileUtils.toByteArray(commandFile);
            if (!commandFile.delete() && commandFile.exists()) {
                Log.w(TAG, "Could not clear RootService command file");
            }
            if (commandBytes.length > MAX_COMMAND_BYTES) {
                Log.w(TAG, "Rejected oversized virtual-root command");
                writeShellResponse(126, "virtual root: command is too large");
                return;
            }
            String command = new String(commandBytes, StandardCharsets.UTF_8);
            if (command.startsWith("SHELL\t")) {
                handleShellCommand(command.substring(6).trim());
                return;
            }
            Matcher matcher = ROOT_SERVER_COMMAND.matcher(command);
            if (!matcher.find()) {
                Log.w(TAG, "Rejected malformed RootService command");
                return;
            }
            String flattenedComponent = matcher.group(1);
            int clientUid = Integer.parseInt(matcher.group(2));
            String action = matcher.group(3);
            if (!flattenedComponent.startsWith(guestContext.getPackageName() + "/")) {
                Log.w(TAG, "Rejected RootService component outside approved package");
                return;
            }
            if (!"start".equals(action)) {
                Log.w(TAG, "Rejected unsupported RootService action=" + action);
                return;
            }
            startRootService(flattenedComponent, clientUid);
        } catch (Throwable throwable) {
            Log.e(TAG, "RootService command failed", throwable);
        }
    }

    private void handleShellCommand(String command) {
        int status = 1;
        String output = "virtual root: unsupported package command";
        try {
            List<String> tokens = tokenize(command);
            if (tokens.size() < 2 || !"pm".equals(tokens.get(0))) {
                writeShellResponse(status, output);
                return;
            }
            String operation = tokens.get(1);
            if ("install-create".equals(operation)) {
                if (installSessions.size() >= MAX_INSTALL_SESSIONS) {
                    writeShellResponse(1, "virtual root: too many install sessions");
                    return;
                }
                int session = nextInstallSession.getAndIncrement();
                installSessions.put(session, new ArrayList<>());
                status = 0;
                output = Integer.toString(session);
            } else if ("install-write".equals(operation) && tokens.size() >= 5) {
                int session = Integer.parseInt(tokens.get(2));
                List<File> files = installSessions.get(session);
                File apk = resolveAllowedFile(tokens.get(tokens.size() - 1));
                if (files != null && files.size() < MAX_APKS_PER_SESSION && apk.isFile()) {
                    files.add(apk);
                    status = 0;
                    output = "Success";
                }
            } else if ("install-commit".equals(operation) && tokens.size() >= 3) {
                int session = Integer.parseInt(tokens.get(2));
                List<File> files = installSessions.remove(session);
                InstallResult result = installApkSet(files);
                status = result != null && result.success ? 0 : 1;
                output = status == 0 ? "Success" : "Failure [" +
                        (result == null ? "install failed" : result.msg) + "]";
            } else if ("install".equals(operation)) {
                File apk = resolveAllowedFile(tokens.get(tokens.size() - 1));
                InstallResult result = installApkSet(Collections.singletonList(apk));
                status = result != null && result.success ? 0 : 1;
                output = status == 0 ? "Success" : "Failure [" +
                        (result == null ? "install failed" : result.msg) + "]";
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "Package command failed", throwable);
            output = "virtual root: package command failed";
        }
        writeShellResponse(status, output);
    }

    private InstallResult installApkSet(List<File> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return null;
        }
        File base = null;
        ArrayList<File> splits = new ArrayList<>();
        for (File file : files) {
            File allowed = resolveAllowedFile(file.getAbsolutePath());
            if ("base.apk".equals(allowed.getName()) || base == null) {
                if (base != null) {
                    splits.add(base);
                }
                base = allowed;
            } else {
                splits.add(allowed);
            }
        }
        InstallResult result = BlackBoxCore.get().installPackageAsUser(
                base, BlackBoxCore.getUserId());
        if (result == null || !result.success || splits.isEmpty()) {
            return result;
        }
        ArrayList<String> paths = new ArrayList<>();
        for (File split : splits) {
            paths.add(split.getAbsolutePath());
        }
        if (!BlackBoxCore.getBPackageManager().attachSplitApks(result.packageName, paths)) {
            result.installError(result.packageName, "split APK validation failed");
        }
        return result;
    }

    private File resolveAllowedFile(String path) throws IOException {
        File file = new File(IOCore.get().redirectPath(path)).getCanonicalFile();
        if (isWithin(file, BEnvironment.getVirtualRoot())
                || isWithin(file, BEnvironment.getExternalVirtualRoot())) {
            return file;
        }
        throw new IOException("Path is outside BlackBox virtual storage");
    }

    private static boolean isWithin(File file, File root) throws IOException {
        String filePath = file.getCanonicalPath();
        String rootPath = root.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private void writeShellResponse(int status, String output) {
        String value = status + "\n" + (output == null ? "" : output) + "\n";
        try (FileOutputStream stream = new FileOutputStream(responseFile, false)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            Log.e(TAG, "Unable to return shell response", exception);
        }
    }

    static List<String> tokenize(String command) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char value = command.charAt(i);
            if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else {
                    token.append(value);
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (Character.isWhitespace(value)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(value);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    private void startRootService(String flattenedComponent, int clientUid) throws Exception {
        if (rootService != null) {
            Log.i(TAG, "RootService already connected package=" + guestContext.getPackageName());
            return;
        }
        loadGuestNativeLibrary();
        ComponentName component = ComponentName.unflattenFromString(flattenedComponent);
        if (component == null) {
            throw new IllegalArgumentException("Invalid RootService component");
        }
        ClassLoader classLoader = guestContext.getClassLoader();
        Class<?> serviceClass = classLoader.loadClass(component.getClassName());
        Object service = serviceClass.getDeclaredConstructor().newInstance();
        Method attachBaseContext = findMethod(serviceClass, "attachBaseContext", Context.class);
        attachBaseContext.setAccessible(true);
        attachBaseContext.invoke(service, new RootServiceContext(guestContext, clientUid));
        rootService = service;
        Log.i(TAG, "RootService connected component=" + component.flattenToShortString());
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void loadGuestNativeLibrary() {
        try {
            ApplicationInfo info = guestContext.getApplicationInfo();
            File library = new File(info.nativeLibraryDir, "libnativelib.so");
            if (library.isFile()) {
                System.load(library.getAbsolutePath());
            }
        } catch (UnsatisfiedLinkError alreadyLoadedOrUnavailable) {
            Log.w(TAG, "DataBackup native helper was not loaded", alreadyLoadedOrUnavailable);
        }
    }

    private static final class RootServiceContext extends ContextWrapper
            implements Callable<Object[]> {
        private final int clientUid;

        RootServiceContext(Context base, int clientUid) {
            super(base);
            this.clientUid = clientUid;
        }

        @Override
        public Object[] call() {
            return new Object[]{clientUid, false};
        }
    }
}
