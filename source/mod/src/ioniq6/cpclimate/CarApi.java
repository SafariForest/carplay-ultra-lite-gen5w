package ioniq6.cpclimate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The car's own binder calls, looked up by name in its framework (automotive.jar is on the boot class path)
 * instead of numbers written for one firmware: AIDL numbers its calls in declaration order, so a firmware
 * whose services gained or lost a method numbers them differently, and a hard-coded number would call
 * something else. A call whose method is missing, or has other parameter or return types, comes back as -1
 * and cp-climate leaves that control out.
 *
 * The listeners cp-climate answers by hand (DATCService's and BCMService's) are checked as a whole: every
 * call's number, name and parameter types must give the fingerprint of the firmware they were written
 * against (CEEV USA 260507, tools/listener_fingerprint.py computes the same from baksmali output). A
 * listener that answers a call differently from the stock stub can crash system_server.
 */
public final class CarApi {
    public static final String DATC = "com.hkmc.datc.IDATCService", DATC_LISTENER = "com.hkmc.datc.IDATCListener";
    public static final String USM = "com.hkmc.usm.IUSMService", VR = "com.hkmc.vr.IVRService";
    public static final String BCM = "com.hkmc.bcm.IBCMService", BCM_LISTENER = "com.hkmc.bcm.IBCMListener";
    /** the Ioniq 6 (CEEV USA 260507) listeners' fingerprints: 7 calls, and 55 with byte[] out at 37 and 39 */
    public static final String DATC_LISTENER_PRINT = "8a834814775381cc8a77646c06710f8483a5b4b0bb309d49bc2c742719781d0c";
    public static final String BCM_LISTENER_PRINT = "b5a76b60550eed8c576003be0018d2cc00cd893b1d71d5872d4e53a966f4b861";

    private CarApi() {
    }

    /** the transaction code of iface.method if the method exists with exactly this descriptor, else -1 */
    public static int code(String iface, String method, String descriptor) {
        try {
            Class<?> i = Class.forName(iface);
            Method m = only(i, method);
            if (m == null || !descriptor.equals(descriptor(m))) return -1;
            Field f = Class.forName(iface + "$Stub").getDeclaredField("TRANSACTION_" + method);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** the SHA-256 of an AIDL interface's calls ("<code>:<name>(<parameter types>)" by code), or null */
    public static String fingerprint(String iface) {
        try {
            StringBuilder s = new StringBuilder();
            for (String line : lines(Class.forName(iface), Class.forName(iface + "$Stub"))) {
                if (s.length() > 0) s.append('\n');
                s.append(line);
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static List<String> lines(Class<?> iface, Class<?> stub) throws Exception {
        final List<Object[]> calls = new ArrayList<Object[]>();
        for (Field f : stub.getDeclaredFields()) {
            if (!f.getName().startsWith("TRANSACTION_") || f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            String name = f.getName().substring("TRANSACTION_".length());
            Method m = only(iface, name);
            if (m == null) throw new IllegalStateException(name + " is not declared once");
            StringBuilder line = new StringBuilder().append(f.getInt(null)).append(':').append(name).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int k = 0; k < p.length; k++) line.append(k > 0 ? "," : "").append(p[k].getName());
            calls.add(new Object[] { f.getInt(null), line.append(')').toString() });
        }
        Collections.sort(calls, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((Integer) a[0]).compareTo((Integer) b[0]);
            }
        });
        List<String> out = new ArrayList<String>();
        for (Object[] c : calls) out.add((String) c[1]);
        return out;
    }

    /** the one method of that name, or null if there is none or more than one */
    static Method only(Class<?> c, String name) {
        Method found = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    /** a JVM method descriptor, e.g. "(III)V" */
    static String descriptor(Method m) {
        StringBuilder s = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) s.append(desc(p));
        return s.append(')').append(desc(m.getReturnType())).toString();
    }

    static String desc(Class<?> c) {
        if (c.isArray()) return "[" + desc(c.getComponentType());
        if (c == int.class) return "I";
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c == short.class) return "S";
        if (c == char.class) return "C";
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
