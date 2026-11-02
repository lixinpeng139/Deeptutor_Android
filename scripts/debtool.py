"""Small helpers for Termux .deb packages and ELF dynamic sections.

usage:
  python debtool.py extract <file.deb> <out_dir>
  python debtool.py needed <elf>
"""

import io
import lzma
import struct
import sys
import tarfile
from pathlib import Path


def ar_members(data: bytes):
    if data[:8] != b"!<arch>\n":
        raise ValueError("not an ar archive")
    pos = 8
    while pos < len(data):
        header = data[pos : pos + 60]
        name = header[:16].decode().strip().rstrip("/")
        size = int(header[48:58].decode().strip())
        pos += 60
        yield name, data[pos : pos + size]
        pos += size + (size & 1)


def extract(deb: str, out: str) -> None:
    for name, body in ar_members(Path(deb).read_bytes()):
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            body = lzma.decompress(body)
        with tarfile.open(fileobj=io.BytesIO(body)) as tar:
            tar.extractall(out, filter="tar")
        return
    raise ValueError("no data.tar in deb")


def dynamic_strings(path: str) -> dict[str, list[str]]:
    data = Path(path).read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError("expected 64-bit ELF")
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum = struct.unpack_from("<HH", data, 0x3A)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type, = struct.unpack_from("<I", data, off + 4)
        sh_offset, sh_size = struct.unpack_from("<QQ", data, off + 0x18)
        sh_link, = struct.unpack_from("<I", data, off + 0x28)
        sections.append((sh_type, sh_offset, sh_size, sh_link))
    result: dict[str, list[str]] = {"NEEDED": [], "SONAME": [], "RUNPATH": [], "RPATH": []}
    tags = {1: "NEEDED", 14: "SONAME", 29: "RUNPATH", 15: "RPATH"}
    for sh_type, off, size, link in sections:
        if sh_type != 6:  # SHT_DYNAMIC
            continue
        strtab = sections[link][1]
        for pos in range(off, off + size, 16):
            tag, val = struct.unpack_from("<qQ", data, pos)
            if tag == 0:
                break
            if tag in tags:
                end = data.index(b"\0", strtab + val)
                result[tags[tag]].append(data[strtab + val : end].decode())
    return result


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "extract":
        extract(sys.argv[2], sys.argv[3])
    elif cmd == "needed":
        print(dynamic_strings(sys.argv[2]))
    else:
        raise SystemExit(__doc__)
