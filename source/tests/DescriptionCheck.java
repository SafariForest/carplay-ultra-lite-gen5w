package ioniq6.cpclimate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** throwaway host check of VehicleState's description edits (media source, ambient test, extra seat and wheel controls) */
public class DescriptionCheck {
    static int fails;

    static void check(String what, boolean ok) {
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> service(Object list, long iid) {
        for (Object a : (List<Object>) list)
            for (Object s : (List<Object>) ((Map<Object, Object>) a).get("services"))
                if (Long.valueOf(iid).equals(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")))) return (Map<Object, Object>) s;
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> characteristic(Map<Object, Object> svc, long iid) {
        for (Object c : (List<Object>) svc.get("characteristics"))
            if (Long.valueOf(iid).equals(OpackCodec.asLong(((Map<Object, Object>) c).get("iid")))) return (Map<Object, Object>) c;
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        byte[] stock = VsData.bytes(VsData.ACCESSORIES);
        Map<Long, Object> values = new TreeMap<Long, Object>();

        // media: the current source is empty in the description
        byte[] media = VehicleState.withoutCurrentSource(stock);
        Object ml = OpackCodec.decode(media);
        Map<Object, Object> np = service(ml, 4353);
        check("nowPlaying exists", np != null);
        check("currentMediaSource (4354) initial value is empty", np != null && "".equals(characteristic(np, 4354).get("initialValue")));
        Object stockList = OpackCodec.decode(stock);
        check("stock description said DAB", "DAB".equals(characteristic(service(stockList, 4353), 4354).get("initialValue")));

        // ambient test: one more settings line, its value shared and writable
        byte[] amb = VehicleState.withAmbientTest(stock, "#FF030A", values);
        check("ambient test: description built", amb != null);
        Object al = OpackCodec.decode(amb);
        Map<Object, Object> line = service(al, VehicleState.AMBIENT_LINE);
        check("ambient line exists at iid 1305", line != null);
        Map<Object, Object> val = characteristic(line, VehicleState.AMBIENT_VALUE);
        check("ambient value 1308: shared, writable, mutable, #FF030A", val != null && Boolean.TRUE.equals(val.get("writable"))
                && Boolean.TRUE.equals(val.get("oemWritable")) && Boolean.TRUE.equals(val.get("mutable")) && "#FF030A".equals(val.get("initialValue")));
        Map<Object, Object> version = service(al, VehicleState.VERSION_LINE);
        check("the version line is unchanged and read-only", Boolean.FALSE.equals(characteristic(version, VehicleState.VERSION_LINE + 3).get("writable")));
        check("ambient values were put in the value map", "#FF030A".equals(values.get(VehicleState.AMBIENT_VALUE)) && "AmbientColor".equals(values.get(1306L)));
        check("ambient symbol and sort order", "lightbulb".equals(values.get(1310L)) && Long.valueOf(2).equals(values.get(1311L)));

        // extras: rear seats, ventilation, steering wheel
        Map<Long, Object> ev = new TreeMap<Long, Object>();
        byte[] extra = VehicleState.withExtras(stock, ev);
        check("extras: description built", extra != null);
        Object el = OpackCodec.decode(extra);
        Map<Object, Object> rl = service(el, 1033), rr = service(el, 1037), dv = service(el, 1041), pv = service(el, 1045), wh = service(el, 571);
        check("rear seat services exist, kind 0x22000001", rl != null && rr != null && Long.valueOf(0x22000001L).equals(OpackCodec.asLong(rl.get("type"))));
        check("rear left layout key seat_2ndRow_left, right seat_2ndRow_right", "seat_2ndRow_left".equals(characteristic(rl, 1036).get("initialValue"))
                && "seat_2ndRow_right".equals(characteristic(rr, 1040).get("initialValue")));
        check("rear levels 0-2, int32, type 0x31000021", Long.valueOf(2).equals(OpackCodec.asLong(characteristic(rl, 1034).get("maximumValue")))
                && Long.valueOf(7).equals(OpackCodec.asLong(characteristic(rl, 1034).get("format")))
                && Long.valueOf(0x31000021L).equals(OpackCodec.asLong(characteristic(rl, 1034).get("type"))));
        check("rear level and on are writable (shared)", Boolean.TRUE.equals(characteristic(rl, 1034).get("writable")) && Boolean.TRUE.equals(characteristic(rl, 1035).get("writable")));
        check("ventilation services: kind 0x22000002, FanLevel 0x31000012, uint8, 0-3", dv != null && pv != null
                && Long.valueOf(0x22000002L).equals(OpackCodec.asLong(dv.get("type")))
                && Long.valueOf(0x31000012L).equals(OpackCodec.asLong(characteristic(dv, 1042).get("type")))
                && Long.valueOf(1).equals(OpackCodec.asLong(characteristic(dv, 1042).get("format")))
                && Long.valueOf(3).equals(OpackCodec.asLong(characteristic(dv, 1042).get("maximumValue"))));
        check("ventilation on (1043) keeps the on type 0x30000002", Long.valueOf(0x30000002L).equals(OpackCodec.asLong(characteristic(dv, 1043).get("type"))));
        check("ventilation layout keys seat_front_left / seat_front_right", "seat_front_left".equals(characteristic(dv, 1044).get("initialValue"))
                && "seat_front_right".equals(characteristic(pv, 1048).get("initialValue")));
        check("steering wheel service 571 kind 0x11000003 with one level (572), 0-1", wh != null && Long.valueOf(0x11000003L).equals(OpackCodec.asLong(wh.get("type")))
                && ((List<Object>) wh.get("characteristics")).size() == 1 && Long.valueOf(1).equals(OpackCodec.asLong(characteristic(wh, 572).get("maximumValue")))
                && Long.valueOf(0x31000021L).equals(OpackCodec.asLong(characteristic(wh, 572).get("type"))));
        check("the front seat heating service 1025 is unchanged", Long.valueOf(0x22000001L).equals(OpackCodec.asLong(service(el, 1025).get("type")))
                && Long.valueOf(3).equals(OpackCodec.asLong(characteristic(service(el, 1025), 1026).get("maximumValue"))));
        check("extras put 13 values in the value map (4 seat services x level, on, layout key + the wheel level), got " + ev.size(), ev.size() == 13);

        // all of it together: iids stay unique, and encoding is stable
        byte[] all = VehicleState.withExtras(VehicleState.withAmbientTest(VehicleState.withoutCurrentSource(stock), "#FFFFFF", new TreeMap<Long, Object>()),
                new TreeMap<Long, Object>());
        Set<Long> seen = new HashSet<Long>();
        boolean unique = true;
        for (Object a : (List<Object>) OpackCodec.decode(all)) {
            for (Object s : (List<Object>) ((Map<Object, Object>) a).get("services")) {
                unique &= seen.add(OpackCodec.asLong(((Map<Object, Object>) s).get("iid")));
                for (Object c : (List<Object>) ((Map<Object, Object>) s).get("characteristics"))
                    unique &= seen.add(OpackCodec.asLong(((Map<Object, Object>) c).get("iid")));
            }
        }
        check("every service and characteristic iid is unique with everything on (" + seen.size() + " iids)", unique);
        check("the description decodes and re-encodes to the same bytes", java.util.Arrays.equals(all, OpackCodec.encode(OpackCodec.decode(all))));
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
    }
}
