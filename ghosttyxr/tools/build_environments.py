#!/usr/bin/env python3
"""Bake CC0 Poly Haven panoramas into inward-facing *cubemap* .glb files.

These glb files are the *visible* geometry for the immersive "focus mode"
spatial environment on Android XR (see `GhosttyXRApp`'s immersive
`LaunchedEffect`). Per the Jetpack XR alpha16 API the visible environment comes
from `SpatialEnvironmentPreference.geometry` (a `GltfModel`) — NOT from an
EXR/IBL, which only lights 3D objects (the terminal has none). So the panorama
must be baked onto unlit mesh geometry.

Why a cubemap and not an equirect sky-dome (the previous approach): an
equirectangular texture wrapped on a sphere wastes resolution at the poles and
visibly *stretches* there, and a single 2:1 texture spreads its width across the
full 360°, so detail at the horizon is starved. We instead resample the source
panorama into the 6 faces of a cube (gnomonic projection, one square texture per
face). That samples the sphere far more uniformly — no pole pinch, no horizontal
starvation — and matches how GPUs sample cube maps.

Resolution target (Samsung Galaxy XR): per-eye 3552x3840 over 109°x100° FOV →
~32 pixels/degree. A cube face of N px covering a 90° frustum resolves
~N/114.6 PPD at its (worst-case) centre, rising toward the edges. The source
tonemapped JPGs are 8K equirect (~22.75 PPD at the equator), so FACE_SIZE=4096
(≈35.7 PPD at face centre) fully captures the source with headroom and is never
itself the bottleneck. The old 2048x1024 equirect domes were ~5.7 PPD — roughly
one sixth of the panel — hence the pixelated/stretched look.

Heavy work (venv, downloads, scratch) lives on an external/NVMe workspace so the
main disk stays clear; only the final .glb files are copied into the app's
assets/ folder.

Usage:
    python3 build_environments.py                 # build all, default workspace
    WORKSPACE=/Volumes/NVMe/ghosttyxr-assets \\
        python3 build_environments.py --only dikhololo_night

Assets (all CC0, https://polyhaven.com), curated as dark night skies (good
terminal-text contrast):
    dikhololo_night, satara_night_no_lamps, moonless_golf, rogland_clear_night
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image
import pygltflib

# --- Configuration -----------------------------------------------------------

# CC0 Poly Haven panoramas curated as dark, tonally-safe terminal backdrops.
ASSETS = [
    "dikhololo_night",
    "satara_night_no_lamps",
    "moonless_golf",
    "rogland_clear_night",
]

POLYHAVEN_FILES_API = "https://api.polyhaven.com/files/{slug}"
USER_AGENT = "ghosttyxr-asset-builder"

# Per-cube-face texture resolution. See the module docstring for the PPD math:
# 4096 fully captures the 8K equirect source (~35.7 PPD at face centre vs the
# Galaxy XR's ~32 PPD) with no pole stretch. Bump only if you also raise the
# source resolution (the tonemapped JPGs are 8K, so larger faces just upsample).
FACE_SIZE = 4096

# JPEG quality for the baked faces. Dark night skies compress well; 90 keeps
# each glb in the low-MB range while staying free of visible blocking.
JPEG_QUALITY = 90

# Cube half-extent in metres. The cube is viewed from its centre and acts as an
# at-infinity backdrop; 100 m sits well inside the 200 m XR view distance.
CUBE_HALF_EXTENT = 100.0

# Workspace (downloads, scratch). Defaults to NVMe; override with $WORKSPACE.
WORKSPACE = Path(os.environ.get("WORKSPACE", "/Volumes/NVMe/ghosttyxr-assets"))

# Final destination inside the app, relative to this script's repo location.
REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_OUT = REPO_ROOT / "android/app/src/main/assets/environments"

# Pillow refuses very large images by default (decompression-bomb guard); the
# 8K source JPGs are safe and known.
Image.MAX_IMAGE_PIXELS = None


# --- Download -----------------------------------------------------------------

def _request(url: str) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT})


def fetch_json(url: str) -> dict:
    with urllib.request.urlopen(_request(url), timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def download(url: str, dest: Path) -> None:
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  cached: {dest.name}")
        return
    print(f"  downloading: {url}")
    with urllib.request.urlopen(_request(url), timeout=600) as resp, open(dest, "wb") as f:
        f.write(resp.read())


def fetch_tonemapped_jpg(slug: str, scratch: Path) -> Path:
    """Poly Haven exposes a ready-made tonemapped JPG (8K equirect), so we skip
    HDR decoding and inherit the artist's exposure — which matters for night
    HDRIs whose linear dynamic range is enormous."""
    meta = fetch_json(POLYHAVEN_FILES_API.format(slug=slug))
    tonemapped = meta.get("tonemapped")
    if not tonemapped or "url" not in tonemapped:
        raise RuntimeError(f"{slug}: no tonemapped JPG in Poly Haven metadata")
    jpg = scratch / f"{slug}.jpg"
    download(tonemapped["url"], jpg)
    return jpg


# --- Equirect -> cubemap resampling ------------------------------------------

# Face directions follow the OpenGL cube-map convention. For each face we
# reconstruct the world-space direction from face coordinates (sc, tc) in
# [-1, 1] (sc rightward, tc upward). Because each face's major axis is ±1 and
# its minor axes are exactly sc/tc, multiplying a corner direction by the cube
# half-extent lands precisely on the cube surface — so the same mapping drives
# both texture baking and the quad geometry, guaranteeing seamless alignment.
#
# name -> function(sc, tc) -> (x, y, z), each arg/return a numpy array.
CUBE_FACES = {
    "px": lambda sc, tc: (np.ones_like(sc), -tc, -sc),
    "nx": lambda sc, tc: (-np.ones_like(sc), -tc, sc),
    "py": lambda sc, tc: (sc, np.ones_like(sc), tc),
    "ny": lambda sc, tc: (sc, -np.ones_like(sc), -tc),
    "pz": lambda sc, tc: (sc, -tc, np.ones_like(sc)),
    "nz": lambda sc, tc: (-sc, -tc, -np.ones_like(sc)),
}
# Render order is fixed so geometry primitives line up with their textures.
FACE_ORDER = ["px", "nx", "py", "ny", "pz", "nz"]


def _sample_equirect(equirect: np.ndarray, dirs: tuple[np.ndarray, ...]) -> np.ndarray:
    """Bilinearly sample an equirectangular RGB image at unit directions.

    `equirect` is (H, W, 3) uint8. `dirs` is (x, y, z) arrays of any shape.
    Longitude wraps; latitude clamps. Returns uint8 of the inputs' shape + (3,).
    """
    x, y, z = dirs
    norm = np.sqrt(x * x + y * y + z * z)
    x, y, z = x / norm, y / norm, z / norm

    lon = np.arctan2(x, z)          # [-pi, pi], 0 toward +z
    lat = np.arcsin(np.clip(y, -1.0, 1.0))  # [-pi/2, pi/2], +y up

    h, w, _ = equirect.shape
    u = (0.5 + lon / (2.0 * np.pi)) * w - 0.5
    v = (0.5 - lat / np.pi) * h - 0.5

    u0 = np.floor(u).astype(np.int64)
    v0 = np.floor(v).astype(np.int64)
    fu = (u - u0)[..., None]
    fv = (v - v0)[..., None]

    u0m = np.mod(u0, w)
    u1m = np.mod(u0 + 1, w)
    v0c = np.clip(v0, 0, h - 1)
    v1c = np.clip(v0 + 1, 0, h - 1)

    src = equirect.astype(np.float32)
    c00 = src[v0c, u0m]
    c10 = src[v0c, u1m]
    c01 = src[v1c, u0m]
    c11 = src[v1c, u1m]

    top = c00 * (1.0 - fu) + c10 * fu
    bot = c01 * (1.0 - fu) + c11 * fu
    out = top * (1.0 - fv) + bot * fv
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


def bake_face(equirect: np.ndarray, face: str, size: int) -> bytes:
    """Resample one cube face from the equirect and return JPEG bytes."""
    # Pixel centres -> face coords in [-1, 1]; row 0 is the top (tc = +1).
    centers = (np.arange(size, dtype=np.float32) + 0.5) / size
    sc_row = centers * 2.0 - 1.0          # left -> right
    tc_col = 1.0 - centers * 2.0          # top  -> bottom
    sc, tc = np.meshgrid(sc_row, tc_col)  # shape (size, size)

    dirs = CUBE_FACES[face](sc, tc)
    pixels = _sample_equirect(equirect, dirs)
    buf = io.BytesIO()
    Image.fromarray(pixels, mode="RGB").save(
        buf, format="JPEG", quality=JPEG_QUALITY, optimize=True
    )
    return buf.getvalue()


# --- glb assembly -------------------------------------------------------------

def _face_quad(face: str, h: float) -> tuple[np.ndarray, np.ndarray]:
    """Corner positions + UVs for one inward-facing cube face.

    Corners are evaluated with the *same* direction mapping used for baking, at
    (sc, tc) in {-1, +1}, then scaled to the cube surface. UVs use glTF's
    V-down convention so the baked texture lands without a flip.
    """
    corners = [(-1.0, 1.0), (1.0, 1.0), (-1.0, -1.0), (1.0, -1.0)]  # TL, TR, BL, BR
    positions = []
    uvs = []
    for sc, tc in corners:
        sc_a = np.array(sc, dtype=np.float32)
        tc_a = np.array(tc, dtype=np.float32)
        x, y, z = CUBE_FACES[face](sc_a, tc_a)
        positions.append((float(x) * h, float(y) * h, float(z) * h))
        uvs.append(((sc + 1.0) * 0.5, (1.0 - tc) * 0.5))
    return (
        np.array(positions, dtype=np.float32),
        np.array(uvs, dtype=np.float32),
    )


def _pad4(b: bytes) -> bytes:
    return b + b"\x00" * (-len(b) % 4)


def build_glb(slug: str, equirect: np.ndarray, out_path: Path) -> None:
    """Assemble a 6-face inward cubemap glb (one unlit textured quad per face)."""
    gltf = pygltflib.GLTF2()
    gltf.scene = 0
    gltf.scenes = [pygltflib.Scene(nodes=[0])]
    gltf.nodes = [pygltflib.Node(mesh=0, name=f"{slug}_cube")]
    gltf.samplers = [pygltflib.Sampler(magFilter=pygltflib.LINEAR, minFilter=pygltflib.LINEAR)]

    blob = b""
    buffer_views: list[pygltflib.BufferView] = []
    accessors: list[pygltflib.Accessor] = []
    images: list[pygltflib.Image] = []
    textures: list[pygltflib.Texture] = []
    materials: list[pygltflib.Material] = []
    primitives: list[pygltflib.Primitive] = []

    # Two inward-facing triangles per quad (TL, TR, BL, BR vertex order).
    idx = np.array([0, 2, 1, 1, 2, 3], dtype=np.uint32)

    for fi, face in enumerate(FACE_ORDER):
        pos, uv = _face_quad(face, CUBE_HALF_EXTENT)
        jpg = bake_face(equirect, face, FACE_SIZE)

        pos_b = _pad4(pos.tobytes())
        uv_b = _pad4(uv.tobytes())
        idx_b = _pad4(idx.tobytes())
        tex_b = _pad4(jpg)

        off_pos = len(blob)
        off_uv = off_pos + len(pos_b)
        off_idx = off_uv + len(uv_b)
        off_tex = off_idx + len(idx_b)
        blob += pos_b + uv_b + idx_b + tex_b

        bv_base = len(buffer_views)
        buffer_views += [
            pygltflib.BufferView(buffer=0, byteOffset=off_pos, byteLength=pos.nbytes,
                                 target=pygltflib.ARRAY_BUFFER),
            pygltflib.BufferView(buffer=0, byteOffset=off_uv, byteLength=uv.nbytes,
                                 target=pygltflib.ARRAY_BUFFER),
            pygltflib.BufferView(buffer=0, byteOffset=off_idx, byteLength=idx.nbytes,
                                 target=pygltflib.ELEMENT_ARRAY_BUFFER),
            pygltflib.BufferView(buffer=0, byteOffset=off_tex, byteLength=len(jpg)),
        ]

        ac_base = len(accessors)
        accessors += [
            pygltflib.Accessor(bufferView=bv_base + 0, componentType=pygltflib.FLOAT,
                               count=len(pos), type=pygltflib.VEC3,
                               min=pos.min(axis=0).tolist(), max=pos.max(axis=0).tolist()),
            pygltflib.Accessor(bufferView=bv_base + 1, componentType=pygltflib.FLOAT,
                               count=len(uv), type=pygltflib.VEC2),
            pygltflib.Accessor(bufferView=bv_base + 2, componentType=pygltflib.UNSIGNED_INT,
                               count=len(idx), type=pygltflib.SCALAR),
        ]

        images.append(pygltflib.Image(bufferView=bv_base + 3, mimeType="image/jpeg"))
        textures.append(pygltflib.Texture(source=fi, sampler=0))

        material = pygltflib.Material(
            name=f"{slug}_{face}",
            pbrMetallicRoughness=pygltflib.PbrMetallicRoughness(
                baseColorTexture=pygltflib.TextureInfo(index=fi),
                metallicFactor=0.0,
                roughnessFactor=1.0,
            ),
            doubleSided=True,
            emissiveFactor=[1.0, 1.0, 1.0],
        )
        # Unlit so the panorama shows at full brightness regardless of scene
        # lighting; also stash the texture as emissive for renderers that ignore
        # the extension.
        material.extensions = {"KHR_materials_unlit": {}}
        material.emissiveTexture = pygltflib.TextureInfo(index=fi)
        materials.append(material)

        primitives.append(pygltflib.Primitive(
            attributes=pygltflib.Attributes(POSITION=ac_base + 0, TEXCOORD_0=ac_base + 1),
            indices=ac_base + 2,
            material=fi,
            mode=pygltflib.TRIANGLES,
        ))

    gltf.buffers = [pygltflib.Buffer(byteLength=len(blob))]
    gltf.bufferViews = buffer_views
    gltf.accessors = accessors
    gltf.images = images
    gltf.textures = textures
    gltf.materials = materials
    gltf.meshes = [pygltflib.Mesh(primitives=primitives)]
    gltf.extensionsUsed = ["KHR_materials_unlit"]

    gltf.set_binary_blob(blob)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    gltf.save_binary(str(out_path))
    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"  wrote {out_path.name} ({size_mb:.1f} MB, 6x{FACE_SIZE} faces)")


# --- Orchestration ------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", nargs="*", default=None,
                        help="Build only these asset slugs (default: all).")
    args = parser.parse_args()

    targets = args.only or ASSETS
    unknown = [t for t in targets if t not in ASSETS]
    if unknown:
        print(f"Unknown asset(s): {unknown}\nKnown: {ASSETS}", file=sys.stderr)
        return 2

    scratch = WORKSPACE / "downloads"
    scratch.mkdir(parents=True, exist_ok=True)
    print(f"Workspace: {WORKSPACE}")
    print(f"Output:    {ASSETS_OUT}")

    for slug in targets:
        print(f"\n[{slug}]")
        jpg = fetch_tonemapped_jpg(slug, scratch)
        with Image.open(jpg) as image:
            equirect = np.asarray(image.convert("RGB"))
        out = ASSETS_OUT / f"{slug}.glb"
        build_glb(slug, equirect, out)

    print("\nDone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
