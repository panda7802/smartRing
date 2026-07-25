#!/usr/bin/env python3
"""Small dependency-free decoder for Android btsnoop HCI logs.

It focuses on BLE scan/connect events and ATT/GATT traffic.  The output is
intended for protocol reconstruction, not as a replacement for Wireshark.
"""

from __future__ import annotations

import argparse
import datetime as dt
import struct
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path


BTSNOOP_EPOCH_US = 0x00DCDDB30F2F8000
TARGET_NAME = b"SQ666"
TARGET_ADDR = "41:42:2C:57:1F:13"

ATT_NAMES = {
    0x01: "Error Response",
    0x02: "Exchange MTU Request",
    0x03: "Exchange MTU Response",
    0x04: "Find Information Request",
    0x05: "Find Information Response",
    0x06: "Find By Type Value Request",
    0x07: "Find By Type Value Response",
    0x08: "Read By Type Request",
    0x09: "Read By Type Response",
    0x0A: "Read Request",
    0x0B: "Read Response",
    0x0C: "Read Blob Request",
    0x0D: "Read Blob Response",
    0x0E: "Read Multiple Request",
    0x0F: "Read Multiple Response",
    0x10: "Read By Group Type Request",
    0x11: "Read By Group Type Response",
    0x12: "Write Request",
    0x13: "Write Response",
    0x16: "Prepare Write Request",
    0x17: "Prepare Write Response",
    0x18: "Execute Write Request",
    0x19: "Execute Write Response",
    0x1B: "Handle Value Notification",
    0x1D: "Handle Value Indication",
    0x1E: "Handle Value Confirmation",
    0x20: "Read Multiple Variable Request",
    0x21: "Read Multiple Variable Response",
    0x23: "Multiple Handle Value Notification",
    0x52: "Write Command",
    0xD2: "Signed Write Command",
}

HCI_COMMANDS = {
    0x0406: "Disconnect",
    0x200B: "LE Set Scan Parameters",
    0x200C: "LE Set Scan Enable",
    0x200D: "LE Create Connection",
    0x200E: "LE Create Connection Cancel",
    0x2041: "LE Set Extended Scan Parameters",
    0x2042: "LE Set Extended Scan Enable",
    0x2043: "LE Extended Create Connection",
}


@dataclass
class Record:
    index: int
    timestamp: int
    flags: int
    original_length: int
    included_length: int
    packet: bytes

    @property
    def time(self) -> str:
        seconds = (self.timestamp - BTSNOOP_EPOCH_US) / 1_000_000
        # Android btsnoop encodes wall-clock fields without applying the local
        # timezone. utcfromtimestamp therefore matches bugreport logcat time.
        return dt.datetime.fromtimestamp(seconds, dt.UTC).strftime("%Y-%m-%d %H:%M:%S.%f")

    @property
    def direction(self) -> str:
        return "device→phone" if self.flags & 1 else "phone→device"


def u16(data: bytes, offset: int = 0) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def s8(value: int) -> int:
    return value - 256 if value > 127 else value


def address(raw: bytes) -> str:
    return ":".join(f"{byte:02X}" for byte in raw[::-1])


def uuid_text(raw_le: bytes) -> str:
    if len(raw_le) == 2:
        return f"0000{u16(raw_le):04X}-0000-1000-8000-00805F9B34FB"
    if len(raw_le) == 16:
        b = raw_le[::-1].hex().upper()
        return f"{b[0:8]}-{b[8:12]}-{b[12:16]}-{b[16:20]}-{b[20:32]}"
    return raw_le.hex().upper()


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{byte:02X}" for byte in data)


def read_records(path: Path) -> list[Record]:
    raw = path.read_bytes()
    if len(raw) < 16 or raw[:8] != b"btsnoop\x00":
        raise ValueError("not a btsnoop file")
    version, link_type = struct.unpack_from(">II", raw, 8)
    if version != 1 or link_type != 1002:
        raise ValueError(f"unsupported btsnoop version/link type: {version}/{link_type}")
    records: list[Record] = []
    offset = 16
    index = 1
    while offset + 24 <= len(raw):
        original_len, included_len, flags, _drops, timestamp = struct.unpack_from(
            ">IIIIQ", raw, offset
        )
        offset += 24
        packet = raw[offset : offset + included_len]
        offset += included_len
        records.append(Record(index, timestamp, flags, original_len, included_len, packet))
        index += 1
    if offset != len(raw):
        raise ValueError(f"trailing/truncated data at {offset}/{len(raw)}")
    return records


def parse_ad_structures(data: bytes) -> list[tuple[int, bytes]]:
    result = []
    offset = 0
    while offset < len(data):
        length = data[offset]
        if length == 0:
            break
        end = offset + 1 + length
        if end > len(data) or length < 1:
            break
        result.append((data[offset + 1], data[offset + 2 : end]))
        offset = end
    return result


def ad_summary(data: bytes) -> str:
    fields = []
    for ad_type, value in parse_ad_structures(data):
        if ad_type in (0x08, 0x09):
            fields.append(f"name={value.decode('utf-8', 'replace')!r}")
        elif ad_type in (0x02, 0x03):
            uuids = [uuid_text(value[i : i + 2]) for i in range(0, len(value) - 1, 2)]
            fields.append("uuid16=[" + ",".join(uuids) + "]")
        elif ad_type in (0x06, 0x07):
            uuids = [uuid_text(value[i : i + 16]) for i in range(0, len(value) - 15, 16)]
            fields.append("uuid128=[" + ",".join(uuids) + "]")
        elif ad_type == 0xFF:
            fields.append(f"manufacturer={hex_bytes(value)}")
        elif ad_type == 0x16:
            fields.append(f"service_data16={hex_bytes(value)}")
    return "; ".join(fields)


def iter_advertising_reports(record: Record):
    packet = record.packet
    if len(packet) < 5 or packet[0] != 0x04 or packet[1] != 0x3E:
        return
    params = packet[3:]
    if not params:
        return
    subevent = params[0]
    if subevent == 0x02 and len(params) >= 2:
        count = params[1]
        offset = 2
        for _ in range(count):
            if offset + 10 > len(params):
                return
            event_type = params[offset]
            addr_type = params[offset + 1]
            addr = address(params[offset + 2 : offset + 8])
            data_len = params[offset + 8]
            end = offset + 9 + data_len
            if end >= len(params):
                return
            data = params[offset + 9 : end]
            rssi = s8(params[end])
            yield {
                "kind": "legacy",
                "event_type": event_type,
                "addr_type": addr_type,
                "addr": addr,
                "data": data,
                "rssi": rssi,
            }
            offset = end + 1
    elif subevent == 0x0D and len(params) >= 2:
        count = params[1]
        offset = 2
        for _ in range(count):
            if offset + 24 > len(params):
                return
            event_type = u16(params, offset)
            addr_type = params[offset + 2]
            addr = address(params[offset + 3 : offset + 9])
            primary_phy = params[offset + 9]
            secondary_phy = params[offset + 10]
            sid = params[offset + 11]
            tx_power = s8(params[offset + 12])
            rssi = s8(params[offset + 13])
            periodic_interval = u16(params, offset + 14)
            direct_addr_type = params[offset + 16]
            direct_addr = address(params[offset + 17 : offset + 23])
            data_len = params[offset + 23]
            end = offset + 24 + data_len
            if end > len(params):
                return
            data = params[offset + 24 : end]
            yield {
                "kind": "extended",
                "event_type": event_type,
                "addr_type": addr_type,
                "addr": addr,
                "data": data,
                "rssi": rssi,
                "primary_phy": primary_phy,
                "secondary_phy": secondary_phy,
                "sid": sid,
                "tx_power": tx_power,
                "periodic_interval": periodic_interval,
                "direct_addr_type": direct_addr_type,
                "direct_addr": direct_addr,
            }
            offset = end


def connection_event(record: Record):
    p = record.packet
    if len(p) < 5 or p[0] != 0x04 or p[1] != 0x3E:
        return None
    q = p[3:]
    if not q:
        return None
    subevent = q[0]
    if subevent == 0x01 and len(q) >= 19:
        return {
            "subevent": "LE Connection Complete",
            "status": q[1],
            "handle": u16(q, 2),
            "role": q[4],
            "addr_type": q[5],
            "addr": address(q[6:12]),
            "interval": u16(q, 12) * 1.25,
            "latency": u16(q, 14),
            "timeout": u16(q, 16) * 10,
        }
    if subevent in (0x0A, 0x29) and len(q) >= 31:
        return {
            "subevent": "LE Enhanced Connection Complete",
            "status": q[1],
            "handle": u16(q, 2),
            "role": q[4],
            "addr_type": q[5],
            "addr": address(q[6:12]),
            "interval": u16(q, 24) * 1.25,
            "latency": u16(q, 26),
            "timeout": u16(q, 28) * 10,
        }
    return None


def parse_create_connection(packet: bytes):
    if len(packet) < 4 or packet[0] != 0x01:
        return None
    opcode = u16(packet, 1)
    params = packet[4:]
    if opcode == 0x200D and len(params) >= 12:
        return {
            "opcode": opcode,
            "scan_interval_ms": u16(params, 0) * 0.625,
            "scan_window_ms": u16(params, 2) * 0.625,
            "filter_policy": params[4],
            "peer_addr_type": params[5],
            "peer_addr": address(params[6:12]),
        }
    if opcode == 0x2043 and len(params) >= 10:
        result = {
            "opcode": opcode,
            "filter_policy": params[0],
            "own_addr_type": params[1],
            "peer_addr_type": params[2],
            "peer_addr": address(params[3:9]),
            "phys": params[9],
        }
        if len(params) >= 26:
            result["scan_interval_ms"] = u16(params, 10) * 0.625
            result["scan_window_ms"] = u16(params, 12) * 0.625
        return result
    return None


def scan_command(packet: bytes):
    if len(packet) < 4 or packet[0] != 0x01:
        return None
    opcode = u16(packet, 1)
    params = packet[4:]
    if opcode == 0x200B and len(params) >= 7:
        return (
            opcode,
            f"type={'active' if params[0] else 'passive'} "
            f"interval={u16(params, 1) * 0.625:g}ms "
            f"window={u16(params, 3) * 0.625:g}ms "
            f"own_addr_type={params[5]} filter_policy={params[6]}",
        )
    if opcode == 0x200C and len(params) >= 2:
        return opcode, f"enable={bool(params[0])} filter_duplicates={params[1]}"
    if opcode == 0x2041 and len(params) >= 3:
        details = [
            f"own_addr_type={params[0]}",
            f"filter_policy={params[1]}",
            f"phys=0x{params[2]:02X}",
        ]
        offset = 3
        for bit, phy_name in ((0, "1M"), (1, "2M"), (2, "Coded")):
            if params[2] & (1 << bit) and offset + 5 <= len(params):
                details.append(
                    f"{phy_name}(type={'active' if params[offset] else 'passive'},"
                    f"interval={u16(params, offset + 1) * 0.625:g}ms,"
                    f"window={u16(params, offset + 3) * 0.625:g}ms)"
                )
                offset += 5
        return opcode, " ".join(details)
    if opcode == 0x2042 and len(params) >= 6:
        return (
            opcode,
            f"enable={bool(params[0])} filter_duplicates={params[1]} "
            f"duration={u16(params, 2) * 10}ms period={u16(params, 4) * 1.28:g}s",
        )
    return None


def parse_att_records(records: list[Record]):
    pending: dict[tuple[int, int], bytearray] = {}
    expected: dict[tuple[int, int], int] = {}
    result = []
    for record in records:
        packet = record.packet
        if len(packet) < 5 or packet[0] != 0x02:
            continue
        handle_flags = u16(packet, 1)
        handle = handle_flags & 0x0FFF
        pb = (handle_flags >> 12) & 0x3
        acl_len = u16(packet, 3)
        acl = packet[5 : 5 + acl_len]
        direction_bit = record.flags & 1
        key = (direction_bit, handle)
        complete = None
        truncated = record.included_length < record.original_length
        if pb == 1:
            if key not in pending:
                continue
            pending[key].extend(acl)
            if len(pending[key]) >= expected[key]:
                complete = bytes(pending.pop(key)[: expected.pop(key)])
        elif len(acl) >= 4:
            l2_len = u16(acl, 0)
            total = 4 + l2_len
            if len(acl) >= total:
                complete = acl[:total]
            elif truncated:
                # Android's in-memory btsnooz privacy mode deliberately stores
                # only a prefix of ACL packets.  The L2CAP/ATT header and first
                # value bytes are still useful, even though no continuation
                # packet will follow.
                complete = acl
            else:
                pending[key] = bytearray(acl)
                expected[key] = total
        if complete is None or len(complete) < 5:
            continue
        l2_len = u16(complete, 0)
        cid = u16(complete, 2)
        payload = complete[4 : 4 + l2_len]
        if cid == 0x0004 and payload:
            result.append((record, handle, payload, truncated))
    return result


def decode_att(payload: bytes) -> str:
    opcode = payload[0]
    name = ATT_NAMES.get(opcode, f"ATT 0x{opcode:02X}")
    if opcode == 0x01 and len(payload) >= 5:
        return (
            f"{name}: request=0x{payload[1]:02X} handle=0x{u16(payload, 2):04X} "
            f"error=0x{payload[4]:02X}"
        )
    if opcode in (0x02, 0x03) and len(payload) >= 3:
        return f"{name}: mtu={u16(payload, 1)}"
    if opcode in (0x04, 0x08, 0x10) and len(payload) >= 5:
        detail = f"start=0x{u16(payload, 1):04X} end=0x{u16(payload, 3):04X}"
        if opcode in (0x08, 0x10) and len(payload) > 5:
            detail += f" type={uuid_text(payload[5:])}"
        return f"{name}: {detail}"
    if opcode == 0x06 and len(payload) >= 7:
        return (
            f"{name}: start=0x{u16(payload, 1):04X} end=0x{u16(payload, 3):04X} "
            f"type={uuid_text(payload[5:7])} value={hex_bytes(payload[7:])}"
        )
    if opcode in (0x0A, 0x0C) and len(payload) >= 3:
        detail = f"handle=0x{u16(payload, 1):04X}"
        if opcode == 0x0C and len(payload) >= 5:
            detail += f" offset={u16(payload, 3)}"
        return f"{name}: {detail}"
    if opcode in (0x12, 0x52, 0xD2) and len(payload) >= 3:
        return (
            f"{name}: handle=0x{u16(payload, 1):04X} "
            f"value=[{hex_bytes(payload[3:])}]"
        )
    if opcode in (0x1B, 0x1D) and len(payload) >= 3:
        return (
            f"{name}: handle=0x{u16(payload, 1):04X} "
            f"value=[{hex_bytes(payload[3:])}]"
        )
    return f"{name}: [{hex_bytes(payload[1:])}]"


def build_gatt_map(att_records):
    services = []
    characteristics = []
    descriptors = []
    cccd_writes = []
    for record, conn_handle, payload, _truncated in att_records:
        opcode = payload[0]
        if opcode == 0x11 and len(payload) >= 2:
            entry_len = payload[1]
            body = payload[2:]
            if entry_len >= 6:
                for offset in range(0, len(body) - entry_len + 1, entry_len):
                    entry = body[offset : offset + entry_len]
                    services.append(
                        {
                            "conn": conn_handle,
                            "start": u16(entry, 0),
                            "end": u16(entry, 2),
                            "uuid": uuid_text(entry[4:]),
                        }
                    )
        elif opcode == 0x09 and len(payload) >= 2:
            entry_len = payload[1]
            body = payload[2:]
            if entry_len >= 7:
                for offset in range(0, len(body) - entry_len + 1, entry_len):
                    entry = body[offset : offset + entry_len]
                    characteristics.append(
                        {
                            "conn": conn_handle,
                            "decl_handle": u16(entry, 0),
                            "properties": entry[2],
                            "value_handle": u16(entry, 3),
                            "uuid": uuid_text(entry[5:]),
                        }
                    )
        elif opcode == 0x05 and len(payload) >= 2:
            fmt = payload[1]
            entry_len = 4 if fmt == 1 else 18 if fmt == 2 else 0
            body = payload[2:]
            if entry_len:
                for offset in range(0, len(body) - entry_len + 1, entry_len):
                    entry = body[offset : offset + entry_len]
                    descriptors.append(
                        {
                            "conn": conn_handle,
                            "handle": u16(entry, 0),
                            "uuid": uuid_text(entry[2:]),
                        }
                    )
        elif opcode in (0x12, 0x52) and len(payload) >= 5:
            value = payload[3:]
            if value in (b"\x00\x00", b"\x01\x00", b"\x02\x00", b"\x03\x00"):
                cccd_writes.append(
                    {
                        "time": record.time,
                        "conn": conn_handle,
                        "handle": u16(payload, 1),
                        "value": u16(value),
                        "opcode": opcode,
                    }
                )
    return services, characteristics, descriptors, cccd_writes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("log", type=Path)
    parser.add_argument("--all-att", action="store_true", help="print all ATT control traffic too")
    args = parser.parse_args()

    records = read_records(args.log)
    print(f"records={len(records)}")
    print(f"range={records[0].time} .. {records[-1].time}")
    print(f"target={TARGET_NAME.decode()} {TARGET_ADDR}")

    print("\n=== Scan commands ===")
    scan_count = 0
    for record in records:
        info = scan_command(record.packet)
        if info:
            scan_count += 1
            opcode, detail = info
            print(f"{record.time} #{record.index} {HCI_COMMANDS[opcode]}: {detail}")
    if not scan_count:
        print("(none in retained ring-buffer interval)")

    print("\n=== SQ666 advertising reports ===")
    adv_count = 0
    for record in records:
        for report in iter_advertising_reports(record) or ():
            if report["addr"] == TARGET_ADDR or TARGET_NAME in report["data"]:
                adv_count += 1
                print(
                    f"{record.time} #{record.index} addr={report['addr']} "
                    f"type={report['addr_type']} rssi={report['rssi']} "
                    f"{ad_summary(report['data'])} raw=[{hex_bytes(report['data'])}]"
                )
    if not adv_count:
        print("(none)")

    print("\n=== Connection commands/events ===")
    target_handles = set()
    for record in records:
        create = parse_create_connection(record.packet)
        if create:
            name = HCI_COMMANDS[create.pop("opcode")]
            print(f"{record.time} #{record.index} {name}: {create}")
        event = connection_event(record)
        if event:
            if event["addr"] == TARGET_ADDR:
                target_handles.add(event["handle"])
            print(f"{record.time} #{record.index} {event}")
        p = record.packet
        if len(p) >= 7 and p[0] == 0x04 and p[1] == 0x05:
            print(
                f"{record.time} #{record.index} Disconnection Complete: "
                f"status={p[3]} handle=0x{u16(p, 4):04X} reason=0x{p[6]:02X}"
            )

    att_records = parse_att_records(records)
    target_att = [entry for entry in att_records if not target_handles or entry[1] in target_handles]
    counts = Counter(entry[2][0] for entry in target_att)
    print("\n=== ATT/GATT summary ===")
    print("target connection handles=" + ", ".join(f"0x{x:04X}" for x in sorted(target_handles)))
    for opcode, count in sorted(counts.items()):
        print(f"0x{opcode:02X} {ATT_NAMES.get(opcode, 'unknown')}: {count}")

    services, chars, descriptors, cccd_writes = build_gatt_map(target_att)
    print("\n=== Discovered services ===")
    if services:
        for item in services:
            print(
                f"conn=0x{item['conn']:04X} handles=0x{item['start']:04X}-0x{item['end']:04X} "
                f"uuid={item['uuid']}"
            )
    else:
        print("(no service-discovery response retained; likely cached)")

    print("\n=== Discovered characteristics ===")
    if chars:
        for item in chars:
            print(
                f"conn=0x{item['conn']:04X} decl=0x{item['decl_handle']:04X} "
                f"value=0x{item['value_handle']:04X} props=0x{item['properties']:02X} "
                f"uuid={item['uuid']}"
            )
    else:
        print("(no characteristic-discovery response retained; likely cached)")

    print("\n=== Discovered descriptors ===")
    if descriptors:
        for item in descriptors:
            print(
                f"conn=0x{item['conn']:04X} handle=0x{item['handle']:04X} uuid={item['uuid']}"
            )
    else:
        print("(none retained)")

    print("\n=== CCCD-like writes ===")
    if cccd_writes:
        for item in cccd_writes:
            meaning = {0: "disable", 1: "notify", 2: "indicate", 3: "notify+indicate"}[item["value"]]
            print(
                f"{item['time']} conn=0x{item['conn']:04X} handle=0x{item['handle']:04X} "
                f"value={item['value']:04X} ({meaning}) via 0x{item['opcode']:02X}"
            )
    else:
        print("(none)")

    print("\n=== Application ATT writes / notifications / indications ===")
    app_opcodes = {0x12, 0x52, 0xD2, 0x1B, 0x1D}
    for record, conn_handle, payload, truncated in target_att:
        opcode = payload[0]
        is_cccd_shape = opcode in (0x12, 0x52) and len(payload) == 5 and payload[3:] in (
            b"\x00\x00",
            b"\x01\x00",
            b"\x02\x00",
            b"\x03\x00",
        )
        if opcode in app_opcodes and not is_cccd_shape:
            print(
                f"{record.time} #{record.index} {record.direction} conn=0x{conn_handle:04X} "
                f"{decode_att(payload)}{' [TRUNCATED]' if truncated else ''}"
            )

    if args.all_att:
        print("\n=== All ATT traffic ===")
        for record, conn_handle, payload, truncated in target_att:
            print(
                f"{record.time} #{record.index} {record.direction} conn=0x{conn_handle:04X} "
                f"{decode_att(payload)}{' [TRUNCATED]' if truncated else ''}"
            )


if __name__ == "__main__":
    main()
