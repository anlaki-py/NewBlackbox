package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

/**
 * Keeps Android's per-app locale service from receiving virtual identities that
 * do not exist in the host package manager.
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    private static final String SERVICE_NAME = "locale";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(
                BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        replaceVirtualUserId(args);
        return super.invoke(proxy, method, args);
    }

    private void replaceVirtualUserId(Object[] args) {
        if (args == null) {
            return;
        }
        int virtualUserId = BlackBoxCore.getUserId();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer && (int) args[i] == virtualUserId) {
                args[i] = BlackBoxCore.getHostUserId();
                return;
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
