package dev.inkprobe;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Build;
import android.os.Looper;
import android.os.Parcel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Read-only-first probe for Xiaomi 15 (dada) ST54L vendor-NCI path.
 * Runs as root via: CLASSPATH=classes.dex app_process /system/bin dev.inkprobe.Probe <mode>
 * Modes: info | read | set7a | restore
 * Writes only under /data/local/tmp.
 */
public final class Probe {
    static final String TAG = "INKPROBE";
    static final String SNAP = "/data/local/tmp/ink-probe-snapshot.bin";
    static final int GID = 15;
    static final byte[] FRAMES_7A = new byte[]{0x01, 0x2A, 0x02, 0x0A, 0x7A};

    static PrintStream out;
    static final LinkedBlockingQueue<byte[]> replies = new LinkedBlockingQueue<byte[]>();
    static volatile int expectedOid = -1;
    static volatile int expectedSub = -1;
    static volatile boolean wildcard = false;
    static String ARG2 = "";
    static final byte[][] TD2_CANDIDATES = new byte[][]{
            {(byte) 0xBB, 0x02, 0x01, 0x7A, 0x00}, // 1 suspected accepted syntax (dry-run wire: Tx 2f 03 05 bb 02 01 7a 00 -> Rx 4f 03 01 00)
            {(byte) 0xBB, 0x02, 0x01, 0x7A},       // 2
            {(byte) 0xBB, 0x01, 0x7A},             // 3
            {(byte) 0xBB, 0x00, 0x01, 0x7A},       // 4
            {(byte) 0xBB, 0x01, 0x01, 0x7A},       // 5
            {(byte) 0xBB, 0x00, 0x02, 0x01, 0x7A}, // 6
            {(byte) 0xB2, 0x02, 0x01, 0x7A, 0x00}, // 7
            {(byte) 0xB2, 0x01, 0x7A, 0x00},       // 8
    };

    static final List<String> INFO = new ArrayList<String>();
    static final StringBuilder errors = new StringBuilder();

    static NfcAdapter adapter;
    static Object aidlService;
    static Object oemExtension;
    static Object cbAdapter;          // NfcAdapter$NfcVendorNciCallback proxy
    static Class<?> cbAdapterClass;
    static Object cbAidl;             // INfcVendorNciCallback proxy
    static Class<?> cbAidlClass;
    static final ExecutorService cbExecutor = Executors.newSingleThreadExecutor();
    static String backend = "none";

    // ---------- output helpers ----------
    static void ev(String event, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tag\":\"").append(TAG).append("\",\"event\":\"").append(esc(event)).append("\",\"message\":\"")
          .append(esc(message)).append("\"}");
        out.println(sb);
        out.flush();
    }

    static void info(String k, String v) {
        INFO.add(k + "=" + v);
        ev("INFO", k + "=" + v);
    }

    static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getClass().getName() + ": " + c.getMessage();
        return m;
    }

    // ---------- reflection helpers ----------
    static boolean classPresent(String name) {
        try { Class.forName(name); return true; } catch (Throwable t) { return false; }
    }

    static String methodsOf(Class<?> c, String nameFilterSubstr) {
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            if (nameFilterSubstr != null && !m.getName().toLowerCase().contains(nameFilterSubstr.toLowerCase())) continue;
            sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getSimpleName());
            }
            sb.append(") ");
        }
        return sb.toString().trim();
    }

    static String methodsOfAny(Class<?> c, String... substrings) {
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            boolean hit = false;
            for (String s : substrings) if (m.getName().toLowerCase().contains(s.toLowerCase())) { hit = true; break; }
            if (!hit) continue;
            sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getSimpleName());
            }
            sb.append(") ");
        }
        return sb.toString().trim();
    }

    // ---------- adapter / service acquisition ----------
    static void acquireAdapter(Context ctx) {
        // Route A: install NfcServiceManager if framework initializer is empty (mirrors prototype)
        try {
            Class<?> csm = Class.forName("android.nfc.NfcServiceManager");
            Class<?> cfi = Class.forName("android.nfc.NfcFrameworkInitializer");
            Object mgr = cfi.getMethod("getNfcServiceManager").invoke(null);
            info("NfcFrameworkInitializer.getNfcServiceManager", mgr == null ? "null" : mgr.getClass().getName());
            if (mgr == null) {
                Object nm = csm.getConstructor().newInstance();
                cfi.getMethod("setNfcServiceManager", csm).invoke(null, nm);
                mgr = cfi.getMethod("getNfcServiceManager").invoke(null);
                info("NfcFrameworkInitializer.setNfcServiceManager", mgr == null ? "STILL_NULL" : "installed " + mgr.getClass().getName());
            }
            // register wrappers is what normal processes get from SystemServiceRegistry; invoke if present
            try {
                cfi.getMethod("registerServiceWrappers").invoke(null);
                info("NfcFrameworkInitializer.registerServiceWrappers", "invoked");
            } catch (Throwable t) {
                info("NfcFrameworkInitializer.registerServiceWrappers", "unavailable " + describe(t));
            }
        } catch (Throwable t) {
            info("NfcFrameworkInitializer", "FAILED " + describe(t));
        }

        // Route B: system context + getDefaultAdapter
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object main = at.getMethod("systemMain").invoke(null);
            Context sys = (Context) at.getMethod("getSystemContext").invoke(main);
            info("systemContext", sys == null ? "null" : sys.getPackageName());
            try {
                adapter = (NfcAdapter) NfcAdapter.class.getMethod("getDefaultAdapter", Context.class).invoke(null, sys);
            } catch (Throwable t) {
                info("getDefaultAdapter(context)", "FAILED " + describe(t));
            }
            if (adapter == null) {
                try {
                    adapter = (NfcAdapter) NfcAdapter.class.getMethod("getNfcAdapter", Context.class).invoke(null, sys);
                    info("getNfcAdapter(context)", adapter == null ? "null" : "ok");
                } catch (Throwable t) {
                    info("getNfcAdapter(context)", "FAILED " + describe(t));
                }
            }
        } catch (Throwable t) {
            info("systemContext", "FAILED " + describe(t));
        }

        if (adapter != null) {
            backend = "adapter";
            info("adapter", "non-null");
            try { info("adapter.isEnabled", String.valueOf(adapter.isEnabled())); } catch (Throwable t) { info("adapter.isEnabled", "FAILED " + describe(t)); }
        } else {
            backend = "none";
            info("adapter", "null");
        }

        // Route C: raw binder to "nfc" service (fallback backend)
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "nfc");
            info("ServiceManager.getService(nfc)", binder == null ? "null" : binder.toString());
            if (binder != null) {
                Class<?> stub = Class.forName("android.nfc.INfcAdapter$Stub");
                aidlService = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
                info("INfcAdapter.asInterface", aidlService.getClass().getName());
                if (adapter == null) backend = "aidl";
            }
        } catch (Throwable t) {
            info("INfcAdapter.asInterface", "FAILED " + describe(t));
        }
        info("backend", backend);
    }

    static void tryOemExtension() {
        try {
            Method m = NfcAdapter.class.getMethod("getNfcOemExtension");
            oemExtension = m.invoke(adapter);
            info("getNfcOemExtension", oemExtension == null ? "null" : oemExtension.getClass().getName());
            if (oemExtension != null) {
                info("NfcOemExtension methods", methodsOf(oemExtension.getClass(), null));
                for (Method mm : oemExtension.getClass().getMethods()) {
                    if (mm.getName().equals("getMaxPausePollingTimeoutMills") || mm.getName().equals("getMaxPausePollingTimeoutMs")) {
                        try { info("oemExtension." + mm.getName(), String.valueOf(mm.invoke(oemExtension))); } catch (Throwable t) { info("oemExtension." + mm.getName(), "FAILED " + describe(t)); }
                    }
                }
            }
        } catch (Throwable t) {
            info("getNfcOemExtension", "FAILED " + describe(t));
        }
    }

    // ---------- callback registration ----------
    static void registerCallback() throws Exception {
        if ("adapter".equals(backend)) {
            cbAdapterClass = Class.forName("android.nfc.NfcAdapter$NfcVendorNciCallback");
            info("NfcVendorNciCallback methods", methodsOf(cbAdapterClass, null));
            InvocationHandler h = new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String n = method.getName();
                    if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                    if ("equals".equals(n)) return proxy == args[0];
                    if ("toString".equals(n)) return "InkProbeVendorCb";
                    try {
                        if ("onVendorNciResponse".equals(n)) {
                            int gid = (Integer) args[0];
                            int oid = (Integer) args[1];
                            byte[] data = (byte[]) (args.length > 2 ? args[2] : null);
                            onVendor(gid, oid, data, true);
                        } else if ("onVendorNciNotification".equals(n)) {
                            int gid = (Integer) args[0];
                            int oid = (Integer) args[1];
                            byte[] data = (byte[]) (args.length > 2 ? args[2] : null);
                            onVendor(gid, oid, data, false);
                        }
                    } catch (Throwable t) {
                        ev("CB_ERROR", describe(t));
                    }
                    return null;
                }
            };
            cbAdapter = Proxy.newProxyInstance(cbAdapterClass.getClassLoader(), new Class<?>[]{cbAdapterClass}, h);
            NfcAdapter.class.getMethod("registerNfcVendorNciCallback", java.util.concurrent.Executor.class, cbAdapterClass)
                    .invoke(adapter, cbExecutor, cbAdapter);
            ev("CONNECTED", "adapter vendor NCI callback registered");
            return;
        }
        if ("aidl".equals(backend)) {
            cbAidlClass = Class.forName("android.nfc.INfcVendorNciCallback");
            info("INfcVendorNciCallback methods", methodsOf(cbAidlClass, null));
            final Binder localBinder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    try {
                        int gid = data.readInt();
                        int oid = data.readInt();
                        byte[] payload = data.createByteArray();
                        ev("AIDL_TX", "code=" + code + " gid=" + gid + " oid=" + oid + " raw=" + hex(payload));
                        onVendor(gid, oid, payload, true);
                    } catch (Throwable t) {
                        ev("CB_ERROR", "aidl onTransact " + describe(t));
                    }
                    return true;
                }
            };
            InvocationHandler h = new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String n = method.getName();
                    if ("asBinder".equals(n)) return localBinder;
                    if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                    if ("equals".equals(n)) return proxy == args[0];
                    if ("toString".equals(n)) return "InkProbeAidlCb";
                    return null;
                }
            };
            cbAidl = Proxy.newProxyInstance(cbAidlClass.getClassLoader(), new Class<?>[]{cbAidlClass}, h);
            Class<?> in = Class.forName("android.nfc.INfcAdapter");
            in.getMethod("registerVendorExtensionCallback", cbAidlClass).invoke(aidlService, cbAidl);
            ev("CONNECTED", "AIDL vendor extension callback registered");
            return;
        }
        throw new IllegalStateException("no backend");
    }

    static void unregisterCallback() {
        try {
            if ("adapter".equals(backend) && adapter != null && cbAdapter != null) {
                NfcAdapter.class.getMethod("unregisterNfcVendorNciCallback", cbAdapterClass).invoke(adapter, cbAdapter);
                ev("DISCONNECTED", "adapter callback unregistered");
            } else if ("aidl".equals(backend) && aidlService != null && cbAidl != null) {
                Class<?> in = Class.forName("android.nfc.INfcAdapter");
                in.getMethod("unregisterVendorExtensionCallback", cbAidlClass).invoke(aidlService, cbAidl);
                ev("DISCONNECTED", "aidl callback unregistered");
            }
        } catch (Throwable t) {
            ev("DISCONNECT_WARN", describe(t));
        }
    }

    static void onVendor(int gid, int oid, byte[] data, boolean response) {
        ev(response ? "VENDOR_RESP" : "VENDOR_NTF", "GID=" + gid + " OID=" + oid + " RAW=" + hex(data));
        if (!response || data == null) return;
        if (wildcard) {
            replies.offer(data.clone());
            return;
        }
        if (oid != expectedOid || data.length == 0 || expectedSub < 0) return;
        if (oid != 2 && (data[0] & 0xFF) != expectedSub) {
            ev("MATCH_SKIP", "payload[0]=" + String.format("%02X", data[0] & 0xFF) + " expectedSub=" + String.format("%02X", expectedSub));
            return;
        }
        replies.offer(data.clone());
    }

    /** send and accept ANY vendor response payload (used for experimental/test OIDs). */
    static byte[] probeRaw(int mt, int gid, int oid, byte[] payload) throws Exception {
        replies.clear();
        wildcard = true;
        try {
            int rc = sendRaw(mt, gid, oid, payload);
            if (rc != 0) { ev("SEND_RC", "rc=" + rc); return null; }
            return replies.poll(2, TimeUnit.SECONDS);
        } finally {
            wildcard = false;
        }
    }

    // ---------- vendor NCI exchange ----------
    static int sendRaw(int mt, int gid, int oid, byte[] payload) throws Exception {
        ev("NCI_TX", "MT=" + mt + " GID=" + gid + " OID=" + oid + " PAYLOAD=" + hex(payload));
        if ("adapter".equals(backend)) {
            Method m = NfcAdapter.class.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
            return ((Integer) m.invoke(adapter, mt, gid, oid, payload)).intValue();
        }
        Class<?> in = Class.forName("android.nfc.INfcAdapter");
        Method m = in.getMethod("sendVendorNciMessage", int.class, int.class, int.class, byte[].class);
        return ((Integer) m.invoke(aidlService, mt, gid, oid, payload)).intValue();
    }

    /** exchange(oid, sub, extra) mirrors RootProbe.exchange(int i, int i2, byte[] bArr). */
    static byte[] exchange(int apiOid, int sub, byte[] extra) throws Exception {
        byte[] tx = new byte[1 + extra.length];
        tx[0] = (byte) sub;
        System.arraycopy(extra, 0, tx, 1, extra.length);
        replies.clear();
        expectedOid = apiOid;
        expectedSub = sub;
        try {
            int rc = sendRaw(1, GID, apiOid, tx);
            if (rc != 0) throw new IllegalStateException("sendVendorNciMessage returned " + rc);
            byte[] rx = replies.poll(3, TimeUnit.SECONDS);
            if (rx == null) throw new IllegalStateException("vendor NCI response timeout (no callback)");
            ev("NCI_RX", hex(rx));
            if (apiOid == 2) {
                if (rx[0] == 0) return Arrays.copyOfRange(rx, 1, rx.length);
                throw new IllegalStateException("ST config cmd status " + hex(rx));
            }
            if (rx.length < 2 || rx[1] != 0) throw new IllegalStateException("sub command status " + hex(rx));
            return Arrays.copyOfRange(rx, 2, rx.length);
        } finally {
            expectedOid = -1;
            expectedSub = -1;
        }
    }

    static byte[] exchange(int apiOid, byte[] extra) throws Exception {
        return exchange(1, apiOid, extra);
    }

    /** raw exchange: returns the full response payload (no status validation, so status can be reported). */
    static byte[] rawExchange(int apiOid, int sub, byte[] extra) throws Exception {
        byte[] tx = new byte[1 + extra.length];
        tx[0] = (byte) sub;
        System.arraycopy(extra, 0, tx, 1, extra.length);
        replies.clear();
        expectedOid = apiOid;
        expectedSub = sub;
        try {
            int rc = sendRaw(1, GID, apiOid, tx);
            if (rc != 0) throw new IllegalStateException("sendVendorNciMessage returned " + rc);
            byte[] rx = replies.poll(3, TimeUnit.SECONDS);
            if (rx == null) throw new IllegalStateException("vendor NCI response timeout");
            ev("NCI_RX", hex(rx));
            return rx;
        } finally {
            expectedOid = -1;
            expectedSub = -1;
        }
    }

    static int statusOf(byte[] rx) {
        return (rx == null || rx.length < 2) ? -1 : (rx[1] & 0xFF);
    }

    // ---------- ST54L RF config parsing (mirror of RfConfig) ----------
    static int crc16(byte[] b) {
        int crc = 0x9944; // 39236
        for (int i = 4; i < b.length; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) crc = (((crc & 0x8000) != 0 ? 0x1021 : 0) ^ (crc << 1)) & 0xFFFF;
        }
        return crc;
    }

    static int readWord(byte[] b, int off) {
        return ((b[off + 3] & 0xFF) << 24) | (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    static byte[] parseRf(int param, byte[] resp) {
        if (resp == null || resp.length != 199 || resp[0] != 1 || resp[1] != param || (resp[2] & 0xFF) != 196) {
            throw new IllegalArgumentException("unexpected RF cfg response len=" + (resp == null ? -1 : resp.length)
                    + " hdr=" + (resp == null || resp.length < 3 ? "?" : hex(Arrays.copyOfRange(resp, 0, 3))));
        }
        byte[] cfg = Arrays.copyOfRange(resp, 3, resp.length);
        int crcStored = (cfg[2] & 0xFF) | ((cfg[3] & 0xFF) << 8);
        int crcCalc = crc16(cfg);
        if (cfg.length != 196 || cfg[0] != 0x44 || cfg[1] != 0 || crcStored != crcCalc) {
            throw new IllegalArgumentException("RF layout/CRC mismatch len=" + cfg.length + " magic=" + String.format("%02X%02X", cfg[0], cfg[1])
                    + " crcStored=" + String.format("%04X", crcStored) + " crcCalc=" + String.format("%04X", crcCalc));
        }
        return cfg;
    }

    static String rfValues(byte[] cfg) {
        int w1 = readWord(cfg, 4);
        int w2 = readWord(cfg, 100);
        return ((w1 >>> 11) & 31) + "/" + ((w1 >>> 27) & 31) + "/" + ((w2 >>> 11) & 31) + "/" + ((w2 >>> 27) & 31);
    }

    static byte[] readFrames() throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                byte[] r = exchange(21, new byte[0]);
                ev("FRAMES", "RAW=" + hex(r));
                return r;
            } catch (Exception e) {
                last = e;
                ev("FRAMES_RETRY", describe(e));
                if (i < 2) Thread.sleep(300);
            }
        }
        throw last;
    }

    static void setFrames(byte[] cfg) throws Exception {
        exchange(19, cfg);
    }

    static byte[] readRf(int param) throws Exception {
        byte[] resp = exchange(2, 3, new byte[]{0x00, 0x10, 0x01, (byte) param});
        ev("RF_RAW", "PARAM=" + param + " RESP=" + hex(resp));
        return parseRf(param, resp);
    }

    static boolean pausePolling() {
        if (oemExtension == null) { ev("PAUSE", "no oem extension"); return false; }
        try {
            Method m = oemExtension.getClass().getMethod("pausePolling", long.class);
            for (int i = 0; i < 8; i++) {
                int rc = ((Integer) m.invoke(oemExtension, 30000L)).intValue();
                ev("PAUSE", "pausePolling(30000) -> " + rc);
                if (rc == 1) return true;
                if (rc != 2) { ev("PAUSE", "unexpected rc " + rc); return false; }
                Thread.sleep(120);
            }
            ev("PAUSE", "could not reach paused state");
            return false;
        } catch (Throwable t) {
            ev("PAUSE", "FAILED " + describe(t));
            return false;
        }
    }

    static void resumePolling() {
        if (oemExtension == null) return;
        try {
            Method m = oemExtension.getClass().getMethod("resumePolling");
            Object rc = m.invoke(oemExtension);
            ev("RESUME", "resumePolling -> " + rc);
        } catch (Throwable t) {
            try {
                Method m2 = NfcAdapter.class.getMethod("resumePolling");
                m2.invoke(adapter);
                ev("RESUME", "NfcAdapter.resumePolling -> ok");
            } catch (Throwable t2) {
                ev("RESUME", "FAILED " + describe(t));
            }
        }
    }

    // ---------- modes ----------
    static void modeInfo() {
        info("BUILD", Build.MANUFACTURER + " " + Build.MODEL + " device=" + Build.DEVICE + " sdk=" + Build.VERSION.SDK_INT + " release=" + Build.VERSION.RELEASE);
        String[] classes = new String[]{
                "android.nfc.NfcAdapter",
                "android.nfc.NfcAdapter$NfcVendorNciCallback",
                "android.nfc.NfcAdapter$SendVendorNciStatus",
                "android.nfc.NfcServiceManager",
                "android.nfc.NfcServiceManager$ServiceRegisterer",
                "android.nfc.NfcFrameworkInitializer",
                "android.nfc.NfcOemExtension",
                "android.nfc.INfcAdapter",
                "android.nfc.INfcVendorNciCallback",
                "android.nfc.NfcVendorNciCallbackListener",
                "android.app.ActivityThread",
        };
        for (String c : classes) info("class " + c, classPresent(c) ? "PRESENT" : "MISSING");

        try {
            Class<?> a = Class.forName("android.nfc.NfcAdapter");
            info("NfcAdapter vendor/oem methods", methodsOfAny(a, "sendVendorNciMessage", "registerNfcVendorNciCallback",
                    "unregisterNfcVendorNciCallback", "getNfcOemExtension", "pausePolling", "resumePolling"));
            info("NfcAdapter.getDefaultAdapter", methodsOfAny(a, "getDefaultAdapter", "getNfcAdapter"));
        } catch (Throwable t) { info("NfcAdapter methods", "FAILED " + describe(t)); }
        try {
            Class<?> c = Class.forName("android.nfc.NfcAdapter$NfcVendorNciCallback");
            info("NfcVendorNciCallback methods", methodsOf(c, null));
        } catch (Throwable t) { info("NfcVendorNciCallback methods", "FAILED " + describe(t)); }
        try {
            Class<?> c = Class.forName("android.nfc.NfcServiceManager");
            info("NfcServiceManager methods", methodsOf(c, null));
            Class<?> sr = Class.forName("android.nfc.NfcServiceManager$ServiceRegisterer");
            info("ServiceRegisterer methods", methodsOf(sr, null));
        } catch (Throwable t) { info("NfcServiceManager methods", "FAILED " + describe(t)); }
        try {
            Class<?> c = Class.forName("android.nfc.NfcFrameworkInitializer");
            info("NfcFrameworkInitializer methods", methodsOf(c, null));
        } catch (Throwable t) { info("NfcFrameworkInitializer methods", "FAILED " + describe(t)); }
        try {
            Class<?> c = Class.forName("android.nfc.INfcAdapter");
            info("INfcAdapter vendor methods", methodsOfAny(c, "sendVendorNciMessage", "VendorExtensionCallback", "pausePolling", "resumePolling", "getNfcAdapterVendorInterface"));
        } catch (Throwable t) { info("INfcAdapter methods", "FAILED " + describe(t)); }
        try {
            Class<?> c = Class.forName("android.nfc.INfcVendorNciCallback");
            info("INfcVendorNciCallback methods", methodsOf(c, null));
        } catch (Throwable t) { info("INfcVendorNciCallback methods", "FAILED " + describe(t)); }

        tryOemExtension();
        // permission view of our process as seen by NFC service
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "nfc");
            info("nfc binder", b == null ? "null" : b.toString());
        } catch (Throwable t) { info("nfc binder", "FAILED " + describe(t)); }
    }

    static void modeMfg() throws Exception {
        registerCallback();
        pausePolling();
        try {
            byte[] v = rawExchange(1, 0x01, new byte[0]);
            ev("STPROPNCI_VERSION", "RAW=" + hex(v));
            byte[] rx = rawExchange(1, 0x02, new byte[0]);
            ev("MFG_RAW", hex(rx));
            if (rx.length >= 2) {
                int st = rx[1] & 0xFF;
                byte[] manuf = Arrays.copyOfRange(rx, 2, rx.length);
                ev("MFG_STATUS", "status=" + st + " len=" + manuf.length);
                ev("MANUF_HEX", hex(manuf));
                if (manuf.length >= 6) {
                    int chip = manuf[0] & 0xFF;
                    int rev = manuf[1] & 0xFF;
                    String[] names = {"Unknown", "ST21NFCA", "ST21NFCB", "ST21NFCC", "ST21NFCD", "ST54J", "ST54L", "ST21NFCL", "ST25RN300"};
                    String chipName = chip < names.length ? names[chip] : "Unknown";
                    ev("CHIP", "idByte=" + chip + " chip=" + chipName + " siliconRev=" + rev);
                    ev("FW_VERSION", String.format("%02X.%02X.%02X%02X", manuf[2] & 0xFF, manuf[3] & 0xFF, manuf[4] & 0xFF, manuf[5] & 0xFF));
                }
                if (manuf.length > 21) {
                    ev("CUSTOMER_DATA", hex(Arrays.copyOfRange(manuf, 21, manuf.length)));
                }
            }
        } finally {
            resumePolling();
            unregisterCallback();
        }
        ev("MFG_DONE", "manufacturer data read");
    }

    static String rawOrTimeout(byte[] rx) {
        return rx == null ? "TIMEOUT" : hex(rx);
    }

    /**
     * Bounded experiment: ST FW TEST channel (oid=3, passthrough to NFCC per stpropnci_prop_st.cc default),
     * plus a probe of whether the vendor-NCI path carries non-prop NCI (gid=0 CORE GET_CONFIG).
     * Everything is under pausePolling(); nothing persistent is written.
     */
    static void modeTestcmd() throws Exception {
        registerCallback();
        boolean paused = pausePolling();
        try {
            ev("SANITY_VERSION", "RX=" + rawOrTimeout(probeRaw(1, 15, 1, new byte[]{0x01})));       // STPROPNCI lib version
            ev("UNKNOWN_PROP_SUBOID", "RX=" + rawOrTimeout(probeRaw(1, 15, 1, new byte[]{0x7F}))); // negative control

            int[] testSubs = new int[]{0x07, 0xB0, 0xB1, 0xB5, 0xC9, 0xCD};
            boolean anyTestData = false;
            for (int sub : testSubs) {
                byte[] rx = probeRaw(1, 15, 3, new byte[]{(byte) sub});
                ev("PROP_TEST", "SUB=0x" + String.format("%02X", sub) + " RX=" + rawOrTimeout(rx));
                if (rx != null && rx.length > 2) anyTestData = true;
            }
            if (anyTestData) {
                ev("TEST_FIELD", "B3,00 -> " + rawOrTimeout(probeRaw(1, 15, 3, new byte[]{(byte) 0xB3, 0x00})));
                ev("TEST_FIELD", "B3,01 -> " + rawOrTimeout(probeRaw(1, 15, 3, new byte[]{(byte) 0xB3, 0x01})));
                ev("TEST_FIELD", "B3,00 -> " + rawOrTimeout(probeRaw(1, 15, 3, new byte[]{(byte) 0xB3, 0x00})));
            } else {
                ev("TEST_FIELD", "skipped: no PROP_TEST command returned data");
            }

            // Does the vendor path carry arbitrary (non-prop) NCI? Read-only CORE GET_CONFIG.
            ev("CORE_GETCFG_P00", "RX=" + rawOrTimeout(probeRaw(1, 0, 2, new byte[]{0x00})));
            ev("CORE_GETCFG_P01", "RX=" + rawOrTimeout(probeRaw(1, 0, 2, new byte[]{0x01})));
            ev("CORE_GETCFG_P02", "RX=" + rawOrTimeout(probeRaw(1, 0, 2, new byte[]{0x02})));
            // read-only RF: RF_GET_LISTEN_MODE_ROUTING is oid=2 under gid=1; payload = 1 byte
            ev("RF_GET_ROUTING", "RX=" + rawOrTimeout(probeRaw(1, 1, 2, new byte[]{0x00})));
        } catch (Throwable t) {
            ev("ERROR", describe(t));
        } finally {
            resumePolling();
            unregisterCallback();
        }
        ev("TESTCMD_DONE", "paused=" + paused);
    }

    /** read-only non-prop NCI: correct CORE_GET_CONFIG (gid=0, oid=0x03) parameter reads. */
    static void modeNci() throws Exception {
        registerCallback();
        boolean paused = pausePolling();
        try {
            for (int p = 0; p <= 3; p++) {
                ev("CORE_GETCFG", "PARAM=0x" + String.format("%02X", p) + " RX=" + rawOrTimeout(probeRaw(1, 0, 3, new byte[]{(byte) p})));
            }
            ev("RF_GET_ROUTING", "RX=" + rawOrTimeout(probeRaw(1, 1, 2, new byte[]{0x00})));
        } catch (Throwable t) {
            ev("ERROR", describe(t));
        } finally {
            resumePolling();
            unregisterCallback();
        }
        ev("NCI_DONE", "paused=" + paused);
    }

    /**
     * Bounded syntax exploration for ST_NCI_PROP_TEST_DATA (0xB2) / TEST_DATA_EXTENDED (0xBB)
     * with the RF field forced ON. Primary goal: which candidate payloads return status 0x00
     * (syntax accepted) vs 0x05 (syntax error). No claim of RF emission unless NTFs prove it.
     */
    static void modeTestdata() throws Exception {
        registerCallback();
        boolean paused = pausePolling();
        boolean fieldOn = false;
        int accepted = 0, idx = 0;
        try {
            // identity for the record (read-only)
            try {
                byte[] m = probeRaw(1, 15, 1, new byte[]{0x02});
                ev("IDENTITY_MFG", rawOrTimeout(m));
                if (m != null && m.length >= 8) {
                    ev("IDENTITY", "chip=" + (m[2] & 0xFF) + " siliconRev=" + (m[3] & 0xFF)
                            + " fw=" + String.format("%02X.%02X.%02X%02X", m[4] & 0xFF, m[5] & 0xFF, m[6] & 0xFF, m[7] & 0xFF));
                }
            } catch (Throwable t) {
                ev("IDENTITY_ERROR", describe(t));
            }
            // field ON
            byte[] on = probeRaw(1, 15, 3, new byte[]{(byte) 0xB3, 0x01});
            fieldOn = on != null && on.length > 0 && (on[0] & 0xFF) == 0x00;
            ev("FIELD_ON", "RX=" + rawOrTimeout(on) + " OK=" + fieldOn);
            Thread.sleep(200);

            byte[][] frames = new byte[][]{
                    {0x7A},
                    {0x01, 0x7A},
                    {0x01, 0x01, 0x7A},
                    {0x00, 0x01, 0x7A},
                    {0x01, 0x7A, 0x00},
                    {0x7A, 0x01},
                    {0x01, 0x7A, 0x23, (byte) 0x8D},
                    {0x02, 0x01, 0x7A, 0x00},
                    {0x00, 0x02, 0x01, 0x7A},
            };
            for (int sub : new int[]{0xB2, 0xBB}) {
                for (byte[] f : frames) {
                    byte[] payload = new byte[1 + f.length];
                    payload[0] = (byte) sub;
                    System.arraycopy(f, 0, payload, 1, f.length);
                    idx++;
                    byte[] rx = null;
                    try {
                        rx = probeRaw(1, 15, 3, payload);
                    } catch (Throwable t) {
                        ev("TESTDATA_ERROR", "IDX=" + idx + " " + describe(t));
                    }
                    int status = (rx != null && rx.length > 0) ? (rx[0] & 0xFF) : -1;
                    ev("TESTDATA", "IDX=" + idx + " OID=" + String.format("%02X", sub) + " PAYLOAD=" + hex(payload)
                            + " RX=" + rawOrTimeout(rx) + " STATUS=" + (status < 0 ? "TIMEOUT" : String.format("0x%02X", status)));
                    if (status == 0) {
                        accepted++;
                        for (int rep = 1; rep <= 3; rep++) {
                            Thread.sleep(250);
                            byte[] rrx = probeRaw(1, 15, 3, payload);
                            int rstatus = (rrx != null && rrx.length > 0) ? (rrx[0] & 0xFF) : -1;
                            ev("TESTDATA_REPEAT", "IDX=" + idx + " REP=" + rep + " RX=" + rawOrTimeout(rrx)
                                    + " STATUS=" + (rstatus < 0 ? "TIMEOUT" : String.format("0x%02X", rstatus)));
                        }
                    }
                    Thread.sleep(150);
                }
            }
            ev("TESTDATA_SUMMARY", "paused=" + paused + " fieldOn=" + fieldOn + " accepted=" + accepted + " candidates=" + idx);
        } catch (Throwable t) {
            ev("ERROR", describe(t));
        } finally {
            try {
                ev("FIELD_OFF", "RX=" + rawOrTimeout(probeRaw(1, 15, 3, new byte[]{(byte) 0xB3, 0x00})));
            } catch (Throwable t) {
                ev("FIELD_OFF_ERROR", describe(t));
            }
            resumePolling();
            unregisterCallback();
        }
        ev("TESTDATA_DONE", "done");
    }

    static byte[] probeRawLong(int mt, int gid, int oid, byte[] payload, long timeoutMs) throws Exception {
        replies.clear();
        wildcard = true;
        try {
            int rc = sendRaw(mt, gid, oid, payload);
            if (rc != 0) { ev("SEND_RC", "rc=" + rc); return null; }
            return replies.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            wildcard = false;
        }
    }

    /** wait until the vendor path answers again after an NFC stack restart. */
    static boolean waitVendorHealthy(int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                byte[] rx = probeRaw(1, 15, 1, new byte[]{0x01});
                if (rx != null && rx.length > 1 && (rx[1] & 0xFF) == 0x00) {
                    ev("HEALTHY", "attempt=" + i);
                    return true;
                }
            } catch (Throwable t) {
                ev("HEALTH_PROBE_ERROR", describe(t));
            }
            ev("WAIT_RECOVERY", "attempt=" + i);
            try { Thread.sleep(600); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    /**
     * Focused single-candidate TEST_DATA probe with restart-aware recovery.
     * Usage: run.sh td2 <1..8>   (candidate table TD2_CANDIDATES)
     */
    static void modeTd2() throws Exception {
        int idx = 1;
        try {
            if (ARG2 != null && ARG2.trim().length() > 0) idx = Integer.parseInt(ARG2.trim());
        } catch (Throwable ignored) { }
        if (idx < 1 || idx > TD2_CANDIDATES.length) {
            ev("ERROR", "candidate index out of range 1.." + TD2_CANDIDATES.length);
            return;
        }
        byte[] payload = TD2_CANDIDATES[idx - 1];
        try { registerCallback(); } catch (Throwable t) { ev("REGISTER_WARN", describe(t)); }
        boolean healthy = waitVendorHealthy(25);
        ev("PRE_HEALTH", "healthy=" + healthy + " idx=" + idx + " payload=" + hex(payload) + " fw=" + "02.04.1188");
        boolean paused = pausePolling();
        try {
            byte[] on = probeRawLong(1, 15, 3, new byte[]{(byte) 0xB3, 0x01}, 3000);
            ev("FIELD_ON", "RX=" + rawOrTimeout(on) + " OK=" + (on != null && on.length > 0 && (on[0] & 0xFF) == 0x00));
            Thread.sleep(200);
            long t0 = System.currentTimeMillis();
            byte[] rx = null;
            try {
                rx = probeRawLong(1, 15, 3, payload, 5000);
            } catch (Throwable t) {
                ev("TD2_SEND_ERROR", describe(t));
            }
            long dt = System.currentTimeMillis() - t0;
            int status = (rx != null && rx.length > 0) ? (rx[0] & 0xFF) : -1;
            ev("TD2_RESULT", "IDX=" + idx + " PAYLOAD=" + hex(payload) + " RX=" + rawOrTimeout(rx)
                    + " STATUS=" + (status < 0 ? "TIMEOUT" : String.format("0x%02X", status)) + " WAIT_MS=" + dt);
            if (status == 0) {
                for (int rep = 1; rep <= 2; rep++) {
                    Thread.sleep(400);
                    byte[] r2 = probeRawLong(1, 15, 3, payload, 5000);
                    int s2 = (r2 != null && r2.length > 0) ? (r2[0] & 0xFF) : -1;
                    ev("TD2_REPEAT", "REP=" + rep + " RX=" + rawOrTimeout(r2)
                            + " STATUS=" + (s2 < 0 ? "TIMEOUT" : String.format("0x%02X", s2)));
                }
            }
        } catch (Throwable t) {
            ev("ERROR", describe(t));
        } finally {
            try {
                ev("FIELD_OFF", "RX=" + rawOrTimeout(probeRawLong(1, 15, 3, new byte[]{(byte) 0xB3, 0x00}, 3000)));
            } catch (Throwable t) {
                ev("FIELD_OFF_ERROR", describe(t));
            }
            resumePolling();
            unregisterCallback();
        }
        ev("TD2_DONE", "idx=" + idx + " paused=" + paused);
    }

    static void modeRead() throws Exception {
        registerCallback();
        pausePolling();
        try {
            // sanity check: ST_PROP_NCI_GET_STPROPNCI_VERSION_SUBOID = 1 (native HAL reply, no NFCC)
            try {
                byte[] v = exchange(1, new byte[]{0x01});
                ev("STPROPNCI_VERSION", "RAW=" + hex(v));
            } catch (Throwable t) {
                ev("STPROPNCI_VERSION_ERROR", describe(t));
            }
            try {
                byte[] frames = readFrames();
                ev("BASELINE", "POLLING_FRAMES=" + hex(frames));
            } catch (Throwable t) {
                ev("FRAMES_ERROR", describe(t));
            }
            for (int p = 2; p <= 4; p++) {
                try {
                    byte[] cfg = readRf(p);
                    ev("RF_CONFIG", "PARAM=" + p + " APC=" + rfValues(cfg) + " LEN=" + cfg.length + " RAW=" + hex(cfg));
                } catch (Throwable t) {
                    ev("RF_ERROR", "PARAM=" + p + " " + describe(t));
                }
            }
        } finally {
            resumePolling();
            unregisterCallback();
        }
        ev("READ_DONE", "read-only probe finished; nothing written");
    }

    static void modeSet7a() throws Exception {
        registerCallback();
        boolean paused = pausePolling();
        if (!paused) {
            ev("ABORT", "pausePolling did not return 1; no config written");
            unregisterCallback();
            return;
        }
        boolean setAttempted = false;
        boolean clearOk = false;
        try {
            // 0. baseline reads (read-only)
            try { ev("BASELINE_GET", "RAW=" + hex(rawExchange(1, 0x15, new byte[0]))); }
            catch (Throwable t) { ev("BASELINE_GET_ERROR", describe(t)); }
            for (int p = 2; p <= 4; p++) {
                try {
                    byte[] rx = rawExchange(2, 0x03, new byte[]{0x00, 0x10, 0x01, (byte) p});
                    ev("RF_CFG", "PARAM=" + p + " STATUS=" + (rx.length > 0 ? (rx[0] & 0xFF) : -1) + " RAW=" + hex(rx));
                } catch (Throwable t) {
                    ev("RF_CFG_ERROR", "PARAM=" + p + " " + describe(t));
                }
            }
            // 1. SET 7A custom poll frames: payload[0]=0x13 (SET_RF_CUSTOM_POLL_FRAME), rest = RfFrameEntry TLV list
            setAttempted = true;
            byte[] setRx = rawExchange(1, 0x13, FRAMES_7A);
            ev("SET7A_STATUS", "status=0x" + String.format("%02X", statusOf(setRx)) + " RAW=" + hex(setRx));
            Thread.sleep(250);
            // 2. GET readback
            try { ev("GET_AFTER_SET", "RAW=" + hex(rawExchange(1, 0x15, new byte[0]))); }
            catch (Throwable t) { ev("GET_AFTER_SET_ERROR", describe(t)); }
            // 3. clear/disable custom poll frames (factory default: count=0)
            byte[] clearRx = rawExchange(1, 0x13, new byte[]{0x00});
            clearOk = statusOf(clearRx) == 0;
            ev("CLEAR_STATUS", "status=0x" + String.format("%02X", statusOf(clearRx)) + " RAW=" + hex(clearRx));
            Thread.sleep(250);
            // 4. GET readback after clear
            try { ev("GET_AFTER_CLEAR", "RAW=" + hex(rawExchange(1, 0x15, new byte[0]))); }
            catch (Throwable t) { ev("GET_AFTER_CLEAR_ERROR", describe(t)); }
        } catch (Throwable t) {
            ev("ERROR", describe(t));
        } finally {
            if (setAttempted && !clearOk) {
                ev("CLEAR_RETRY", "retrying disable of custom poll frames");
                try {
                    byte[] clearRx = rawExchange(1, 0x13, new byte[]{0x00});
                    clearOk = statusOf(clearRx) == 0;
                    ev("CLEAR_RETRY_STATUS", "status=0x" + String.format("%02X", statusOf(clearRx)) + " RAW=" + hex(clearRx));
                } catch (Throwable t) {
                    ev("CLEAR_RETRY_ERROR", describe(t));
                }
            }
            resumePolling();
            unregisterCallback();
        }
        ev("SET7A_DONE", "clearOk=" + clearOk);
    }

    static void modeRestore() throws Exception {
        registerCallback();
        pausePolling();
        try {
            byte[] original = Files.readAllBytes(new File(SNAP).toPath());
            ev("SNAPSHOT", "RAW=" + hex(original));
            byte[] now = readFrames();
            if (Arrays.equals(now, original)) {
                ev("RESTORED", "already equals snapshot; nothing to do");
                deleteSnap();
            } else {
                setFrames(original);
                Thread.sleep(300);
                byte[] after = readFrames();
                if (Arrays.equals(after, original)) {
                    ev("RESTORED", "snapshot re-applied and verified");
                    deleteSnap();
                } else {
                    ev("RESTORE_MISMATCH", "AFTER=" + hex(after));
                }
            }
        } finally {
            resumePolling();
            unregisterCallback();
        }
    }

    static void saveSnapshot(byte[] frames) throws Exception {
        File f = new File(SNAP);
        File tmp = new File(SNAP + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(frames);
        fos.getFD().sync();
        fos.close();
        if (!tmp.renameTo(f)) throw new IllegalStateException("snapshot rename failed");
        try { Runtime.getRuntime().exec(new String[]{"/system/bin/chmod", "600", SNAP}).waitFor(); } catch (Throwable ignored) { }
        ev("SNAPSHOT_SAVED", SNAP + " " + hex(frames));
    }

    static void deleteSnap() {
        File f = new File(SNAP);
        if (f.exists() && !f.delete()) ev("SNAPSHOT_KEPT", "delete failed " + SNAP);
        else ev("SNAPSHOT_DELETED", SNAP);
    }

    // ---------- main ----------
    public static void main(String[] args) {
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
        final String mode = args.length > 0 ? args[0] : "info";
        ARG2 = args.length > 1 ? args[1] : "";
        Looper.prepareMainLooper();
        Context ctx = null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object main = at.getMethod("systemMain").invoke(null);
            ctx = (Context) at.getMethod("getSystemContext").invoke(main);
        } catch (Throwable t) {
            ev("ERROR", "systemContext " + describe(t));
        }
        acquireAdapter(ctx);
        tryOemExtension();
        if ("info".equals(mode)) {
            modeInfo();
            ev("EXIT", "0");
            System.exit(0);
        }
        final CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                int rc = 0;
                try {
                    if ("read".equals(mode)) modeRead();
                    else if ("set7a".equals(mode)) modeSet7a();
                    else if ("restore".equals(mode)) modeRestore();
                    else if ("mfg".equals(mode)) modeMfg();
                    else if ("testcmd".equals(mode)) modeTestcmd();
                    else if ("nci".equals(mode)) modeNci();
                    else if ("testdata".equals(mode)) modeTestdata();
                    else if ("td2".equals(mode)) modeTd2();
                    else { ev("ERROR", "unknown mode " + mode); rc = 2; }
                } catch (Throwable t) {
                    ev("ERROR", describe(t));
                    rc = 1;
                } finally {
                    done.countDown();
                    ev("EXIT", String.valueOf(rc));
                    out.flush();
                    System.exit(rc);
                }
            }
        }, "inkprobe-worker");
        worker.start();
        Looper.loop();
    }
}
