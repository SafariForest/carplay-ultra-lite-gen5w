package ioniq6.cpclimate;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/**
 * CarPlay video, stage 1. When the phone wants to play a video it sends requestUI("videoplayback:"); stock DiPO
 * treats every requestUI as "show the car's own screen" and goes to the launcher, which ends CarPlay's screen.
 * This wraps DiPO's CarPlayService listeners (CarPlayService.cbCarPlayServices, ICarPlayServiceListener) in a
 * proxy that keeps that one request away from DiPO and answers it as LIVI does (stack/video.rs): the car borrows
 * the screen for "VideoPlayback". Everything else goes to DiPO unchanged.
 *
 * There is no player yet (stage 3), so the screen is handed back after RETURN_MS: the stage is for seeing what
 * the phone does next (its playback commands), never for leaving the screen black.
 */
final class VideoUi {
    static final String URL = "videoplayback:";
    static final String BORROW_ID = "VideoPlayback";
    static final long RETURN_MS = 20 * 1000L;
    static final String PKG = "com.daudio.app.dipo.telechips.";

    static volatile VsChannel.Log out;
    static volatile boolean enabled;
    static volatile Object carPlay;
    private static volatile long borrowedAt;

    /** wraps the session's listeners (again after DiPO adds new ones); safe to call on every session start */
    static synchronized void install(Object service, ClassLoader cl) {
        if (!enabled || service == null) return;
        carPlay = service;
        try {
            Class<?> iface = cl.loadClass(PKG + "ICarPlayServiceListener");
            Field f = service.getClass().getDeclaredField("cbCarPlayServices");
            f.setAccessible(true);
            Object now = f.get(service);
            if (now instanceof ListenerList) return;   // this session's service is already done
            if (!(now instanceof ArrayList)) {
                log("video: no listener list (" + now + ")");
                return;
            }
            @SuppressWarnings("unchecked")
            ListenerList list = new ListenerList(iface, cl, (ArrayList<Object>) now);
            f.set(service, list);
            log("video: " + list.size() + " DiPO listener(s) wrapped (requestUI " + URL + " kept from DiPO)");
        } catch (Throwable t) {
            log("video: listeners not wrapped: " + t);
        }
    }

    static boolean isOurs(Object l) {
        return l != null && Proxy.isProxyClass(l.getClass()) && Proxy.getInvocationHandler(l) instanceof Handler;
    }

    /** DiPO's own listener behind a proxy (or the object itself) */
    static Object unwrap(Object l) {
        return isOurs(l) ? ((Handler) Proxy.getInvocationHandler(l)).dipo : l;
    }

    /**
     * CarPlayService.cbCarPlayServices in place: every listener DiPO adds is stored wrapped, and DiPO's
     * remove/contains/indexOf with its own listener still find the wrapped one (ArrayList compares with equals,
     * which DiPO's listener can't answer for a proxy).
     */
    static final class ListenerList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        final Class<?> iface;
        final ClassLoader cl;

        ListenerList(Class<?> iface, ClassLoader cl, ArrayList<Object> from) {
            this.iface = iface;
            this.cl = cl;
            for (Object l : from) add(l);
        }

        Object wrap(Object l) {
            return l == null || isOurs(l) || !iface.isInstance(l) ? l
                    : Proxy.newProxyInstance(cl, new Class<?>[] { iface }, new Handler(l));
        }

        @Override
        public boolean add(Object l) {
            return super.add(wrap(l));
        }

        @Override
        public void add(int i, Object l) {
            super.add(i, wrap(l));
        }

        @Override
        public Object set(int i, Object l) {
            return super.set(i, wrap(l));
        }

        @Override
        public int indexOf(Object o) {
            Object want = unwrap(o);
            for (int i = 0; i < size(); i++) if (unwrap(get(i)) == want) return i;
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            Object want = unwrap(o);
            for (int i = size() - 1; i >= 0; i--) if (unwrap(get(i)) == want) return i;
            return -1;
        }

        @Override
        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        @Override
        public boolean remove(Object o) {
            int i = indexOf(o);
            if (i < 0) return false;
            remove(i);
            return true;
        }
    }

    static final class Handler implements InvocationHandler {
        final Object dipo;

        Handler(Object dipo) {
            this.dipo = dipo;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (enabled && "onRequestUI".equals(m.getName()) && args != null && args.length == 1 && URL.equals(args[0])) {
                onVideoRequested();
                return null;
            }
            if ("equals".equals(m.getName()) && args != null && args.length == 1 && args[0] == proxy) return Boolean.TRUE;
            try {
                return m.invoke(dipo, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    /** the video engine closed its window (its back button): CarPlay gets the screen back */
    static void engineClosed() {
        Object service = carPlay;
        if (service != null && borrowedAt != 0) unborrow(service, "video closed");
    }

    /** the phone wants to show a video: borrow the screen for it, as LIVI does; the engine shows it, else back after RETURN_MS */
    static void onVideoRequested() {
        log("video: the phone asked for " + URL + " (kept from DiPO); borrowing the screen for " + BORROW_ID);
        final Object service = carPlay;
        if (service == null) return;
        try {
            ClassLoader cl = service.getClass().getClassLoader();
            Object priority = enumValue(cl, "CarPlayTransferPriority", "UserInitiated");
            Object constraint = enumValue(cl, "CarPlayConstraint", "UserInitiated");
            Method borrow = service.getClass().getMethod("changeModeBorrowScreen",
                    priority.getClass(), constraint.getClass(), String.class);
            borrow.invoke(service, priority, constraint, BORROW_ID);
            final long at = System.currentTimeMillis();
            borrowedAt = at;
            Method show = VideoMessages.engineShow;
            if (show != null) {
                show.invoke(null);
                log("video: screen borrowed; the video engine shows it");
                return;
            }
            log("video: screen borrowed; no player yet, giving it back in " + RETURN_MS / 1000 + " s");
            Thread back = new Thread("cp-climate-video-return") {
                @Override
                public void run() {
                    try {
                        Thread.sleep(RETURN_MS);
                    } catch (InterruptedException ignored) {
                    }
                    if (borrowedAt == at) unborrow(service, "stage 1 timer");
                }
            };
            back.setDaemon(true);
            back.start();
        } catch (Throwable t) {
            log("video: borrow failed: " + (t instanceof InvocationTargetException ? t.getCause() : t));
        }
    }

    static void unborrow(Object service, String why) {
        try {
            service.getClass().getMethod("changeModeUnborrowScreen", String.class).invoke(service, BORROW_ID);
            borrowedAt = 0;
            log("video: screen given back to CarPlay (" + why + ")");
        } catch (Throwable t) {
            log("video: unborrow failed: " + (t instanceof InvocationTargetException ? t.getCause() : t));
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Object enumValue(ClassLoader cl, String type, String name) throws ClassNotFoundException {
        return Enum.valueOf((Class) cl.loadClass(PKG + type), name);
    }

    static void log(String m) {
        VsChannel.Log o = out;
        if (o != null) o.log(m);
    }
}
