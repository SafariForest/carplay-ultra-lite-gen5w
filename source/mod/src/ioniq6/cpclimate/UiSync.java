package ioniq6.cpclimate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The uiSync channel's protocol (LIVI ui_sync.rs): the phone's CarPlay theme layouts and palettes.
 * Each message is an OPACK dictionary (the panel's keys, e.g. displayUUID, plus "data") whose "data"
 * is a binary plist header {type, ssn, psn, asn, ...}; commands carry another binary plist in the
 * header's "data". The car answers the phone's reset, allows layout changes, asks for its cluster
 * layout (the phone's theme settings and wallpapers follow the active layout and palette), gives
 * focus and acknowledges commands; the layouts and palettes the phone picks are only logged.
 */
final class UiSync {
    static final long RESET = 0, RESET_COMPLETE = 1, COMMAND = 2, COMMAND_ACK = 3, VERSION = 2;
    static final long START_LAYOUT_TRANSITION = 0x03, REQUEST_LAYOUT = 0x05, GIVE_FOCUS = 0x07, REQUEST_FOCUS = 0x08,
            PALETTE_CHANGE = 0x10, ALLOW_LAYOUT_CHANGE = 0x11, APPEARANCE_PREFERENCE_CHANGE = 0x13;
    static final long FOCUS_TOKEN = 4711, LIGHT = 0, DARK = 1;

    /** a cluster layout of the theme: its id, a palette, and the zone that shows the map with its map component */
    static final class Layout {
        final String name, id, palette, zone, component;

        Layout(String name, String id, String palette, String zone, String component) {
            this.name = name;
            this.id = id;
            this.palette = palette;
            this.zone = zone;
            this.component = component;
        }
    }

    /** theme 44C9FA31EC's cluster layouts (its package.json and theme.json, version 985), first = default */
    static final Layout[] THEME_LAYOUTS = {
        new Layout("Modular", "0D38B526-1271-575D-8A92-393A86B718F9", "blue",
                "F0786093-7DEF-5F93-A171-2839E58C7EB4", "2B28C705-6563-5F11-8867-2AAEC2FC0555"),
        new Layout("DigiRing", "8BC9ABB1-CB20-595E-92BF-D8F099BF3515", "green",
                "BC653D68-76F1-5FC2-B2D5-2F1B78E72C90", "5B5182A0-F0F6-5A8E-B108-D625D0FDC01B"),
        new Layout("DigiBand", "1B54747B-5609-52E0-A1DD-9CC8EF0B11BE", "starlight",
                "0B64D0E5-D130-5269-A668-DBF46FEBB017", "E5F49B90-ADDE-5E2B-8995-66A7C2D0E92F"),
        new Layout("DigiBandTicks", "1B0484EC-1933-524D-80F8-2FC1FC7B4B2D", "orange",
                "9A37CE0F-197C-5087-8734-466801532847", "8ADC3266-A892-5C79-AB5E-6FC2B95794B4"),
        new Layout("MapsMedia", "DB7C6944-5DFF-58DF-94E9-91006781FA93", "vibrant",
                "1839387C-CF95-5D87-B686-7E8501ECE674", "2C9F5C1F-EA6C-51CA-9AAE-68DA4021DB00"),
        new Layout("Cobranded", "6C45CC47-EC01-527A-B40D-1316AA9C936F", "custom",
                "D6E7BC4C-744C-5944-BA96-8F578A31ED6D", "8D56ED3C-CA1D-5110-B846-EC79FF4A2A93"),
    };

    /** the layout named by the setting, the default (Modular) for an unknown name, null for "off" */
    static Layout layout(String name) {
        if ("off".equals(name)) return null;
        for (Layout l : THEME_LAYOUTS) if (l.name.equals(name)) return l;
        return THEME_LAYOUTS[0];
    }

    /** the cluster layout the car asks for once the channel is up (null: none, as 0.9.11) */
    Layout wanted;
    boolean light;

    long session, nextTx, lastRx, version;
    boolean ready, awaitingResetComplete;
    Object phoneId;
    /** the panel's keys from the phone's last message, copied into ours */
    Map<Object, Object> panel = new LinkedHashMap<Object, Object>();
    /** what the phone picked, for the log: "layout X", "palette Y of X", "appearance dark" */
    final List<String> choices = new ArrayList<String>();

    /** the channel reopened: a fresh session from the phone's next reset */
    void reopen() {
        session = nextTx = lastRx = version = 0;
        ready = awaitingResetComplete = false;
        phoneId = null;
        panel = new LinkedHashMap<Object, Object>();
    }

    /** one message from the phone; returns the car's answers */
    @SuppressWarnings("unchecked")
    List<byte[]> handle(byte[] message) {
        List<byte[]> out = new ArrayList<byte[]>();
        Object f = OpackCodec.decode(message);
        if (!(f instanceof Map)) return out;
        Map<Object, Object> frame = (Map<Object, Object>) f;
        Object d = frame.get("data");
        if (!(d instanceof byte[])) return out;
        Object h = Bplist.decode((byte[]) d);
        if (!(h instanceof Map)) return out;
        Map<String, Object> header = (Map<String, Object>) h;
        panel = new LinkedHashMap<Object, Object>(frame);
        panel.remove("data");
        Long type = OpackCodec.asLong(header.get("type"));
        Long ssn = OpackCodec.asLong(header.get("ssn"));
        if (type != null && type == RESET) {
            if (awaitingResetComplete && nextTx == 2) return out;
            session++;
            nextTx = 1;
            lastRx = 0;
            ready = false;
            awaitingResetComplete = true;
            phoneId = header.get("id");
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("version", VERSION);
            withId(extra);
            out.add(message(RESET, null, extra));
        } else if (ssn == null || ssn != session) {
            return out;
        } else if (type != null && type == RESET_COMPLETE && awaitingResetComplete) {
            awaitingResetComplete = false;
            ready = true;
            Long v = OpackCodec.asLong(header.get("version"));
            version = Math.min(v == null ? 1 : v, VERSION);
            lastRx = orLast(header.get("psn"));
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("version", version);
            if (version >= 2) withId(extra);
            out.add(message(RESET_COMPLETE, null, extra));
            out.add(message(COMMAND, command(ALLOW_LAYOUT_CHANGE, "allow", Boolean.TRUE), null));
            if (wanted != null) out.add(message(COMMAND, requestLayout(wanted, light ? LIGHT : DARK), null));
            out.add(message(COMMAND, command(GIVE_FOCUS, "tokenID", FOCUS_TOKEN), null));
        } else if (type != null && type == COMMAND && ready) {
            lastRx = orLast(header.get("psn"));
            boolean wantsFocus = followPhone(header);
            if (!Boolean.TRUE.equals(header.get("noAck"))) out.add(message(COMMAND_ACK, null, null));
            if (wantsFocus) out.add(message(COMMAND, command(GIVE_FOCUS, "tokenID", FOCUS_TOKEN), null));
        } else if (type != null && type == COMMAND_ACK) {
            lastRx = orLast(header.get("psn"));
        }
        return out;
    }

    private long orLast(Object psn) {
        Long p = OpackCodec.asLong(psn);
        return p == null ? lastRx : p;
    }

    /** records the phone's layout, palette or appearance choice; true if it asks for focus */
    @SuppressWarnings("unchecked")
    boolean followPhone(Map<String, Object> header) {
        Object raw = header.get("data");
        if (!(raw instanceof byte[])) return false;
        Object c;
        try {
            c = Bplist.decode((byte[]) raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!(c instanceof Map)) return false;
        Map<String, Object> cmd = (Map<String, Object>) c;
        Object d = cmd.get("data");
        Map<String, Object> data = d instanceof Map ? (Map<String, Object>) d : new LinkedHashMap<String, Object>();
        Long type = OpackCodec.asLong(cmd.get("type"));
        if (type == null) return false;
        if (type == START_LAYOUT_TRANSITION && data.get("layoutID") instanceof String) {
            choices.add("layout " + data.get("layoutID") + (data.get("paletteID") instanceof String ? " palette " + data.get("paletteID") : ""));
        } else if (type == PALETTE_CHANGE && data.get("layoutIdentifier") instanceof String && data.get("paletteIdentifier") instanceof String) {
            choices.add("palette " + data.get("paletteIdentifier") + " of layout " + data.get("layoutIdentifier"));
        } else if (type == APPEARANCE_PREFERENCE_CHANGE) {
            Long p = OpackCodec.asLong(data.get("preference"));
            if (p != null && p >= 0 && p <= 2) choices.add("appearance " + (p == 0 ? "automatic" : p == 1 ? "light" : "dark"));
        } else if (type == REQUEST_FOCUS) {
            return true;
        }
        return false;
    }

    private void withId(Map<String, Object> fields) {
        if (phoneId != null) fields.put("id", phoneId);
    }

    /** LIVI ui_sync.rs request_layout: the layout, its palette, the appearance and the map zone's component */
    static Map<String, Object> requestLayout(Layout l, long appearance) {
        Map<String, Object> active = new LinkedHashMap<String, Object>();
        active.put(l.zone, l.component);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("layoutID", l.id);
        data.put("paletteID", l.palette);
        data.put("appearanceMode", appearance);
        data.put("fadeInNewLayout", Boolean.TRUE);
        data.put("fadeOutOldLayout", Boolean.TRUE);
        data.put("activeComponents", active);
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("type", REQUEST_LAYOUT);
        c.put("data", data);
        return c;
    }

    static Map<String, Object> command(long type, String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put(key, value);
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("type", type);
        c.put("data", data);
        return c;
    }

    /** one of the car's messages: the header {type, ssn, psn, asn, extra..., data} in the panel's frame */
    byte[] message(long kind, Map<String, Object> data, Map<String, Object> extra) {
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("type", kind);
        header.put("ssn", session);
        header.put("psn", nextTx);
        header.put("asn", lastRx);
        if (extra != null) header.putAll(extra);
        if (data != null) header.put("data", Bplist.encode(data));
        nextTx++;
        Map<Object, Object> frame = new LinkedHashMap<Object, Object>(panel);
        frame.put("data", Bplist.encode(header));
        return OpackCodec.encode(frame);
    }

    /** a message for the log: the panel and the header's type, sequence numbers and command */
    @SuppressWarnings("unchecked")
    static String describe(byte[] message) {
        try {
            Map<Object, Object> frame = (Map<Object, Object>) OpackCodec.decode(message);
            Map<String, Object> header = (Map<String, Object>) Bplist.decode((byte[]) frame.get("data"));
            Object cmd = header.get("data") instanceof byte[] ? Bplist.decode((byte[]) header.get("data")) : null;
            Map<String, Object> shown = new LinkedHashMap<String, Object>(header);
            shown.remove("data");
            return message.length + " B for " + frame.get("displayUUID") + ": " + shown + (cmd != null ? " command " + cmd : "");
        } catch (RuntimeException e) {
            return message.length + " B " + IapChannel.hex(message, 0, Math.min(message.length, 64));
        }
    }
}
