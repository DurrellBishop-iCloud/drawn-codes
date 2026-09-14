// Drawn Codes web — viewport, input, rendering, layers, UI, storage.
import { GridModel, computeFill, buildInk, STROKE } from './engine.js?v=w013';

export const APP_VERSION = 'w0.1.3';
const LAYER_COUNT = 4;
const DEFAULT_COLORS = ['#000000', '#e0362c', '#1d6fe0', '#f2a900'];
const CORNER_ZONE = 0.38;

// ---- state -----------------------------------------------------------
const layers = Array.from({ length: LAYER_COUNT }, () => new GridModel());
const layerColors = [...DEFAULT_COLORS];
const layerOrder = [0, 1, 2, 3];
const layerAllow45 = [true, true, true, true];
const layerAllow90 = [true, true, true, true];
const layerShowFill = [true, true, true, true];
let activeLayer = 0;
let erasing = false;
let snapping = true;

const viewport = { cellSize: 96, originX: 0, originY: 0 };
const MIN_CELL = 14, MAX_CELL = 400;

const fillCache = Array.from({ length: LAYER_COUNT }, () => ({ v: -1, fill: null }));
const inkCache = Array.from({ length: LAYER_COUNT }, () => ({ v: -1, ink: null }));

const model = () => layers[activeLayer];

// ---- canvas ----------------------------------------------------------
const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
let dpr = 1;

function resize() {
  dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(innerWidth * dpr);
  canvas.height = Math.round(innerHeight * dpr);
  canvas.style.width = innerWidth + 'px';
  canvas.style.height = innerHeight + 'px';
  draw();
}
addEventListener('resize', resize);

const s2cX = (px) => viewport.originX + px / viewport.cellSize;
const s2cY = (py) => viewport.originY + py / viewport.cellSize;
const c2sX = (cx) => (cx - viewport.originX) * viewport.cellSize;
const c2sY = (cy) => (cy - viewport.originY) * viewport.cellSize;

function zoomBy(factor, fx, fy) {
  const ns = Math.min(MAX_CELL, Math.max(MIN_CELL, viewport.cellSize * factor));
  const wx = s2cX(fx), wy = s2cY(fy);
  viewport.cellSize = ns;
  viewport.originX = wx - fx / ns;
  viewport.originY = wy - fy / ns;
}

function fillFor(i) {
  if (!layerShowFill[i]) return null;
  const c = fillCache[i];
  if (c.v !== layers[i].version) { c.fill = computeFill(layers[i]); c.v = layers[i].version; }
  return c.fill;
}
function inkFor(i) {
  const c = inkCache[i];
  if (c.v !== layers[i].version) { c.ink = buildInk(layers[i]); c.v = layers[i].version; }
  return c.ink;
}

function draw() {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, innerWidth, innerHeight);

  // guide grid
  const s = viewport.cellSize;
  if (s >= 20) {
    ctx.strokeStyle = '#e1e1e1';
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (let cx = Math.floor(s2cX(0)); c2sX(cx) <= innerWidth; cx++) {
      const px = c2sX(cx);
      ctx.moveTo(px, 0); ctx.lineTo(px, innerHeight);
    }
    for (let cy = Math.floor(s2cY(0)); c2sY(cy) <= innerHeight; cy++) {
      const py = c2sY(cy);
      ctx.moveTo(0, py); ctx.lineTo(innerWidth, py);
    }
    ctx.stroke();
  }

  for (const i of layerOrder) {
    if (layers[i].isEmpty) continue;
    drawLayer(i);
  }
}

function drawLayer(i) {
  const color = layerColors[i];
  const s = viewport.cellSize;
  const offX = -viewport.originX * s;
  const offY = -viewport.originY * s;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = color;

  // enclosure fill: half-cell runs + triangles
  const fill = fillFor(i);
  if (fill) {
    const hs = s / 2;
    for (let y = 0; y < fill.hh; y++) {
      const top = offY + (fill.originHalfR + y) * hs;
      if (top + hs < 0 || top > innerHeight) continue;
      let x = 0;
      while (x < fill.hw) {
        const st = fill.state[y * fill.hw + x];
        if (st === 1) {
          let x2 = x;
          while (x2 + 1 < fill.hw && fill.state[y * fill.hw + x2 + 1] === 1) x2++;
          const left = offX + (fill.originHalfC + x) * hs;
          const right = offX + (fill.originHalfC + x2 + 1) * hs;
          if (right >= 0 && left <= innerWidth) {
            ctx.fillRect(left - 0.5, top - 0.5, right - left + 1, hs + 1);
          }
          x = x2 + 1;
        } else {
          if (st === 2 || st === 3) {
            const left = offX + (fill.originHalfC + x) * hs;
            if (left + hs >= 0 && left <= innerWidth) {
              drawTriangle(st, y, x, left, top, hs);
            }
          }
          x++;
        }
      }
    }
  }

  // skeleton strokes + fillets/diamonds/dots (paths in cell units,
  // centres on integers: shift by half a cell)
  const ink = inkFor(i);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.translate(offX + 0.5 * s, offY + 0.5 * s);
  ctx.scale(s, s);
  ctx.strokeStyle = color;
  ctx.lineWidth = STROKE;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.stroke(ink.strokes);
  ctx.fill(ink.fills);
}

function drawTriangle(st, y, x, left, top, hs) {
  const right = left + hs, bottom = top + hs;
  ctx.beginPath();
  if ((x & 1) === (y & 1)) {
    if (st === 2) { ctx.moveTo(left, top); ctx.lineTo(right, top); ctx.lineTo(right, bottom); }
    else { ctx.moveTo(left, top); ctx.lineTo(left, bottom); ctx.lineTo(right, bottom); }
  } else {
    if (st === 2) { ctx.moveTo(left, top); ctx.lineTo(right, top); ctx.lineTo(left, bottom); }
    else { ctx.moveTo(right, top); ctx.lineTo(right, bottom); ctx.lineTo(left, bottom); }
  }
  ctx.closePath();
  ctx.fill();
}

// ---- drawing input ---------------------------------------------------
let mode = 'none';           // none | draw | nav
let drawPointer = -1;
let lastX = 0, lastY = 0, lastCol = 0, lastRow = 0;
let snapX = 0, snapY = 0;
let inCorner = false, cornerR = 0, cornerC = 0;
const navPointers = new Map();
let navMidX = 0, navMidY = 0, navSpan = 0;

const colAt = (px) => Math.floor(s2cX(px));
const rowAt = (py) => Math.floor(s2cY(py));

function strokeTo(x, y) {
  const dist = Math.hypot(x - lastX, y - lastY);
  const steps = Math.floor(dist / (viewport.cellSize / 4)) + 1;
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    visitPoint(lastX + (x - lastX) * t, lastY + (y - lastY) * t);
  }
  lastX = x; lastY = y;
}

function visitPoint(px, py) {
  const wx = s2cX(px), wy = s2cY(py);
  const c = Math.floor(wx), r = Math.floor(wy);
  if (!erasing && layerAllow45[activeLayer]) {
    const kc = Math.round(wx), kr = Math.round(wy);
    if (Math.abs(wx - kc) + Math.abs(wy - kr) <= CORNER_ZONE) {
      if (!inCorner) { inCorner = true; cornerR = kr; cornerC = kc; }
      return;
    }
    if (inCorner) {
      inCorner = false;
      if (r !== lastRow || c !== lastCol) {
        const via = cornerR === Math.max(r, lastRow) && cornerC === Math.max(c, lastCol);
        if (via && Math.abs(r - lastRow) === 1 && Math.abs(c - lastCol) === 1) {
          model().connectDiagonal(lastRow, lastCol, r, c);
          lastRow = r; lastCol = c;
          return;
        }
      }
    }
  }
  visitCell(c, r);
}

function visitCell(c, r) {
  if (c === lastCol && r === lastRow) return;
  if (!erasing && !layerAllow90[activeLayer]) { lastCol = c; lastRow = r; return; }
  let cc = lastCol, cr = lastRow;
  while (cc !== c || cr !== r) {
    const dx = c - cc, dy = r - cr;
    const stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
    const stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
    let nc, nr;
    if (Math.abs(dx) >= Math.abs(dy) && stepX !== 0) { nc = cc + stepX; nr = cr; }
    else { nc = cc; nr = cr + stepY; }
    if (erasing) model().erase(nr, nc);
    else model().connect(cr, cc, nr, nc);
    cc = nc; cr = nr;
  }
  lastCol = c; lastRow = r;
}

canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  if (e.pointerType === 'mouse' && e.button !== 0) {   // right/middle: pan
    mode = 'nav';
    navPointers.set(e.pointerId, [e.clientX, e.clientY]);
    rebaseNav();
    return;
  }
  if (mode === 'draw') {   // second touch: cancel stroke, navigate
    model().undo();
    mode = 'nav';
    navPointers.set(e.pointerId, [e.clientX, e.clientY]);
    rebaseNav();
    draw();
    return;
  }
  if (mode === 'nav') {
    navPointers.set(e.pointerId, [e.clientX, e.clientY]);
    rebaseNav();
    return;
  }
  mode = 'draw';
  drawPointer = e.pointerId;
  navPointers.set(e.pointerId, [e.clientX, e.clientY]);
  model().pushUndo();
  if (snapping) {
    const wx = s2cX(e.clientX), wy = s2cY(e.clientY);
    snapX = (Math.floor(wx) + 0.5 - wx) * viewport.cellSize;
    snapY = (Math.floor(wy) + 0.5 - wy) * viewport.cellSize;
  } else { snapX = 0; snapY = 0; }
  lastX = e.clientX + snapX; lastY = e.clientY + snapY;
  lastCol = colAt(lastX); lastRow = rowAt(lastY);
  inCorner = false;
  if (erasing) model().erase(lastRow, lastCol); else model().touch(lastRow, lastCol);
  draw();
});

canvas.addEventListener('pointermove', (e) => {
  if (navPointers.has(e.pointerId)) navPointers.set(e.pointerId, [e.clientX, e.clientY]);
  if (mode === 'draw' && e.pointerId === drawPointer) {
    const events = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of events) strokeTo(ev.clientX + snapX, ev.clientY + snapY);
    draw();
  } else if (mode === 'nav') {
    moveNav();
    draw();
  }
});

function endPointer(e) {
  navPointers.delete(e.pointerId);
  if (mode === 'draw' && e.pointerId === drawPointer) {
    mode = 'none';
    drawPointer = -1;
    saveState();
  } else if (mode === 'nav') {
    if (navPointers.size === 0) { mode = 'none'; saveState(); }
    else rebaseNav();
  }
}
canvas.addEventListener('pointerup', endPointer);
canvas.addEventListener('pointercancel', endPointer);

function navAnchor() {
  let sx = 0, sy = 0;
  const pts = [...navPointers.values()];
  for (const [x, y] of pts) { sx += x; sy += y; }
  const mx = sx / pts.length, my = sy / pts.length;
  let span = 0;
  if (pts.length >= 2) span = Math.hypot(pts[0][0] - pts[1][0], pts[0][1] - pts[1][1]);
  return [mx, my, span];
}
function rebaseNav() { [navMidX, navMidY, navSpan] = navAnchor(); }
function moveNav() {
  const [mx, my, span] = navAnchor();
  if (navSpan > 0 && span > 0) zoomBy(span / navSpan, navMidX, navMidY);
  viewport.originX -= (mx - navMidX) / viewport.cellSize;
  viewport.originY -= (my - navMidY) / viewport.cellSize;
  navMidX = mx; navMidY = my; navSpan = span;
}

canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  if (e.ctrlKey || e.metaKey) {
    zoomBy(Math.exp(-e.deltaY * 0.01), e.clientX, e.clientY);
  } else {
    viewport.originX += e.deltaX / viewport.cellSize;
    viewport.originY += e.deltaY / viewport.cellSize;
  }
  draw();
  scheduleSave();
}, { passive: false });

// ---- UI --------------------------------------------------------------
const $ = (id) => document.getElementById(id);

function restyleChips() {
  $('c45').classList.toggle('on', layerAllow45[activeLayer]);
  $('c90').classList.toggle('on', layerAllow90[activeLayer]);
  $('cfill').classList.toggle('on', layerShowFill[activeLayer]);
}
$('undo').onclick = () => { model().undo(); draw(); saveState(); };
$('c45').onclick = () => { layerAllow45[activeLayer] = !layerAllow45[activeLayer]; restyleChips(); saveState(); };
$('c90').onclick = () => { layerAllow90[activeLayer] = !layerAllow90[activeLayer]; restyleChips(); saveState(); };
$('cfill').onclick = () => { layerShowFill[activeLayer] = !layerShowFill[activeLayer]; restyleChips(); draw(); saveState(); };

$('erase').onclick = () => {
  erasing = !erasing;
  $('erase').textContent = erasing ? 'DRAW' : 'ERASE';
  $('erase').style.background = erasing ? '#962828' : '';
};
$('snap').onclick = () => {
  snapping = !snapping;
  $('snap').classList.toggle('on', snapping);
};
let lastClear = 0;
$('clear').onclick = () => {
  const now = performance.now();
  if (now - lastClear < 400) {
    for (const m of layers) { m.pushUndo(); m.clear(); }
  } else {
    model().pushUndo();
    model().clear();
  }
  lastClear = now;
  draw(); saveState();
};
$('fit').onclick = () => {
  let b = null;
  for (const m of layers) {
    const mb = m.bounds();
    if (!mb) continue;
    if (!b) b = { ...mb };
    else {
      b.minR = Math.min(b.minR, mb.minR); b.maxR = Math.max(b.maxR, mb.maxR);
      b.minC = Math.min(b.minC, mb.minC); b.maxC = Math.max(b.maxC, mb.maxC);
    }
  }
  if (!b) { viewport.cellSize = Math.min(innerWidth, innerHeight) / 12; viewport.originX = 0; viewport.originY = 0; }
  else {
    const cw = b.maxC - b.minC + 3, ch = b.maxR - b.minR + 3;
    viewport.cellSize = Math.min(MAX_CELL, Math.max(MIN_CELL,
      Math.min(innerWidth / cw, (innerHeight - 60) / ch)));
    viewport.originX = b.minC - 1 - (innerWidth / viewport.cellSize - (cw - 2)) / 2;
    viewport.originY = b.minR - 1 - ((innerHeight - 60) / viewport.cellSize - (ch - 2)) / 2;
  }
  draw(); scheduleSave();
};
$('save').onclick = exportPNG;

// native colour swatch in the toolbar: always shows the active layer's
// colour; picking applies live to that layer
const colorInput = $('colorinput');
colorInput.oninput = () => {
  layerColors[activeLayer] = colorInput.value;
  rebuildDots(); draw(); scheduleSave();
};
function syncColorInput() { colorInput.value = layerColors[activeLayer]; }

// layer dots: tap selects, drag reorders
function rebuildDots() {
  const col = $('dots');
  col.innerHTML = '';
  for (const li of [...layerOrder].reverse()) {
    const d = document.createElement('div');
    d.className = 'dot' + (li === activeLayer ? ' active' : '');
    d.style.background = layerColors[li];
    let startY = 0, dragging = false;
    d.addEventListener('pointerdown', (e) => {
      d.setPointerCapture(e.pointerId);
      startY = e.clientY; dragging = false;
      e.stopPropagation();
    });
    d.addEventListener('pointermove', (e) => {
      const dy = e.clientY - startY;
      if (!dragging && Math.abs(dy) > 6) dragging = true;
      if (dragging) d.style.transform = `translateY(${dy}px)`;
    });
    d.addEventListener('pointerup', (e) => {
      const dy = e.clientY - startY;
      d.style.transform = '';
      if (!dragging) {
        activeLayer = li;
        rebuildDots(); restyleChips(); syncColorInput();
      } else {
        const slots = Math.round(dy / 42);
        if (slots !== 0) {
          const display = [...layerOrder].reverse();
          const pos = display.indexOf(li);
          const np = Math.min(display.length - 1, Math.max(0, pos + slots));
          display.splice(pos, 1);
          display.splice(np, 0, li);
          const back = display.reverse();
          for (let k = 0; k < back.length; k++) layerOrder[k] = back[k];
          rebuildDots(); draw();
        }
      }
      saveState();
    });
    col.appendChild(d);
  }
}

// keyboard
addEventListener('keydown', (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === 'z') { model().undo(); draw(); saveState(); e.preventDefault(); }
  else if (e.key === 'e') $('erase').click();
  else if (e.key === 'f') $('fit').click();
  else if (e.key >= '1' && e.key <= '4') { activeLayer = +e.key - 1; rebuildDots(); restyleChips(); syncColorInput(); }
});

// ---- export ----------------------------------------------------------
function exportPNG() {
  let b = null;
  for (const m of layers) {
    const mb = m.bounds();
    if (!mb) continue;
    if (!b) b = { ...mb };
    else {
      b.minR = Math.min(b.minR, mb.minR); b.maxR = Math.max(b.maxR, mb.maxR);
      b.minC = Math.min(b.minC, mb.minC); b.maxC = Math.max(b.maxC, mb.maxC);
    }
  }
  if (!b) return;
  const px = 80;
  const w = (b.maxC - b.minC + 3) * px, h = (b.maxR - b.minR + 3) * px;
  const off = document.createElement('canvas');
  off.width = w; off.height = h;
  const octx = off.getContext('2d');
  octx.fillStyle = '#fff';
  octx.fillRect(0, 0, w, h);
  for (const i of layerOrder) {
    if (layers[i].isEmpty) continue;
    const color = layerColors[i];
    octx.setTransform(1, 0, 0, 1, 0, 0);
    octx.fillStyle = color;
    const fill = fillFor(i);
    const offX = -(b.minC - 1) * px, offY = -(b.minR - 1) * px;
    if (fill) {
      const hs = px / 2;
      for (let y = 0; y < fill.hh; y++) for (let x = 0; x < fill.hw; x++) {
        const st = fill.state[y * fill.hw + x];
        if (st === 0) continue;
        const left = offX + (fill.originHalfC + x) * hs;
        const top = offY + (fill.originHalfR + y) * hs;
        if (st === 1) octx.fillRect(left - 0.5, top - 0.5, hs + 1, hs + 1);
        else {
          octx.beginPath();
          const r = left + hs, bo = top + hs;
          if ((x & 1) === (y & 1)) {
            if (st === 2) { octx.moveTo(left, top); octx.lineTo(r, top); octx.lineTo(r, bo); }
            else { octx.moveTo(left, top); octx.lineTo(left, bo); octx.lineTo(r, bo); }
          } else {
            if (st === 2) { octx.moveTo(left, top); octx.lineTo(r, top); octx.lineTo(left, bo); }
            else { octx.moveTo(r, top); octx.lineTo(r, bo); octx.lineTo(left, bo); }
          }
          octx.closePath(); octx.fill();
        }
      }
    }
    const ink = inkFor(i);
    octx.setTransform(1, 0, 0, 1, offX + 0.5 * px, offY + 0.5 * px);
    octx.scale(px, px);
    octx.strokeStyle = color;
    octx.lineWidth = STROKE;
    octx.lineCap = 'round';
    octx.lineJoin = 'round';
    octx.stroke(ink.strokes);
    octx.fill(ink.fills);
  }
  const a = document.createElement('a');
  a.download = 'code_' + new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19) + '.png';
  a.href = off.toDataURL('image/png');
  a.click();
}

// ---- storage ---------------------------------------------------------
function saveState() {
  try {
    localStorage.setItem('drawncodes', JSON.stringify({
      v: 1,
      cell: viewport.cellSize, ox: viewport.originX, oy: viewport.originY,
      active: activeLayer, colors: layerColors, order: layerOrder,
      a45: layerAllow45, a90: layerAllow90, fill: layerShowFill,
      layers: layers.map((m) => m.serialize()),
    }));
  } catch (_) {}
}
let saveTimer = 0;
function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveState, 400);
}
function loadState() {
  try {
    const d = JSON.parse(localStorage.getItem('drawncodes'));
    if (!d || d.v !== 1) return;
    viewport.cellSize = Math.min(MAX_CELL, Math.max(MIN_CELL, d.cell || 96));
    viewport.originX = d.ox || 0;
    viewport.originY = d.oy || 0;
    activeLayer = Math.min(LAYER_COUNT - 1, Math.max(0, d.active | 0));
    for (let i = 0; i < LAYER_COUNT; i++) {
      if (d.colors?.[i]) layerColors[i] = d.colors[i];
      layerAllow45[i] = d.a45?.[i] ?? true;
      layerAllow90[i] = d.a90?.[i] ?? true;
      layerShowFill[i] = d.fill?.[i] ?? true;
      if (d.layers?.[i]) layers[i].restore(d.layers[i]);
    }
    if (d.order && new Set(d.order).size === LAYER_COUNT) {
      for (let i = 0; i < LAYER_COUNT; i++) layerOrder[i] = d.order[i];
    }
  } catch (_) {}
}

// ---- boot ------------------------------------------------------------
loadState();
document.getElementById('ver').textContent = APP_VERSION;
rebuildDots();
restyleChips();
syncColorInput();
resize();
