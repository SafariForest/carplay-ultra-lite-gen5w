// Prints LIVI's vehicleStateProtocolInfo (GPL-3.0-or-later, see LICENSE/NOTICE) as a binary plist,
// or with --export <dir> what cp-climate's Java side answers from (LIVI's own OPACK).
#![allow(dead_code, unused_imports)]
mod bplist;
mod image_archive;
mod opack;
mod vehicle_state;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let climate = args.iter().any(|a| a == "--climate");
    let state = vehicle_state::VehicleState::new(false, "0.1.0", "2026-11-07 00:00").with_climate(climate);
    if let Some(i) = args.iter().position(|a| a == "--export") {
        let dir = std::path::Path::new(&args[i + 1]);
        let (acc, values, writable, controls) = state.cpc_export();
        std::fs::write(dir.join("vs-accessories.opack"), acc).unwrap();
        std::fs::write(dir.join("vs-values.opack"), values).unwrap();
        let w: Vec<String> = writable.iter().map(|i| i.to_string()).collect();
        let c: Vec<String> = controls.iter().map(|(i, n)| format!("{i}={n}")).collect();
        let (names, shared, notifiers) = state.cpc_meta();
        let n: Vec<String> = names.iter().map(|(i, n)| format!("{i}={n}")).collect();
        let s: Vec<String> = shared.iter().map(|i| i.to_string()).collect();
        let f: Vec<String> = notifiers.iter().map(|(i, n)| format!("{i}={n}")).collect();
        std::fs::write(
            dir.join("vs-meta.txt"),
            format!(
                "writable {}\ncontrols {}\nshared {}\nnotifiers {}\nnames {}\n",
                w.join(" "), c.join(" "), s.join(" "), f.join(" "), n.join(" ")
            ),
        )
        .unwrap();
        return;
    }
    if let Some(i) = args.iter().position(|a| a == "--replay") {
        // --replay <in> <out>: records of lane (0 normal, 1 high), u32 LE length, message; the answers
        // go to <out> the same way, each message's ended by lane 0xff (stdout has LIVI's log)
        let input = std::fs::read(&args[i + 1]).unwrap();
        let mut state = state;
        let mut out = Vec::new();
        let mut at = 0;
        while at + 5 <= input.len() {
            let lane = if input[at] == 1 { vehicle_state::Lane::High } else { vehicle_state::Lane::Normal };
            let n = u32::from_le_bytes(input[at + 1..at + 5].try_into().unwrap()) as usize;
            let message = &input[at + 5..at + 5 + n];
            at += 5 + n;
            for (l, answer) in state.handle(lane, message) {
                out.push(u8::from(l == vehicle_state::Lane::High));
                out.extend((answer.len() as u32).to_le_bytes());
                out.extend(answer);
            }
            out.extend([0xff, 0, 0, 0, 0]); // end of this message's answers
        }
        std::fs::write(&args[i + 2], out).unwrap();
        return;
    }
    let (key, value) = state.info_entry();
    eprintln!("key {key}");
    let bytes = bplist::encode(&value);
    std::io::Write::write_all(&mut std::io::stdout(), &bytes).unwrap();
}
