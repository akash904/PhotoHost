'use strict';
/*
 * The photo grid.
 *
 * Three things here are what make it feel like a real gallery rather than a list of images:
 *
 *  1. A JUSTIFIED layout -- rows of varying widths sharing one height, filling the container
 *     exactly. A uniform square grid crops everything and makes a library look like a spreadsheet.
 *  2. BLURHASH painted immediately as each tile's background. Every tile is the correct size and
 *     roughly the correct colours on first paint, so there is never a grey box, never a layout
 *     shift, and never a second round-trip just to know how big something is.
 *  3. KEYSET pagination. Scrolling to 2019 costs the same as the first page.
 */

const API = '/api/v1';
const TARGET_ROW_HEIGHT = 190;   // desktop; scaled down on narrow screens
const GAP = 4;
const PAGE = 200;

const state = {
  items: [],
  seen: new Set(),
  nextCursor: null,
  hasMore: true,
  loading: false,
  buckets: [],
  viewerIndex: -1,
  // Selection lives here rather than in the DOM so a re-layout on resize cannot lose it.
  selected: new Set(),
  selectMode: false,
  lastClickedIndex: -1,
  view: 'library',
  trash: [],
};

const el = {
  grid: document.getElementById('grid'),
  scrubber: document.getElementById('scrubber'),
  count: document.getElementById('count'),
  empty: document.getElementById('empty'),
  sentinel: document.getElementById('sentinel'),
  rescan: document.getElementById('rescan'),
  viewer: document.getElementById('viewer'),
  viewerMedia: document.getElementById('viewerMedia'),
  viewerInfo: document.getElementById('viewerInfo'),
  topbar: document.getElementById('topbar'),
  actionbar: document.getElementById('actionbar'),
  selCount: document.getElementById('selCount'),
  selectBtn: document.getElementById('select'),
  trashGrid: document.getElementById('trashGrid'),
  sources: document.getElementById('sources'),
  viewLibrary: document.getElementById('viewLibrary'),
  viewTrash: document.getElementById('viewTrash'),
};

// ---------------------------------------------------------------- blurhash

const B83 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~';

function d83(str) {
  let v = 0;
  for (const c of str) {
    const i = B83.indexOf(c);
    if (i < 0) return NaN;
    v = v * 83 + i;
  }
  return v;
}

const toLinear = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
const toSrgb = (v) => { const x = Math.max(0, Math.min(1, v)); return x <= 0.0031308 ? Math.round(x * 12.92 * 255 + 0.5) : Math.round((1.055 * Math.pow(x, 1 / 2.4) - 0.055) * 255 + 0.5); };
const signPow = (v, e) => Math.sign(v) * Math.pow(Math.abs(v), e);

/** Decodes to a tiny data URL. 24px is plenty -- the result is a blur by definition. */
function blurhashToDataUrl(hash, w = 24, h = 24) {
  if (!hash || hash.length < 6) return null;
  const sizeFlag = d83(hash[0]);
  if (Number.isNaN(sizeFlag)) return null;
  const nx = (sizeFlag % 9) + 1;
  const ny = Math.floor(sizeFlag / 9) + 1;
  if (hash.length !== 4 + 2 * nx * ny) return null;

  const maxValue = (d83(hash[1]) + 1) / 166;
  const colors = new Array(nx * ny);
  const dc = d83(hash.substring(2, 6));
  colors[0] = [toLinear(dc >> 16), toLinear((dc >> 8) & 255), toLinear(dc & 255)];
  for (let i = 1; i < nx * ny; i++) {
    const v = d83(hash.substring(4 + i * 2, 6 + i * 2));
    colors[i] = [
      signPow((Math.floor(v / (19 * 19)) - 9) / 9, 2) * maxValue,
      signPow(((Math.floor(v / 19) % 19) - 9) / 9, 2) * maxValue,
      signPow(((v % 19) - 9) / 9, 2) * maxValue,
    ];
  }

  const canvas = document.createElement('canvas');
  canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(w, h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let r = 0, g = 0, b = 0;
      for (let j = 0; j < ny; j++) {
        for (let i = 0; i < nx; i++) {
          const basis = Math.cos((Math.PI * x * i) / w) * Math.cos((Math.PI * y * j) / h);
          const c = colors[i + j * nx];
          r += c[0] * basis; g += c[1] * basis; b += c[2] * basis;
        }
      }
      const o = 4 * (x + y * w);
      img.data[o] = toSrgb(r); img.data[o + 1] = toSrgb(g); img.data[o + 2] = toSrgb(b); img.data[o + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvas.toDataURL('image/png');
}

// ---------------------------------------------------------------- dates

/**
 * Renders in the photo's OWN timezone, using the offset captured at index time.
 * Using the viewer's timezone instead would silently re-date holiday photos.
 */
function localParts(item) {
  const offsetMin = item.tzOffsetMinutes ?? 0;
  const shifted = new Date(item.capturedAt + offsetMin * 60000);
  return {
    dayKey: shifted.toISOString().slice(0, 10),
    date: shifted,
  };
}

const DAY_FMT = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
const TIME_FMT = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });

function duration(ms) {
  if (!ms) return '';
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** For text from files and servers placed into innerHTML: a file name can contain markup. */
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// ---------------------------------------------------------------- data

async function api(path, opts) {
  const r = await fetch(path, opts);
  if (r.status === 401) {
    document.body.innerHTML = '<div style="padding:40px;font:15px system-ui">Not paired. Open <code>/pair?c=&lt;token&gt;</code> once — the app shows the link.</div>';
    throw new Error('unauthorized');
  }
  if (!r.ok) throw new Error(`${path} -> ${r.status}`);
  return r.json();
}

async function loadPage() {
  if (state.loading || !state.hasMore) return;
  state.loading = true;
  try {
    const q = new URLSearchParams({ limit: String(PAGE) });
    if (state.nextCursor) q.set('cursor', state.nextCursor);
    const page = await api(`${API}/timeline?${q}`);
    // Guard against duplicates: a jump can re-request rows already held.
    for (const it of page.items) {
      if (state.seen.has(it.id)) continue;
      state.seen.add(it.id);
      state.items.push(it);
    }
    state.nextCursor = page.nextCursor;
    state.hasMore = page.hasMore && !!page.nextCursor;
    state.items.sort((a, b) => b.capturedAt - a.capturedAt || b.id - a.id);
    render();
  } finally {
    state.loading = false;
  }
}

async function loadBuckets() {
  state.buckets = await api(`${API}/timeline/buckets`);
  renderScrubber();
}

/** Jumps by replacing the cursor and clearing what is held; a keyset seek, not a scroll. */
async function jumpTo(bucket) {
  state.items = [];
  state.seen.clear();
  // +1 so the newest item in that month is included: the cursor is exclusive.
  state.nextCursor = `${bucket.newestCapturedAt + 1}_9223372036854775807`;
  state.hasMore = true;
  el.grid.innerHTML = '';
  window.scrollTo(0, 0);
  await loadPage();
}

// ---------------------------------------------------------------- layout

function targetHeight() {
  const w = el.grid.clientWidth;
  if (w < 500) return 120;
  if (w < 900) return 160;
  return TARGET_ROW_HEIGHT;
}

/**
 * Packs items into rows that fill the width exactly.
 *
 * Accumulate aspect ratios until the row would overflow at the target height, then solve for the
 * height that makes it fit precisely. Every photo keeps its true aspect ratio -- nothing is cropped
 * to fit a grid cell.
 */
function buildRows(items, containerWidth, target) {
  const rows = [];
  let current = [];
  let ratioSum = 0;

  const flush = (isLast) => {
    if (!current.length) return;
    const avail = containerWidth - GAP * (current.length - 1);
    const justified = avail / ratioSum;

    // A full row is always justified to the exact width. The trailing row of a day needs judgement:
    // stretching one lone photo across the screen looks absurd, but leaving a nearly-full row short
    // makes the whole grid look broken. So justify it once it is at least 40% full, capped so a
    // single wide photo cannot balloon.
    let h = justified;
    if (isLast) {
      const fill = (ratioSum * target) / avail;
      h = fill >= 0.4 ? Math.min(justified, target * 1.7) : target;
    }
    rows.push(current.map((c) => ({ item: c.item, w: Math.floor(c.ratio * h), h: Math.floor(h) })));
    current = [];
    ratioSum = 0;
  };

  for (const item of items) {
    const ratio = item.width && item.height ? item.width / item.height : 1;
    const clamped = Math.max(0.4, Math.min(3.5, ratio));
    current.push({ item, ratio: clamped });
    ratioSum += clamped;
    if (ratioSum * target + GAP * (current.length - 1) >= containerWidth) flush(false);
  }
  flush(true);
  return rows;
}

// ---------------------------------------------------------------- rendering

const blurCache = new Map();
function blurUrl(item) {
  if (!item.blurhash) return null;
  if (!blurCache.has(item.id)) blurCache.set(item.id, blurhashToDataUrl(item.blurhash));
  return blurCache.get(item.id);
}

/** Thumbnails load only when a tile nears the viewport, so scrolling never storms the server. */
const io = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    const img = e.target;
    io.unobserve(img);
    const src = img.dataset.src;
    if (!src) continue;
    img.src = src;
    img.addEventListener('load', () => img.classList.add('in'), { once: true });
  }
}, { rootMargin: '600px 0px' });

function render() {
  const width = el.grid.clientWidth;
  const target = targetHeight();

  // Group by the photo's own local day, preserving newest-first order.
  const days = [];
  let currentDay = null;
  for (const item of state.items) {
    const { dayKey, date } = localParts(item);
    if (!currentDay || currentDay.key !== dayKey) {
      currentDay = { key: dayKey, date, items: [] };
      days.push(currentDay);
    }
    currentDay.items.push(item);
  }

  const frag = document.createDocumentFragment();
  for (const day of days) {
    const head = document.createElement('div');
    head.className = 'dayhead';
    head.innerHTML = `${DAY_FMT.format(day.date)}<span class="n">${day.items.length}</span>`;
    frag.appendChild(head);

    for (const row of buildRows(day.items, width, target)) {
      const rowEl = document.createElement('div');
      rowEl.className = 'row';
      for (const cell of row) rowEl.appendChild(tile(cell));
      frag.appendChild(rowEl);
    }
  }

  el.grid.replaceChildren(frag);
  if (state.view === 'library') {
    el.count.textContent = `${state.items.length}${state.hasMore ? '+' : ''} items`;
  }
  el.empty.classList.toggle('hidden', state.items.length > 0);
  highlightScrubber();
}

function tile(cell) {
  const { item, w, h } = cell;
  const d = document.createElement('div');
  d.className = 'tile';
  d.style.width = `${w}px`;
  d.style.height = `${h}px`;

  const bg = blurUrl(item);
  if (bg) d.style.backgroundImage = `url(${bg})`;

  const img = document.createElement('img');
  img.loading = 'lazy';
  img.decoding = 'async';
  img.alt = '';
  // Request roughly the displayed size class; grid thumbs are 256px on the short edge.
  img.dataset.src = `${API}/assets/${item.id}/thumb?size=grid`;
  d.appendChild(img);
  io.observe(img);

  if (item.isVideo) {
    const b = document.createElement('span');
    b.className = 'badge';
    b.textContent = `▶ ${duration(item.durationMs)}`;
    d.appendChild(b);
  }
  if (item.favorite) {
    const f = document.createElement('span');
    f.className = 'fav';
    f.textContent = '★';
    d.appendChild(f);
  }

  if (state.selected.has(item.id)) d.classList.add('sel');
  if (state.selectMode) {
    const c = document.createElement('span');
    c.className = 'check';
    c.textContent = state.selected.has(item.id) ? '\u2611' : '\u2610';
    d.appendChild(c);
  }

  d.addEventListener('click', (e) => {
    const index = state.items.indexOf(item);
    if (state.selectMode) {
      // Shift-click extends from the last click. On a library of thousands this is the difference
      // between deleting a month and clicking a month's worth of tiles.
      if (e.shiftKey && state.lastClickedIndex >= 0) {
        const [a, b] = [state.lastClickedIndex, index].sort((x, y) => x - y);
        for (let i = a; i <= b; i++) state.selected.add(state.items[i].id);
      } else {
        toggleSelect(item.id);
      }
      state.lastClickedIndex = index;
      render();
      renderActionBar();
    } else {
      openViewer(index);
    }
  });

  // Long-press enters selection on touch, matching every phone gallery.
  let pressTimer = null;
  d.addEventListener('touchstart', () => {
    pressTimer = setTimeout(() => {
      state.selectMode = true;
      toggleSelect(item.id);
      state.lastClickedIndex = state.items.indexOf(item);
      render();
      renderActionBar();
    }, 450);
  }, { passive: true });
  const cancelPress = () => { clearTimeout(pressTimer); };
  d.addEventListener('touchend', cancelPress, { passive: true });
  d.addEventListener('touchmove', cancelPress, { passive: true });

  return d;
}

// ---------------------------------------------------------------- selection

function toggleSelect(id) {
  if (state.selected.has(id)) state.selected.delete(id);
  else state.selected.add(id);
}

function clearSelection() {
  state.selected.clear();
  state.selectMode = false;
  state.lastClickedIndex = -1;
  render();
  renderTrash();
  renderActionBar();
}

function renderActionBar() {
  const n = state.selected.size;
  const on = n > 0;
  el.actionbar.classList.toggle('hidden', !on);
  el.topbar.classList.toggle('hidden', on);
  el.selCount.textContent = `${n} selected`;
  // Restore only makes sense for things already in the trash.
  document.getElementById('selRestore').classList.toggle('hidden', state.view !== 'trash');
  document.getElementById('selFav').classList.toggle('hidden', state.view === 'trash');
}

async function batch(op) {
  const ids = [...state.selected];
  if (!ids.length) return 0;
  const r = await fetch(`${API}/assets/batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, op }),
  });
  if (!r.ok) throw new Error(`batch ${op} -> ${r.status}`);
  const result = await r.json();
  return result.affected;
}

function renderScrubber() {
  const frag = document.createDocumentFragment();
  let lastYear = null;
  for (const b of state.buckets) {
    const year = b.bucket.slice(0, 4);
    const month = Number(b.bucket.slice(5, 7));
    const div = document.createElement('div');
    // Weighted by count, so a month with 2,000 photos is a bigger target than one with three.
    div.className = 'sb-month' + (year !== lastYear ? ' year' : '');
    div.style.flexGrow = String(Math.max(1, Math.sqrt(b.count)));
    div.dataset.bucket = b.bucket;
    div.innerHTML = `<span class="lbl">${year !== lastYear ? year : String(month).padStart(2, '0')}</span>`;
    div.title = `${b.bucket} — ${b.count}`;
    div.addEventListener('click', () => jumpTo(b));
    frag.appendChild(div);
    lastYear = year;
  }
  el.scrubber.replaceChildren(frag);
}

function highlightScrubber() {
  const first = state.items[0];
  if (!first) return;
  const key = localParts(first).dayKey.slice(0, 7);
  for (const node of el.scrubber.children) {
    node.classList.toggle('active', node.dataset.bucket === key);
  }
}

// ---------------------------------------------------------------- viewer

function openViewer(index) {
  if (index < 0 || index >= state.items.length) return;
  state.viewerIndex = index;
  const item = state.items[index];
  el.viewer.classList.remove('hidden');
  document.body.style.overflow = 'hidden';

  el.viewerMedia.replaceChildren();
  zoomReset(null);
  if (item.isVideo) {
    const v = document.createElement('video');
    // The original, streamed with Range support -- which is what makes scrubbing work.
    v.src = `${API}/assets/${item.id}/original`;
    v.controls = true;
    v.autoplay = true;
    v.preload = 'metadata';
    el.viewerMedia.appendChild(v);
  } else {
    // Show the preview immediately, then swap in the original once it has decoded.
    const img = document.createElement('img');
    img.src = `${API}/assets/${item.id}/thumb?size=preview`;
    // Otherwise a mouse drag to pan picks the image up as a file to drag out of the page.
    img.draggable = false;
    el.viewerMedia.appendChild(img);
    zoomReset(img);
    const full = new Image();
    full.src = `${API}/assets/${item.id}/original`;
    full.addEventListener('load', () => { if (state.viewerIndex === index) img.src = full.src; });
  }
  if (!el.viewerInfo.classList.contains('hidden')) loadInfo(item.id);

  // Prefetch a page ahead when opening near the end, so arrow-keying does not stall.
  if (index > state.items.length - 10) loadPage();
}

function closeViewer() {
  el.viewer.classList.add('hidden');
  el.viewerMedia.replaceChildren();
  zoomReset(null);
  el.viewerInfo.classList.add('hidden');
  document.body.style.overflow = '';
  state.viewerIndex = -1;
}

const step = (delta) => openViewer(state.viewerIndex + delta);

// ---------------------------------------------------------------- zoom

/*
 * Zoom and pan in the viewer: wheel or pinch to zoom, drag to pan.
 *
 * A CSS transform on the <img>, origin at its top-left corner. The image keeps its fitted layout
 * box; the transform maps a point u of that box to tx + u*s on screen. Zooming "at the cursor"
 * means choosing the new tx so the point under the cursor stays under it, which is the whole trick
 * behind zoom that feels anchored rather than sliding towards the middle.
 *
 * Pan is clamped so the image cannot be dragged off into black space: once it is larger than the
 * screen its edges stop at the screen's, and while it is smaller it stays centred.
 *
 * The full-resolution original replaces the preview once loaded; it has the same aspect ratio and
 * so the same layout box, so the current zoom carries over, now with real detail behind it.
 */

const MAX_ZOOM = 8;
const zoom = { img: null, s: 1, tx: 0, ty: 0, pointers: new Map(), pinch: null };

function zoomReset(img) {
  zoom.img = img;
  zoom.s = 1;
  zoom.tx = 0;
  zoom.ty = 0;
  zoom.pointers.clear();
  zoom.pinch = null;
  if (img) img.style.transformOrigin = '0 0';
  applyZoom();
}

function applyZoom() {
  el.viewer.classList.toggle('zoomed', zoom.s > 1);
  if (!zoom.img) return;
  zoom.img.style.transform = zoom.s === 1 ? '' : `translate(${zoom.tx}px, ${zoom.ty}px) scale(${zoom.s})`;
}

/** The image's untransformed box, in client coordinates. */
function layoutBox() {
  const r = zoom.img.getBoundingClientRect();
  return { left: r.left - zoom.tx, top: r.top - zoom.ty, w: r.width / zoom.s, h: r.height / zoom.s };
}

function clampZoom(box) {
  const c = el.viewerMedia.getBoundingClientRect();
  const fit = (t, size, lo, hi) => {
    const scaled = size * zoom.s;
    if (scaled <= hi - lo) return (lo + hi) / 2 - scaled / 2;
    return Math.min(lo, Math.max(hi - scaled, t));
  };
  zoom.tx = fit(zoom.tx, box.w, c.left - box.left, c.right - box.left);
  zoom.ty = fit(zoom.ty, box.h, c.top - box.top, c.bottom - box.top);
}

/** Zooms to [scale], keeping the image point under client position (cx, cy) where it is. */
function zoomAt(cx, cy, scale) {
  if (!zoom.img) return;
  const box = layoutBox();
  if (!box.w || !box.h) return; // not decoded yet
  const s = Math.min(MAX_ZOOM, Math.max(1, scale));
  const px = cx - box.left;
  const py = cy - box.top;
  const u = (px - zoom.tx) / zoom.s;
  const v = (py - zoom.ty) / zoom.s;
  zoom.s = s;
  zoom.tx = s === 1 ? 0 : px - u * s;
  zoom.ty = s === 1 ? 0 : py - v * s;
  clampZoom(box);
  applyZoom();
}

function panBy(dx, dy) {
  const box = layoutBox();
  zoom.tx += dx;
  zoom.ty += dy;
  clampZoom(box);
  applyZoom();
}

function zoomAtCentre(factor) {
  const c = el.viewerMedia.getBoundingClientRect();
  zoomAt(c.left + c.width / 2, c.top + c.height / 2, zoom.s * factor);
}

el.viewerMedia.addEventListener('wheel', (e) => {
  if (!zoom.img) return;
  e.preventDefault();
  // One notch of a mouse wheel is ~100 px and zooms ~15%; a trackpad sends many small deltas, and
  // a trackpad pinch arrives as a wheel with ctrlKey set, both handled by the same curve.
  const perUnit = e.deltaMode === 1 ? 0.05 : 0.0015;
  zoomAt(e.clientX, e.clientY, zoom.s * Math.exp(-e.deltaY * perUnit));
}, { passive: false });

el.viewerMedia.addEventListener('dblclick', (e) => {
  if (!zoom.img) return;
  zoomAt(e.clientX, e.clientY, zoom.s > 1 ? 1 : 2.5);
});

// Pointer events cover mouse, pen and touch alike: one pointer pans, two pinch.
el.viewerMedia.addEventListener('pointerdown', (e) => {
  if (!zoom.img) return;
  zoom.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  el.viewerMedia.setPointerCapture(e.pointerId);
  if (zoom.pointers.size === 2) {
    const [a, b] = [...zoom.pointers.values()];
    zoom.pinch = { dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, s: zoom.s };
  }
});
el.viewerMedia.addEventListener('pointermove', (e) => {
  const prev = zoom.pointers.get(e.pointerId);
  if (!prev) return;
  const now = { x: e.clientX, y: e.clientY };
  zoom.pointers.set(e.pointerId, now);
  if (zoom.pointers.size === 2 && zoom.pinch) {
    const [a, b] = [...zoom.pointers.values()];
    const dist = Math.hypot(a.x - b.x, a.y - b.y);
    zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, zoom.pinch.s * (dist / zoom.pinch.dist));
  } else if (zoom.pointers.size === 1 && zoom.s > 1) {
    panBy(now.x - prev.x, now.y - prev.y);
  }
});
const pointerGone = (e) => {
  zoom.pointers.delete(e.pointerId);
  if (zoom.pointers.size < 2) zoom.pinch = null;
};
el.viewerMedia.addEventListener('pointerup', pointerGone);
el.viewerMedia.addEventListener('pointercancel', pointerGone);

// A resize or rotation changes the fitted box; start over rather than leave the image off-centre.
window.addEventListener('resize', () => { if (zoom.img && zoom.s > 1) zoomReset(zoom.img); });

async function loadInfo(id) {
  const d = await api(`${API}/assets/${id}`);
  const { date } = localParts(d);
  const SOURCES = ['EXIF + offset', 'EXIF (no timezone)', 'container', 'file mtime', 'filename', 'unknown'];
  const trusted = d.capturedAtSource <= 1;
  const mb = (d.byteSize / 1048576).toFixed(1);
  el.viewerInfo.innerHTML = `
    <h3>${DAY_FMT.format(date)} · ${TIME_FMT.format(date)}</h3>
    <dl>
      <dt>Date from</dt><dd class="${trusted ? '' : 'warn'}">${SOURCES[d.capturedAtSource] || '?'}</dd>
      <dt>Size</dt><dd>${mb} MB</dd>
      <dt>Dimensions</dt><dd>${d.width ?? '?'} × ${d.height ?? '?'}</dd>
      ${d.durationMs ? `<dt>Length</dt><dd>${duration(d.durationMs)}</dd>` : ''}
      ${d.cameraMake ? `<dt>Camera</dt><dd>${d.cameraMake} ${d.cameraModel ?? ''}</dd>` : ''}
      ${d.latitude ? `<dt>Location</dt><dd>${d.latitude.toFixed(5)}, ${d.longitude.toFixed(5)}</dd>` : ''}
      <dt>Type</dt><dd>${d.mime}</dd>
      <dt>Path</dt><dd>${d.relPath ?? '(unresolved)'}</dd>
      <dt>Hash</dt><dd style="font:11px monospace">${d.contentHash.slice(0, 24)}…</dd>
    </dl>
    <p style="margin-top:16px"><a href="${API}/assets/${d.id}/original?download=1" style="color:var(--accent)">Download original</a></p>`;
}


// ---------------------------------------------------------------- trash

async function loadTrash() {
  state.trash = await api(`${API}/trash`);
  renderTrash();
}

function renderTrash() {
  if (state.view !== 'trash') return;
  const frag = document.createDocumentFragment();
  for (const item of state.trash) {
    const d = document.createElement('div');
    d.className = 'tile' + (state.selected.has(item.id) ? ' sel' : '');
    const bg = blurUrl(item);
    if (bg) d.style.backgroundImage = `url(${bg})`;

    const img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = '';
    // Thumbnails still exist because trashing is a soft delete; nothing is removed until a purge.
    img.dataset.src = `${API}/assets/${item.id}/thumb?size=grid`;
    d.appendChild(img);
    io.observe(img);

    const c = document.createElement('span');
    c.className = 'check';
    c.textContent = state.selected.has(item.id) ? '\u2611' : '\u2610';
    d.appendChild(c);

    if (item.sourceAlbum) {
      const src = document.createElement('span');
      src.className = 'src';
      src.textContent = item.sourceAlbum;
      d.appendChild(src);
    }

    d.addEventListener('click', () => {
      toggleSelect(item.id);
      renderTrash();
      renderActionBar();
    });
    frag.appendChild(d);
  }
  el.trashGrid.replaceChildren(frag);
  el.count.textContent = `${state.trash.length} in trash`;
}

// ---------------------------------------------------------------- sources

async function renderSources() {
  const sources = await api(`${API}/sources`);
  if (!sources.length) {
    el.sources.innerHTML =
      '<h4>Sources</h4><p class="muted">Nothing recorded yet. The folder a photo came from is ' +
      'captured at upload, so it appears for items backed up from now on.</p>';
    return;
  }
  const rows = sources.map((s) => `
    <div class="row">
      <div style="flex:1">
        <strong>${esc(s.album)}</strong>
        <div class="muted" style="font-size:12px">${s.count} items · ${(s.bytes / 1048576).toFixed(0)} MB</div>
      </div>
      <button class="ghost danger" data-album="${esc(s.album)}">Delete all</button>
    </div>`).join('');
  el.sources.innerHTML = `<h4>Sources</h4>${rows}`;
  el.sources.querySelectorAll('button[data-album]').forEach((b) => {
    b.addEventListener('click', async () => {
      const album = b.dataset.album;
      if (!confirm(`Move everything from "${album}" to the trash?`)) return;
      b.disabled = true;
      await fetch(`${API}/assets/batch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ op: 'trash', sourceAlbum: album }),
      });
      await reload();
      await renderSources();
    });
  });
}

function setView(view) {
  state.view = view;
  state.selected.clear();
  el.viewLibrary.classList.toggle('active', view === 'library');
  el.viewTrash.classList.toggle('active', view === 'trash');
  el.grid.classList.toggle('hidden', view !== 'library');
  el.sentinel.classList.toggle('hidden', view !== 'library');
  el.scrubber.classList.toggle('hidden', view !== 'library');
  el.trashGrid.classList.toggle('hidden', view !== 'trash');
  el.sources.classList.toggle('hidden', view !== 'trash');
  el.selectBtn.classList.toggle('hidden', view !== 'library');
  renderActionBar();
  if (view === 'trash') {
    loadTrash();
    renderSources();
  } else {
    render();
  }
}

// ---------------------------------------------------------------- upload

/*
 * Adding photos from whatever device this page is open on: an iPhone, a Mac, a friend's laptop --
 * anything without the app. It is the phone backup's own protocol, driven from the page:
 *
 *  - init with the file's SHA-256, so a photo already in the library is answered "duplicate"
 *    before a byte is sent;
 *  - PATCH in chunks at an explicit offset, so a dropped connection resumes where the server says
 *    it got to, instead of starting a 2 GB video over;
 *  - finish, where the server hashes what arrived and refuses a mismatch.
 *
 * One file at a time. Parallel uploads would not be faster over one Wi-Fi link, and a phone server
 * has little memory and one disk to spare.
 *
 * Hashing first needs crypto.subtle, which browsers only provide on HTTPS pages and on localhost.
 * Over plain http://192.168.x.x the page uploads without a hash and the server still catches a
 * duplicate at finish -- after the transfer rather than before it. Very large files are not hashed
 * either: the browser has no incremental SHA-256, so it would mean holding the whole file in memory.
 */

/** Exactly what the server indexes; anything else would upload and then be ignored. */
const MEDIA_EXT = new Set([
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif', 'avif', 'bmp',
  'dng', 'cr2', 'cr3', 'nef', 'arw', 'orf', 'rw2', 'raf', 'srw',
  'mp4', 'mov', 'm4v', '3gp', 'mkv', 'webm', 'avi',
]);
const CHUNK = 4 * 1024 * 1024;
/** A phone browser holding more than this in memory at once risks the tab being killed. */
const HASH_LIMIT = 128 * 1024 * 1024;
const RETRIES = 5;
/** Shown under Trash > Sources, where "Delete all" undoes a whole upload that went to the wrong place. */
const UPLOAD_SOURCE = 'Uploaded from a browser';

const up = {
  queue: [],
  running: false,
  cancelled: false,
  controller: null,
  total: 0,
  done: 0,
  bytesTotal: 0,
  bytesFinished: 0,
  currentOffset: 0,
  current: null,
  phase: '',
  added: 0,
  duplicates: 0,
  skipped: 0,
  failed: [],
  fatal: null,
};

const upEl = {
  panel: document.getElementById('uploads'),
  title: document.getElementById('upTitle'),
  bar: document.getElementById('upBar'),
  line: document.getElementById('upLine'),
  failed: document.getElementById('upFailed'),
  cancel: document.getElementById('upCancel'),
  close: document.getElementById('upClose'),
  input: document.getElementById('uploadInput'),
  drop: document.getElementById('dropzone'),
};

const extOf = (name) => (name.lastIndexOf('.') > 0 ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '');
const isMedia = (file) => MEDIA_EXT.has(extOf(file.name));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function fmtBytes(n) {
  if (n >= 1073741824) return `${(n / 1073741824).toFixed(2)} GB`;
  if (n >= 1048576) return `${(n / 1048576).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(n / 1024))} KB`;
}

function resetUploads() {
  Object.assign(up, {
    total: 0, done: 0, bytesTotal: 0, bytesFinished: 0, currentOffset: 0, current: null, phase: '',
    added: 0, duplicates: 0, skipped: 0, failed: [], fatal: null, cancelled: false,
  });
}

/** Adds files to the queue, and starts it unless it is already running. */
function enqueueUploads(files) {
  // A finished batch's summary stays up until the next one starts, then the counts start over.
  if (!up.running) resetUploads();
  for (const f of files) {
    if (!isMedia(f)) { up.skipped++; continue; }
    up.queue.push(f);
    up.total++;
    up.bytesTotal += f.size;
  }
  renderUploads();
  if (!up.running && up.queue.length) runUploads();
}

async function runUploads() {
  up.running = true;
  let addedAny = false;
  while (up.queue.length && !up.cancelled && !up.fatal) {
    const file = up.queue.shift();
    up.current = file;
    up.currentOffset = 0;
    try {
      const outcome = await uploadOne(file);
      if (outcome === 'duplicate') up.duplicates++;
      else { up.added++; addedAny = true; }
    } catch (e) {
      if (up.cancelled) break;
      up.failed.push({ name: file.name, reason: e.message || String(e) });
    }
    up.bytesFinished += file.size;
    up.currentOffset = 0;
    up.done++;
    renderUploads();
  }
  up.running = false;
  up.current = null;
  up.queue = [];
  renderUploads();
  // Once, at the end: reloading after every file would keep throwing the grid back to the top.
  if (addedAny && state.view === 'library') reload().catch((e) => console.error(e));
}

async function sha256Hex(file) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Throws on a signed-out page, which no retry can fix, and stops the whole queue. */
function checkSignedIn(r) {
  if (r.status === 401) {
    up.fatal = 'This browser is no longer signed in. Open the pairing link again, then retry.';
    throw new Error('not signed in');
  }
}

async function uploadOne(file) {
  const signal = up.controller?.signal;
  let sha256 = null;
  if (window.crypto?.subtle && file.size <= HASH_LIMIT) {
    up.phase = 'Checking';
    renderUploads();
    sha256 = await sha256Hex(file);
  }

  up.phase = 'Uploading';
  renderUploads();
  const initR = await fetch(`${API}/upload/init`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: file.name,
      size: file.size,
      sha256,
      // The file's own date. The server uses it only when the photo carries no capture date of
      // its own, which is better than the moment it arrived.
      capturedAt: file.lastModified || null,
      sourceAlbum: UPLOAD_SOURCE,
    }),
    signal,
  });
  checkSignedIn(initR);
  if (!initR.ok) throw new Error(`the server refused it (${initR.status}: ${await initR.text()})`);
  const init = await initR.json();
  if (init.duplicate) return 'duplicate';

  const id = init.uploadId;
  let offset = init.offset || 0;
  try {
    let failures = 0;
    while (offset < file.size) {
      if (up.cancelled) throw new Error('cancelled');
      const end = Math.min(offset + CHUNK, file.size);
      try {
        const r = await fetch(`${API}/upload/${id}?offset=${offset}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/octet-stream' },
          body: file.slice(offset, end),
          signal,
        });
        checkSignedIn(r);
        if (r.status === 409) {
          // The server holds a different amount than assumed, e.g. a chunk that landed but whose
          // answer was lost. Carry on from what it actually has.
          offset = (await r.json()).offset;
        } else if (!r.ok) {
          throw new Error(`upload stopped (${r.status})`);
        } else {
          offset = (await r.json()).offset;
          failures = 0;
        }
      } catch (e) {
        if (up.cancelled || up.fatal) throw e;
        if (++failures > RETRIES) throw new Error(`connection kept failing (${e.message})`);
        await sleep(1000 * failures);
        // Ask where the server got to rather than assuming the failed chunk did not land.
        const probe = await fetch(`${API}/upload/${id}`, { signal }).catch(() => null);
        if (probe?.ok) offset = (await probe.json()).offset;
      }
      up.currentOffset = offset;
      renderUploads();
    }

    up.phase = 'Saving';
    renderUploads();
    const fin = await fetch(`${API}/upload/${id}/finish`, { method: 'POST', signal });
    checkSignedIn(fin);
    if (!fin.ok) throw new Error(await fin.text() || `the server could not save it (${fin.status})`);
    return (await fin.json()).duplicate ? 'duplicate' : 'added';
  } catch (e) {
    // Drops the half-written copy on the server. Nothing resumes it later: the page has no record
    // of it once this tab is gone.
    fetch(`${API}/upload/${id}`, { method: 'DELETE' }).catch(() => {});
    throw e;
  }
}

function renderUploads() {
  const started = up.running || up.done > 0 || up.skipped > 0 || up.failed.length > 0 || up.fatal;
  upEl.panel.classList.toggle('hidden', !started);
  if (!started) return;

  const bytesDone = up.bytesFinished + up.currentOffset;
  const pct = up.bytesTotal ? Math.min(100, (100 * bytesDone) / up.bytesTotal) : (up.running ? 0 : 100);
  upEl.bar.style.width = `${pct}%`;
  upEl.cancel.classList.toggle('hidden', !up.running || up.cancelled);
  upEl.close.classList.toggle('hidden', up.running);

  const counts = [];
  if (up.added) counts.push(`${up.added} added`);
  if (up.duplicates) counts.push(`${up.duplicates} already in the library`);
  if (up.failed.length) counts.push(`${up.failed.length} failed`);
  if (up.skipped) counts.push(`${up.skipped} skipped (not a photo or video)`);

  if (up.running) {
    upEl.title.textContent = up.cancelled
      ? 'Stopping…'
      : `${up.phase || 'Uploading'} ${Math.min(up.done + 1, up.total)} of ${up.total}`;
    upEl.line.textContent = `${fmtBytes(bytesDone)} of ${fmtBytes(up.bytesTotal)}` +
      (up.current ? ` · ${up.current.name}` : '') + (counts.length ? ` · ${counts.join(' · ')}` : '');
  } else {
    upEl.title.textContent = up.fatal ? 'Upload stopped' : up.cancelled ? 'Upload cancelled' : 'Upload finished';
    upEl.line.textContent = up.fatal || counts.join(' · ') || 'Nothing to upload.';
  }

  upEl.failed.classList.toggle('hidden', !up.failed.length);
  if (up.failed.length) {
    upEl.failed.querySelector('summary').textContent = `Show what failed (${up.failed.length})`;
    upEl.failed.querySelector('ul').innerHTML =
      up.failed.map((f) => `<li><strong>${esc(f.name)}</strong>: ${esc(f.reason)}</li>`).join('');
  }
}

/**
 * Files from a drop, walking into dropped folders.
 *
 * The entries must be taken from the DataTransfer before the first await: the browser empties it
 * as soon as the drop handler yields.
 */
async function filesFromDrop(dt) {
  const entries = [...(dt.items || [])]
    .filter((i) => i.kind === 'file')
    .map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null));
  if (!entries.length || entries.some((e) => !e)) return [...dt.files];

  const out = [];
  const walk = async (entry) => {
    if (entry.name.startsWith('.')) return;
    if (entry.isFile) {
      out.push(await new Promise((res, rej) => entry.file(res, rej)));
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      // readEntries answers in batches (100 in Chrome) and signals the end with an empty one.
      for (;;) {
        const batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        if (!batch.length) break;
        for (const child of batch) await walk(child);
      }
    }
  };
  for (const e of entries) {
    try { await walk(e); } catch (err) { console.error(err); }
  }
  return out;
}

const dragHasFiles = (e) => !!e.dataTransfer && [...e.dataTransfer.types].includes('Files');
let dragDepth = 0;

window.addEventListener('dragenter', (e) => {
  if (!dragHasFiles(e)) return;
  e.preventDefault();
  dragDepth++;
  upEl.drop.classList.remove('hidden');
});
window.addEventListener('dragover', (e) => {
  if (!dragHasFiles(e)) return;
  // Without this the browser opens the dropped file itself and navigates away from the library.
  e.preventDefault();
  e.dataTransfer.dropEffect = 'copy';
});
window.addEventListener('dragleave', (e) => {
  if (!dragHasFiles(e)) return;
  // dragenter/dragleave fire for every child element crossed; only leaving the page counts.
  if (--dragDepth <= 0) { dragDepth = 0; upEl.drop.classList.add('hidden'); }
});
window.addEventListener('drop', async (e) => {
  if (!dragHasFiles(e)) return;
  e.preventDefault();
  dragDepth = 0;
  upEl.drop.classList.add('hidden');
  enqueueUploads(await filesFromDrop(e.dataTransfer));
});

document.getElementById('upload').addEventListener('click', () => upEl.input.click());
upEl.input.addEventListener('change', () => {
  const files = [...upEl.input.files];
  // Cleared so choosing the same file again still fires a change.
  upEl.input.value = '';
  if (files.length) enqueueUploads(files);
});

upEl.cancel.addEventListener('click', () => {
  up.cancelled = true;
  up.controller?.abort();
  up.controller = new AbortController();
  renderUploads();
});
upEl.close.addEventListener('click', () => {
  resetUploads();
  renderUploads();
});
up.controller = new AbortController();

// Closing the tab mid-upload loses the rest of the queue; the browser's own "leave site?" asks first.
window.addEventListener('beforeunload', (e) => {
  if (!up.running) return;
  e.preventDefault();
  e.returnValue = '';
});

// ---------------------------------------------------------------- events


document.getElementById('vClose').addEventListener('click', closeViewer);
document.getElementById('vPrev').addEventListener('click', () => step(-1));
document.getElementById('vNext').addEventListener('click', () => step(1));
document.getElementById('vInfo').addEventListener('click', () => {
  el.viewerInfo.classList.toggle('hidden');
  if (!el.viewerInfo.classList.contains('hidden') && state.viewerIndex >= 0) {
    loadInfo(state.items[state.viewerIndex].id);
  }
});

window.addEventListener('keydown', (e) => {
  if (el.viewer.classList.contains('hidden')) return;
  // Escape closes the info panel first if it is open, then the viewer. Jumping straight out of
  // the viewer loses your place in the grid for what is usually meant as "close this panel".
  if (e.key === 'Escape') {
    if (!el.viewerInfo.classList.contains('hidden')) el.viewerInfo.classList.add('hidden');
    else closeViewer();
  } else if (e.key === 'ArrowLeft') step(-1);
  else if (e.key === 'ArrowRight') step(1);
  else if (e.key === 'i') document.getElementById('vInfo').click();
  else if (e.key === '+' || e.key === '=') zoomAtCentre(1.5);
  else if (e.key === '-') zoomAtCentre(1 / 1.5);
  else if (e.key === '0') zoomReset(zoom.img);
});

// Swipe between photos on touch, the way a phone gallery should behave.
el.viewerMedia.addEventListener('click', () => {
  if (!el.viewerInfo.classList.contains('hidden')) el.viewerInfo.classList.add('hidden');
});

let touchX = null;
el.viewerMedia.addEventListener('touchstart', (e) => {
  // A second finger makes it a pinch, and a zoomed photo is being panned: neither is a swipe.
  touchX = e.touches.length === 1 && zoom.s === 1 ? e.touches[0].clientX : null;
}, { passive: true });
el.viewerMedia.addEventListener('touchend', (e) => {
  if (touchX === null || zoom.s > 1) { touchX = null; return; }
  const dx = e.changedTouches[0].clientX - touchX;
  if (Math.abs(dx) > 60) step(dx < 0 ? 1 : -1);
  touchX = null;
}, { passive: true });


el.viewLibrary.addEventListener('click', () => setView('library'));
el.viewTrash.addEventListener('click', () => setView('trash'));

el.selectBtn.addEventListener('click', () => {
  state.selectMode = !state.selectMode;
  if (!state.selectMode) state.selected.clear();
  el.selectBtn.classList.toggle('active', state.selectMode);
  render();
  renderActionBar();
});

document.getElementById('selClear').addEventListener('click', clearSelection);

document.getElementById('selAll').addEventListener('click', () => {
  const pool = state.view === 'trash' ? state.trash : state.items;
  for (const it of pool) state.selected.add(it.id);
  render();
  renderTrash();
  renderActionBar();
});

document.getElementById('selFav').addEventListener('click', async () => {
  await batch('favorite');
  clearSelection();
  await reload();
});

document.getElementById('selRestore').addEventListener('click', async () => {
  await batch('restore');
  clearSelection();
  await loadTrash();
  await reload();
});

document.getElementById('selDelete').addEventListener('click', async () => {
  const n = state.selected.size;
  // In the trash, Delete is permanent; in the library it is reversible. The wording has to say so,
  // because the button looks identical in both places.
  const permanent = state.view === 'trash';
  const message = permanent
    ? `Permanently delete ${n} item(s)? This removes the stored files and cannot be undone.`
    : `Move ${n} item(s) to the trash?`;
  if (!confirm(message)) return;
  await batch(permanent ? 'purge' : 'trash');
  clearSelection();
  if (permanent) await loadTrash();
  await reload();
});

window.addEventListener('keydown', (e) => {
  if (!el.viewer.classList.contains('hidden')) return;
  if (e.key === 'Escape' && state.selected.size) clearSelection();
});

el.rescan.addEventListener('click', async () => {
  el.rescan.disabled = true;
  el.rescan.textContent = 'Scanning…';
  try {
    await fetch(`${API}/scan`, { method: 'POST' });
    // The scan runs in the background; poll until the count stops moving.
    let last = -1;
    for (let i = 0; i < 40; i++) {
      await new Promise((r) => setTimeout(r, 1500));
      const s = await api(`${API}/stats`);
      if (s.assets === last && s.pendingJobs === 0) break;
      last = s.assets;
    }
    await reload();
  } finally {
    el.rescan.disabled = false;
    el.rescan.textContent = 'Scan';
  }
});

async function reload() {
  state.items = [];
  state.seen.clear();
  state.nextCursor = null;
  state.hasMore = true;
  el.grid.innerHTML = '';
  await Promise.all([loadPage(), loadBuckets()]);
}

// Infinite scroll.
new IntersectionObserver((entries) => {
  if (entries.some((e) => e.isIntersecting)) loadPage();
}, { rootMargin: '900px 0px' }).observe(el.sentinel);

let resizeTimer = null;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(render, 120);
});

reload().catch((e) => console.error(e));
