"""Offline visual prototype of the v15 shaft wall (Claude, spec section 13.1-13.2).

Unrolled wall section: x = arc length along the wall (m), y = depth (m), 1 px = 1 block.
h(x, y) = how far rock protrudes towards the shaft axis (m). Output: shaded block-colour PNG.
Reference implementation for porting into the generator; parameters live in PARAMS.
Run: python -I tools/landscape/v15_wall_proto.py <out.png> [zone] [seed]
"""
import sys
import numpy as np
from PIL import Image

PARAMS = {
    'flute_period': 6.0,      # m, mean groove spacing along the wall (spec 3-9)
    'flute_stretch': 20.0,    # vertical stretch of grooves (spec 15-25)
    'flute_depth': 1.2,       # m (spec 1-4); secondary to slabs
    'pipe_period': 18.0,      # m, merged "organ pipes" (spec 10-25)
    'pipe_stretch': 8.0,
    'pipe_depth': 3.5,
    'drift_period': 60.0,     # m, slow phase drift of grooves with depth
    'drift_amp': 8.0,         # m
    'bulge_period': 90.0,     # m, large buttresses and bays
    'bulge_depth': 10.0,
    'streak_width': 5.0,      # m, vertical material streaks
    'streak_stretch': 16.0,
    'strata_period': 30.0,    # m, faint horizontal strata (spec 20-40)
    'moss_below_ledge': 20.0,  # m
    'moss_streak_len': 40.0,
    'vine_min': 6.0, 'vine_max': 30.0,
    'slab_period': 34.0,      # m, macro vertical slabs/facets (canon: big lit and shaded planes)
    'slab_stretch': 10.0,
    'slab_depth': 9.0,
    'slab_steps': 5,          # terraces across x -> crisp vertical edges
    'light_z': 0.35,
    'drape_width': 7.0,       # m, moss curtains hanging down the wall
    'drape_len': 35.0,        # m
    'drape_cover': 0.18,      # share of wall under curtains (gardens; wet x1.5, sunny x0.5)          # smaller = more grazing sun
}

# Approximate mean texture colours of vanilla blocks (sRGB).
BLOCK = {
    'stone': (125, 125, 125), 'andesite': (136, 136, 134), 'calcite': (223, 224, 220),
    'diorite': (188, 188, 188), 'tuff': (108, 109, 102), 'mossy_cobblestone': (110, 118, 94),
    'moss_block': (89, 109, 45), 'dripstone_block': (134, 107, 92), 'deepslate': (80, 80, 82),
    'white_terracotta': (209, 178, 161), 'vine': (64, 104, 34), 'grass_top': (94, 143, 52),
}
ZONES = {  # spec 13.1, percent
    'rim': {'stone': 40, 'andesite': 28, 'calcite': 10, 'dripstone_block': 7, 'white_terracotta': 5, 'mossy_cobblestone': 10},
    'gardens': {'stone': 30, 'andesite': 28, 'calcite': 9, 'dripstone_block': 10, 'white_terracotta': 8, 'tuff': 5, 'mossy_cobblestone': 10},
    'karst': {'stone': 35, 'tuff': 25, 'andesite': 15, 'calcite': 10, 'dripstone_block': 8, 'moss_block': 7},
}


def smooth_noise(rng, w, h, px, py):
    """Bicubic value noise in [0,1] with feature size px (x) and py (y) metres."""
    gw, gh = max(2, int(w / px) + 3), max(2, int(h / py) + 3)
    grid = rng.random((gh, gw)).astype(np.float32)
    img = Image.fromarray(grid, mode='F').resize((max(w, int(gw * px)), max(h, int(gh * py))), Image.BICUBIC)
    return np.clip(np.asarray(img)[:h, :w], 0, 1)


def ridged(n):
    return 1.0 - np.abs(2.0 * n - 1.0)


def wall(w, h, zone, seed, P=PARAMS):
    rng = np.random.default_rng(seed)
    y = np.arange(h, dtype=np.float32)[:, None]
    x = np.arange(w, dtype=np.float32)[None, :]
    # Phase drift: shift x by a slow function of depth so grooves lean and wander.
    drift = (smooth_noise(rng, 1, h, 1, P['drift_period']) - 0.5) * 2 * P['drift_amp']
    pad = int(P['drift_amp']) + 4
    flute = ridged(smooth_noise(rng, w + 2 * pad, h, P['flute_period'], P['flute_period'] * P['flute_stretch']))
    pipe = ridged(smooth_noise(rng, w + 2 * pad, h, P['pipe_period'], P['pipe_period'] * P['pipe_stretch']))
    xi = np.clip((x + pad + drift).astype(int), 0, w + 2 * pad - 1)
    yi = np.broadcast_to(np.arange(h)[:, None], xi.shape)
    fl = flute[yi, xi]
    hgt = (fl ** 2) * P['flute_depth'] + (pipe[yi, xi] ** 3) * P['pipe_depth']
    hgt = hgt + smooth_noise(rng, w, h, P['bulge_period'], P['bulge_period'] * 1.5) * P['bulge_depth']
    slab = smooth_noise(rng, w + 2 * pad, h, P['slab_period'], P['slab_period'] * P['slab_stretch'])[yi, xi]
    k = P['slab_steps']
    q = slab * k
    frac = q - np.floor(q)
    q = np.floor(q) + np.clip((frac - 0.85) / 0.15, 0, 1)  # flat facet, short steep step
    hgt = hgt + q / k * P['slab_depth']
    # One ledge (rounded underside grown into the wall) to test moss and vines under it.
    ledge_y, ledge_x0, ledge_x1 = int(h * 0.3), int(w * 0.2), int(w * 0.75)
    cx, half = (ledge_x0 + ledge_x1) / 2, (ledge_x1 - ledge_x0) / 2
    t = np.clip(1 - ((x - cx) / half) ** 2, 0, 1)
    depth_under = 18 * np.sqrt(t)
    under = np.clip(1 - (y - ledge_y) / (depth_under + 1e-3), 0, 1) * (y >= ledge_y)
    hgt = np.maximum(hgt, 25 * t * under ** 0.7)
    is_ledge_top = (y >= ledge_y - 1) & (y <= ledge_y) & (t > 0.02)

    # Materials: per-block weighted random (reads as one textured tone from afar); the weights
    # drift slowly over large vertical regions (lighter calcite-rich, darker tuff-rich, warm
    # dripstone/white-terracotta veins) plus faint horizontal strata. No iso-band rings.
    weights = ZONES[zone]
    names = list(weights)
    base = np.array([weights[n] for n in names], np.float32)
    region = [smooth_noise(rng, w, h, P['streak_width'] * 6, P['streak_width'] * P['streak_stretch'] * 3) for _ in names]
    strata = 0.5 + 0.5 * np.sin(2 * np.pi * y / P['strata_period'] + 3 * smooth_noise(rng, w, h, 80, 40))
    wfield = np.stack([base[i] * (0.05 + 3.0 * region[i] ** 4) * (0.85 + 0.3 * strata) for i in range(len(names))], -1)
    wfield /= wfield.sum(-1, keepdims=True)
    cdf = np.cumsum(wfield, -1)
    pick = rng.random((h, w, 1)).astype(np.float32)
    idx = np.clip((pick > cdf).sum(-1), 0, len(names) - 1)
    col = np.array([BLOCK[n] for n in names], np.float32)[idx]

    # Moss: big patches on up-facing surfaces, patchy band under the ledge, rare groove streaks.
    below = y - ledge_y
    near_ledge = (below > 0) & (below < P['moss_below_ledge'] + 25 * t) & (t > 0.03)
    gyy, _ = np.gradient(hgt)
    up = gyy < -0.35  # protrusion grows with depth -> surface faces up and catches soil
    moss = (near_ledge & (smooth_noise(rng, w, h, 4, 6) > 0.45)) | (up & (smooth_noise(rng, w, h, 10, 14) > 0.45)) |            ((fl < 0.2) & (smooth_noise(rng, w, h, 6, P['moss_streak_len']) > 0.8))
    # Curtains: discrete objects. Anchor = moss clump (wider, 3-6 m tall), below it a tapering
    # curtain of vines/moss with a ragged lower edge. Count from drape_cover; bare rock between.
    curtain = np.zeros((h, w), bool)
    clump = np.zeros((h, w), bool)
    ragged = smooth_noise(rng, w, h, 2, 3)
    n_anchor = int(P['drape_cover'] * w * h / (P['drape_width'] * P['drape_len'] * 0.55))
    # Clustered anchors: green gathers on crests in groups, large faces stay bare (reference r02).
    n_cluster = max(1, n_anchor // 5)
    centres = [(rng.uniform(0, w), rng.uniform(-10, h)) for _ in range(n_cluster)]
    for i in range(n_anchor):
        cx0, cy0 = centres[i % n_cluster]
        x0, y0 = cx0 + rng.normal(0, 9), cy0 + rng.normal(0, 3)
        wd = P['drape_width'] * rng.uniform(0.8, 2.2)
        ln = P['drape_len'] * rng.uniform(0.45, 1.3)
        ch = rng.uniform(3, 6)
        fr = np.clip((y - y0) / ln, 0, 1)
        half_w = wd * 0.5 * (1 - fr ** 1.5) + 2 * (ragged - 0.5)
        body = (y >= y0) & (y < y0 + ln) & (np.abs(x - x0) < half_w)
        curtain |= body
        clump |= (np.abs(y - y0) < ch * 0.5) & (np.abs(x - x0) < wd * 0.75 + 2 * (ragged - 0.5))
    moss = moss | clump | (curtain & (ragged < 0.45))
    col[moss] = BLOCK['moss_block']
    col[curtain & ~moss] = BLOCK['vine']

    # Vines hanging from the ledge lip, 6-30 m, attached to rock surface.
    lens = P['vine_min'] + smooth_noise(rng, w, 1, 2, 1)[0] * (P['vine_max'] - P['vine_min'])
    lip = ledge_y + depth_under[0]
    vine = (y > lip[None, :]) & (y < lip[None, :] + lens[None, :]) & (t > 0.05) & (rng.random((h, w)) > 0.35)
    col[vine] = BLOCK['vine']
    col[is_ledge_top] = BLOCK['grass_top']

    # Lambert shading of the protrusion field; light from upper-left, slightly frontal.
    gy, gx = np.gradient(hgt)
    n = np.dstack([-gx, -gy, np.ones_like(hgt)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    L = np.array([-0.75, -0.45, P['light_z']])
    L /= np.linalg.norm(L)
    lam = np.clip((n * L).sum(axis=2), 0, 1)
    ao = 0.75 + 0.25 * (hgt - hgt.min()) / (np.ptp(hgt) + 1e-6)
    shade = (0.30 + 0.95 * lam) * ao
    return np.clip(col * shade[..., None], 0, 255).astype(np.uint8)


if __name__ == '__main__':
    out = sys.argv[1]
    zone = sys.argv[2] if len(sys.argv) > 2 else 'gardens'
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 15
    Image.fromarray(wall(480, 300, zone, seed)).resize((960, 600), Image.NEAREST).save(out)
    print(out)
