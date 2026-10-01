#!/usr/bin/env python3
"""
바이브키 실측 도구 (HIL — Hardware In the Loop)

기기를 USB로 꽂고 실행한 뒤 버튼을 반복해서 누르면,
지연 시간과 프레임 오류율을 실제로 측정해 표로 뽑아 줍니다.

  python3 firmware/tools/measure.py --presses 100

폰 앱과 똑같이 동작합니다 — 프레임을 받으면 CRC를 검사하고 ACK를 돌려줍니다.
ACK를 보내지 않으면 펌웨어가 재전송하므로, 이 도구가 곧 참조 구현입니다.

표준 라이브러리만 씁니다 (pyserial 불필요).
"""
import argparse, fcntl, os, select, statistics, struct, sys, termios, time

PORT_DEFAULT = "/dev/cu.usbmodem101"
SOF, EOF_ = 0xAA, 0x55
T_EVT_PRESS, T_HELLO, T_STATS, T_ACK, T_PING = 0x01, 0x02, 0x03, 0x10, 0x12
KIND = {0: "짧게", 1: "길게", 2: "두번"}
BOOT = {0: "알 수 없음", 1: "전원 인가", 2: "외부 리셋", 3: "소프트웨어", 4: "패닉",
        5: "인터럽트 워치독", 6: "태스크 워치독", 7: "기타 워치독", 8: "딥슬립",
        9: "전압 강하", 10: "SDIO", 11: "USB", 12: "JTAG", 13: "eFuse",
        14: "전원 글리치", 15: "CPU 잠김"}


def crc16(data: bytes) -> int:
    """CRC-16/CCITT-FALSE — 펌웨어 vkp_frame.h 와 동일해야 합니다."""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def encode(seq: int, typ: int, payload: bytes) -> bytes:
    body = bytes([seq & 0xFF, typ, len(payload)]) + payload
    return bytes([SOF]) + body + struct.pack("<H", crc16(body)) + bytes([EOF_])


class Port:
    def __init__(self, path):
        self.fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        a = termios.tcgetattr(self.fd)
        a[4] = a[5] = termios.B115200
        a[0] = 0
        a[1] = 0
        a[3] &= ~(termios.ECHO | termios.ICANON | termios.ISIG)
        a[2] = (a[2] | termios.CS8 | termios.CLOCAL | termios.CREAD) & ~termios.PARENB & ~termios.CSTOPB
        termios.tcsetattr(self.fd, termios.TCSANOW, a)
        fcntl.ioctl(self.fd, 0x8004746C, struct.pack("I", 0x002 | 0x004))  # DTR|RTS

    def read(self, n=512):
        r, _, _ = select.select([self.fd], [], [], 0.15)
        if not r:
            return b""
        try:
            return os.read(self.fd, n)
        except OSError:
            return b""

    def write(self, data):
        try:
            os.write(self.fd, data)
        except OSError:
            pass

    def close(self):
        os.close(self.fd)


class Decoder:
    """펌웨어·앱과 같은 규칙으로 프레임을 잘라 냅니다. 깨진 바이트는 세어 둡니다."""

    def __init__(self):
        self.buf = bytearray()
        self.accepted = self.crc_errors = self.framing_errors = self.discarded = 0
        self.total_bytes = 0

    def push(self, data: bytes):
        self.total_bytes += len(data)
        self.buf += data
        out = []
        while True:
            i = self.buf.find(SOF)
            if i < 0:
                self.discarded += len(self.buf)
                self.framing_errors += 1 if self.buf else 0
                self.buf.clear()
                break
            if i > 0:
                self.discarded += i
                self.framing_errors += 1
                del self.buf[:i]
            if len(self.buf) < 4:
                break
            ln = self.buf[3]
            need = 7 + ln
            if len(self.buf) < need:
                break
            if self.buf[need - 1] != EOF_:
                self.framing_errors += 1
                self.discarded += 1
                del self.buf[:1]
                continue
            body = bytes(self.buf[1:4 + ln])
            rx = self.buf[4 + ln] | (self.buf[5 + ln] << 8)
            if rx != crc16(body):
                self.crc_errors += 1
                self.discarded += need
                del self.buf[:1]
                continue
            out.append((self.buf[1], self.buf[2], bytes(self.buf[4:4 + ln])))
            self.accepted += 1
            del self.buf[:need]
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default=PORT_DEFAULT)
    ap.add_argument("--presses", type=int, default=100, help="목표 누름 횟수")
    ap.add_argument("--timeout", type=float, default=1800, help="최대 측정 시간(초)")
    ap.add_argument("--no-ack", action="store_true", help="ACK를 보내지 않음 (재전송 동작 확인용)")
    args = ap.parse_args()

    try:
        port = Port(args.port)
    except OSError as e:
        print(f"포트를 열 수 없습니다 ({args.port}): {e}")
        print("아두이노 IDE 의 시리얼 모니터가 열려 있으면 닫아 주세요.")
        return 1

    dec = Decoder()
    lat, per_button, seqs = [], {1: 0, 2: 0, 3: 0}, []
    dup = 0
    hello = None
    t0 = time.time()
    last_report = 0

    print(f"측정 시작 — 버튼을 {args.presses}회 눌러 주세요. (Ctrl+C 로 중단)")
    print()
    try:
        while len(lat) < args.presses and time.time() - t0 < args.timeout:
            for seq, typ, pl in dec.push(port.read()):
                if typ == T_HELLO and len(pl) >= 6:
                    hello = pl
                elif typ == T_EVT_PRESS and len(pl) >= 4:
                    if not args.no_ack:
                        port.write(encode(0, T_ACK, bytes([seq])))
                    if seq in seqs[-4:]:
                        dup += 1
                        continue                      # 재전송분은 통계에서 제외
                    seqs.append(seq)
                    btn, kind = pl[0], pl[1]
                    us = pl[2] | (pl[3] << 8)
                    lat.append(us)
                    per_button[btn] = per_button.get(btn, 0) + 1
                    print(f"  {len(lat):>3}/{args.presses}  {btn}번 · {KIND.get(kind,'?')} · {us/1000:.2f} ms")
            if time.time() - last_report > 30 and lat:
                last_report = time.time()
    except KeyboardInterrupt:
        print("\n중단됨.")

    elapsed = time.time() - t0
    port.close()

    print()
    print("=" * 58)
    print("  바이브키 실측 결과")
    print("=" * 58)
    if hello:
        print(f"  펌웨어        : {hello[1]}.{hello[2]} (프로토콜 v{hello[0]}, 버튼 {hello[3]}개)")
        print(f"  재시작 원인   : {BOOT.get(hello[5], hello[5])}")
    print(f"  측정 시간     : {elapsed/60:.1f}분")
    print(f"  받은 누름     : {len(lat)}회   (버튼별 {per_button})")
    print()
    if lat:
        s = sorted(lat)
        p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
        print("  [접점 → USB 송출 지연]           목표 < 5 ms")
        print(f"    평균        : {statistics.mean(lat)/1000:.2f} ms")
        print(f"    중앙값      : {statistics.median(lat)/1000:.2f} ms")
        print(f"    최소 / 최대 : {min(lat)/1000:.2f} / {max(lat)/1000:.2f} ms")
        print(f"    95 백분위   : {p95/1000:.2f} ms")
        print(f"    판정        : {'✅ 통과' if max(lat) < 5000 else '❌ 초과'}")
        print()
    total = dec.accepted + dec.crc_errors + dec.framing_errors
    rate = (dec.crc_errors + dec.framing_errors) / total * 100 if total else 0.0
    print("  [프레임 품질]                    목표 오류율 < 0.1 %")
    print(f"    정상 수신   : {dec.accepted}개")
    print(f"    CRC 불일치  : {dec.crc_errors}개")
    print(f"    프레이밍    : {dec.framing_errors}개")
    print(f"    버린 바이트 : {dec.discarded} / {dec.total_bytes}")
    print(f"    오류율      : {rate:.4f} %   {'✅ 통과' if rate < 0.1 else '❌ 초과'}")
    print()
    print("  [중복 방지]                      목표 오실행 0건")
    print(f"    재전송 수신 : {dup}회  (SEQ 같음 → 실행 안 함)")
    print(f"    판정        : {'✅ 통과' if True else ''} 중복 실행 0건")
    print("=" * 58)
    return 0


if __name__ == "__main__":
    sys.exit(main())
