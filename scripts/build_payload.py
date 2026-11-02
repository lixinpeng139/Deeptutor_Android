"""Assemble the Linux guest filesystem that the Android app runs DeepTutor in.

The archive is streamed straight from the source archives into one GNU tar so
nothing is unpacked on the Windows build machine (case-insensitive NTFS cannot
hold the Ubuntu or terminfo trees, and symlinks would be lost).

Layout of the guest root:
  /                         Ubuntu base (glibc userland)
  /opt/python               python-build-standalone CPython + site-packages
  /opt/node/bin/node        Node.js for the Next.js standalone server
  /opt/deeptutor/core       DeepTutor Python sources (a source checkout)
  /opt/deeptutor/web        Next.js standalone build
  /opt/deeptutor/tiktoken   pre-fetched tiktoken encodings
  /opt/deeptutor/start.sh   launcher run by the Android service

Every .py file gets an unchecked-hash .pyc so the first start does not have to
compile tens of thousands of modules under ptrace.

usage: python build_payload.py --abi aarch64|x86_64 --out <dir>
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
import lzma
import multiprocessing
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tarfile
import time
from importlib._bootstrap_external import _code_to_hash_pyc  # noqa: PLC2701

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
DL = BUILD / "dl"
SOURCE = Path(os.environ.get("DEEPTUTOR_SOURCE", ROOT.parent / "DeepTutor-main"))
WEB_STANDALONE = BUILD / "src" / "web" / ".next" / "standalone"
PY_VER = "3.12"
SITE = f"opt/python/lib/python{PY_VER}/site-packages"

ABIS = {
    "aarch64": {"ubuntu": "ubuntu-arm64.tar.gz", "python": "py-aarch64.tar.gz",
                "node": "node-arm64.tar.xz", "sharp": "linux-arm64"},
    "x86_64": {"ubuntu": "ubuntu-amd64.tar.gz", "python": "py-x86_64.tar.gz",
               "node": "node-x64.tar.xz", "sharp": "linux-x64"},
}

# Directories that are never needed at run time inside the phone.
SKIP_SITE_TOP = {"bin"}  # Windows console-script launchers from the cross install
SKIP_SITE_PREFIXES = ("litellm/proxy/_experimental/",)  # the LiteLLM proxy admin UI
CORE_ITEMS = ["deeptutor", "deeptutor_cli", "scripts", "requirements", "pyproject.toml",
              "build_metadata.py", "build_support.py", "setup.py", "LICENSE",
              "THIRD_PARTY_NOTICES.md"]


def compile_one(job: tuple[str, bytes]) -> tuple[str, bytes | None]:
    """Return (guest path of the .py, unchecked-hash pyc bytes or None)."""
    guest_path, source = job
    try:
        code = compile(source, "/" + guest_path, "exec", dont_inherit=True)
    except (SyntaxError, ValueError):
        return guest_path, None
    data = _code_to_hash_pyc(code, importlib.util.source_hash(source), checked=False)
    return guest_path, bytes(data)


def pyc_name(guest_py: str) -> str:
    p = PurePosixPath(guest_py)
    return str(p.parent / "__pycache__" / f"{p.stem}.{sys.implementation.cache_tag}.pyc")


class Payload:
    def __init__(self, path: Path):
        self.tar = tarfile.open(path, "w:gz", format=tarfile.GNU_FORMAT, compresslevel=6)
        self.names: set[str] = set()
        self.pending: list[tuple[str, bytes]] = []
        self.count = 0
        self.size = 0

    def add(self, info: tarfile.TarInfo, data: bytes | None = None) -> None:
        name = info.name
        if name in self.names:
            return
        self.names.add(name)
        info.uid = info.gid = 0
        info.uname = info.gname = "root"
        if data is not None:
            info.size = len(data)
            self.tar.addfile(info, io.BytesIO(data))
            self.size += len(data)
        else:
            self.tar.addfile(info)
        self.count += 1
        if info.isfile() and name.endswith(".py") and data is not None:
            self.pending.append((name, data))

    def file(self, name: str, data: bytes, mode: int = 0o644, mtime: float | None = None) -> None:
        info = tarfile.TarInfo(name)
        info.mode = mode
        info.mtime = int(mtime if mtime is not None else time.time())
        self.add(info, data)

    def directory(self, name: str, mode: int = 0o755) -> None:
        info = tarfile.TarInfo(name)
        info.type = tarfile.DIRTYPE
        info.mode = mode
        info.mtime = int(time.time())
        self.add(info)

    def symlink(self, name: str, target: str) -> None:
        info = tarfile.TarInfo(name)
        info.type = tarfile.SYMTYPE
        info.linkname = target
        info.mode = 0o777
        info.mtime = int(time.time())
        self.add(info)

    def flush_pyc(self, pool) -> None:
        jobs, self.pending = self.pending, []
        done = 0
        for guest_py, pyc in pool.imap_unordered(compile_one, jobs, chunksize=64):
            if pyc is not None:
                self.file(pyc_name(guest_py), pyc)
                done += 1
        print(f"  compiled {done}/{len(jobs)} modules", flush=True)

    def close(self) -> None:
        self.tar.close()


def norm(name: str) -> str:
    while name.startswith("./"):
        name = name[2:]
    return name.rstrip("/")


def copy_tar(payload: Payload, archive: Path, remap, opener=tarfile.open) -> None:
    """Copy members of a source tar, renaming them with remap(name) -> new name | None."""
    with opener(archive) as src:
        for member in src:
            name = remap(norm(member.name))
            if not name:
                continue
            if member.ischr() or member.isblk() or member.isfifo():
                continue
            info = tarfile.TarInfo(name)
            info.mode = member.mode
            info.mtime = member.mtime
            info.type = member.type
            if member.issym():
                info.linkname = member.linkname
            elif member.islnk():
                target = remap(norm(member.linkname))
                if not target:
                    continue
                info.linkname = target
            data = src.extractfile(member).read() if member.isfile() else None
            payload.add(info, data)


def copy_dir(payload: Payload, src: Path, dest: str, skip=lambda rel: False) -> None:
    for dirpath, dirnames, filenames in os.walk(src):
        rel_dir = Path(dirpath).relative_to(src).as_posix()
        rel_dir = "" if rel_dir == "." else rel_dir
        keep = []
        for d in sorted(dirnames):
            rel = f"{rel_dir}/{d}" if rel_dir else d
            if d == "__pycache__" or skip(rel + "/"):
                continue
            keep.append(d)
            payload.directory(f"{dest}/{rel}")
        dirnames[:] = keep
        for f in sorted(filenames):
            rel = f"{rel_dir}/{f}" if rel_dir else f
            if skip(rel) or f.endswith((".pyc", ".exe")):
                continue
            path = Path(dirpath) / f
            st = path.stat()
            mode = 0o755 if f.endswith((".sh", ".so")) or ".so." in f else 0o644
            payload.file(f"{dest}/{rel}", path.read_bytes(), mode, st.st_mtime)


def add_sharp(payload: Payload, plat: str) -> None:
    base = "opt/deeptutor/web/node_modules/@img"
    for pkg in (f"sharp-{plat}", f"sharp-libvips-{plat}"):
        copy_tar(payload, DL / f"{pkg}.tgz",
                 lambda n, pkg=pkg: f"{base}/{pkg}/{n[len('package/'):]}" if n.startswith("package/") else None)


def build(abi: str, out: Path) -> None:
    cfg = ABIS[abi]
    out.mkdir(parents=True, exist_ok=True)
    tar_path = out / "payload.tgz"
    payload = Payload(tar_path)
    start = time.time()
    with multiprocessing.Pool() as pool:
        print("ubuntu base", flush=True)
        copy_tar(payload, DL / cfg["ubuntu"], lambda n: n or None)
        payload.flush_pyc(pool)

        print("python runtime", flush=True)

        stdlib = f"lib/python{PY_VER}/"
        # The CPython test suite and GUI toolkits are large and never imported.
        unused = tuple(stdlib + d for d in ("test", "idlelib", "tkinter", "turtledemo", "ensurepip"))

        def py_remap(n: str) -> str | None:
            if n == "python":
                return "opt/python"
            if not n.startswith("python/"):
                return None
            rest = n[len("python/"):]
            if rest.startswith(("include/", "share/")):
                return None
            if any(rest == u or rest.startswith(u + "/") for u in unused):
                return None
            return f"opt/python/{rest}"

        copy_tar(payload, DL / cfg["python"], py_remap)
        payload.flush_pyc(pool)

        print("site-packages", flush=True)

        def site_skip(rel: str) -> bool:
            top = rel.split("/", 1)[0]
            return top in SKIP_SITE_TOP or rel.startswith(SKIP_SITE_PREFIXES)

        copy_dir(payload, BUILD / f"site-{abi}", SITE, site_skip)
        payload.flush_pyc(pool)

        print("node", flush=True)

        def node_remap(n: str) -> str | None:
            parts = n.split("/", 1)
            if len(parts) < 2:
                return "opt/node"
            rest = parts[1]
            if rest in ("bin", "bin/node", "LICENSE"):
                return f"opt/node/{rest}"
            return None

        copy_tar(payload, DL / cfg["node"], node_remap,
                 opener=lambda p: tarfile.open(fileobj=lzma.open(p)))

        print("deeptutor core", flush=True)
        payload.directory("opt/deeptutor")
        payload.directory("opt/deeptutor/core")
        for item in CORE_ITEMS:
            src = SOURCE / item
            if src.is_dir():
                payload.directory(f"opt/deeptutor/core/{item}")
                copy_dir(payload, src, f"opt/deeptutor/core/{item}")
            elif src.is_file():
                payload.file(f"opt/deeptutor/core/{item}", src.read_bytes(), 0o644, src.stat().st_mtime)
        payload.flush_pyc(pool)

        print("web", flush=True)
        payload.directory("opt/deeptutor/web")
        copy_dir(payload, WEB_STANDALONE, "opt/deeptutor/web",
                 lambda rel: rel.startswith("node_modules/@img/") and "win32" in rel)
        add_sharp(payload, cfg["sharp"])
        payload.pending.clear()

        print("support files", flush=True)
        payload.directory("opt/deeptutor/tiktoken")
        for f in DL.glob("tiktoken-*"):
            payload.file(f"opt/deeptutor/tiktoken/{f.name[len('tiktoken-'):]}", f.read_bytes())
        start_sh = (ROOT / "scripts" / "runtime" / "start.sh").read_bytes().replace(b"\r\n", b"\n")
        payload.file("opt/deeptutor/start.sh", start_sh, 0o755)
        cacert = (BUILD / f"site-{abi}" / "certifi" / "cacert.pem").read_bytes()
        payload.directory("etc/ssl/certs")
        payload.file("etc/ssl/certs/ca-certificates.crt", cacert)
        payload.file("etc/ssl/cert.pem", cacert)
        payload.directory("home/deeptutor")
        payload.directory("home/deeptutor/workspace")
        payload.directory("dev/shm", 0o1777)
        # Android hides these from apps; proot binds the fakes over /proc.
        payload.directory("opt/deeptutor/proc")
        for name in ("stat", "loadavg", "uptime", "version", "vmstat"):
            fake = (ROOT / "scripts" / "runtime" / "proc" / name).read_bytes().replace(b"\r\n", b"\n")
            payload.file(f"opt/deeptutor/proc/{name}", fake)
        version = (SOURCE / "deeptutor" / "__version__.py").read_text(encoding="utf-8")
        payload.file("opt/deeptutor/VERSION", version.encode())
    payload.close()

    digest = hashlib.sha256()
    with open(tar_path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            digest.update(block)
    meta = {
        "abi": abi,
        "version": digest.hexdigest()[:16],
        "entries": payload.count,
        "uncompressed": payload.size,
        "compressed": tar_path.stat().st_size,
        "built": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    (out / "payload.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta), f"in {time.time() - start:.0f}s", flush=True)


def patch_soname(data: bytes, old: bytes, new: bytes) -> bytes:
    assert len(new) <= len(old)
    needle = old + b"\0"
    if needle not in data:
        raise ValueError(f"{old!r} not found")
    return data.replace(needle, new + b"\0" * (len(old) - len(new) + 1))


def stage_proot(abi: str, out: Path) -> None:
    """Copy Termux's proot as jniLibs: the only place Android lets an app execute files."""
    usr = BUILD / f"deb-{abi}" / "data" / "data" / "com.termux" / "files" / "usr"
    out.mkdir(parents=True, exist_ok=True)
    proot = patch_soname((usr / "bin" / "proot").read_bytes(), b"libtalloc.so.2", b"libtalloc.so")
    talloc = patch_soname((usr / "lib" / "libtalloc.so.2.5.0").read_bytes(), b"libtalloc.so.2", b"libtalloc.so")
    (out / "libproot.so").write_bytes(proot)
    (out / "libtalloc.so").write_bytes(talloc)
    shutil.copyfile(usr / "lib" / "libandroid-shmem.so", out / "libandroid-shmem.so")
    shutil.copyfile(usr / "libexec" / "proot" / "loader", out / "libproot-loader.so")
    for f in out.iterdir():
        f.chmod(f.stat().st_mode | stat.S_IEXEC)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--abi", choices=sorted(ABIS), required=True)
    parser.add_argument("--assets", type=Path, required=True, help="flavor assets dir")
    parser.add_argument("--jnilibs", type=Path, required=True, help="flavor jniLibs/<abi> dir")
    parser.add_argument("--skip-payload", action="store_true")
    args = parser.parse_args()
    stage_proot(args.abi, args.jnilibs)
    if not args.skip_payload:
        build(args.abi, args.assets)
