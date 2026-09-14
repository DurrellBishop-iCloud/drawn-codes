// Drawn Codes 3D — real printable solids derived from the drawing model.
// The drawing (shared browser storage with ../) is the CAD source: each
// layer's silhouette is traced at high precision, sorted into outlines
// and holes, extruded to a real thickness in millimetres, and stacked in
// layer order. The viewer renders the exact meshes the STL export writes.

import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { STLExporter } from 'three/addons/exporters/STLExporter.js';
import { GridModel, computeFill } from '../engine.js?v=w024';
import { traceSilhouette } from '../silhouette.js?v=w024';

export const APP_VERSION = '3d0.3.1';
const LAYER_COUNT = 4;
const TRACE_SAMPLES = 48;    // export-grade precision

// ---- state -----------------------------------------------------------
let mmPerCell = 4;
const thickness = [2, 2, 2, 2];   // mm per layer
let drawing = null;               // { layers[GridModel], colors, order, fillOn }
const solids = [];                // per layer index: THREE.Mesh or null

// ---- three scene -----------------------------------------------------
const viewEl = document.getElementById('view');
const scene = new THREE.Scene();
scene.background = new THREE.Color(0xf4f4f4);
let camera = new THREE.PerspectiveCamera(40, 1, 0.1, 5000);
let orthoHalf = 100;
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFShadowMap;
viewEl.appendChild(renderer.domElement);
let controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;

function toggleProjection() {
  const target = controls.target.clone();
  const pos = camera.position.clone();
  if (camera.isPerspectiveCamera) {
    orthoHalf = pos.distanceTo(target) *
      Math.tan(THREE.MathUtils.degToRad(camera.fov / 2));
    camera = new THREE.OrthographicCamera(-1, 1, 1, -1, -5000, 5000);
  } else {
    camera = new THREE.PerspectiveCamera(40, 1, 0.1, 5000);
  }
  camera.position.copy(pos);
  camera.up.set(0, 0, 1);
  controls.dispose();
  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.target.copy(target);
  controls.update();
  resize();
  if (window.__dc3d) window.__dc3d.camera = camera;
  const b = document.getElementById('proj');
  b.textContent = camera.isPerspectiveCamera ? 'PERSP' : 'ORTHO';
}

scene.add(new THREE.HemisphereLight(0xffffff, 0x666666, 1.0));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(60, 40, 120);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
sun.shadow.radius = 4;
sun.shadow.bias = -0.0004;
scene.add(sun);
scene.add(sun.target);
const sun2 = new THREE.DirectionalLight(0xffffff, 0.5);
sun2.position.set(-80, -60, 40);
scene.add(sun2);

const grid = new THREE.GridHelper(200, 20, 0xbbbbbb, 0xdddddd);
grid.rotation.x = Math.PI / 2;   // grid in the XY (build-plate) plane, Z up
scene.add(grid);

// shadow-catching build plate (invisible except for received shadows)
const plate = new THREE.Mesh(
  new THREE.PlaneGeometry(2000, 2000),
  new THREE.ShadowMaterial({ opacity: 0.22 }));
plate.receiveShadow = true;
scene.add(plate);

const partGroup = new THREE.Group();
scene.add(partGroup);

function resize() {
  const w = viewEl.clientWidth, h = viewEl.clientHeight;
  renderer.setSize(w, h);
  renderer.setPixelRatio(window.devicePixelRatio || 1);
  const aspect = w / h;
  if (camera.isPerspectiveCamera) {
    camera.aspect = aspect;
  } else {
    camera.left = -orthoHalf * aspect;
    camera.right = orthoHalf * aspect;
    camera.top = orthoHalf;
    camera.bottom = -orthoHalf;
  }
  camera.updateProjectionMatrix();
}
addEventListener('resize', resize);

function animate() {
  requestAnimationFrame(animate);
  controls.update();
  renderer.render(scene, camera);
}

// ---- drawing → solids ------------------------------------------------
function loadDrawing() {
  let d;
  try { d = JSON.parse(localStorage.getItem('drawncodes')); } catch (_) {}
  if (!d || !d.layers) { status('No drawing found — draw something first.'); return null; }
  const layers = [];
  for (let i = 0; i < LAYER_COUNT; i++) {
    const m = new GridModel();
    m.restore(d.layers[i] || []);
    layers.push(m);
  }
  return {
    layers,
    colors: d.colors || ['#000', '#e0362c', '#1d6fe0', '#f2a900'],
    order: (d.order && new Set(d.order).size === LAYER_COUNT) ? d.order : [0, 1, 2, 3],
    fillOn: d.fill || [true, true, true, true],
  };
}

function signedArea(loop) {
  let a = 0;
  for (let i = 0; i < loop.length; i++) {
    const [x1, y1] = loop[i], [x2, y2] = loop[(i + 1) % loop.length];
    a += x1 * y2 - x2 * y1;
  }
  return a / 2;
}

function pointInLoop(loop, px, py) {
  let inside = false;
  for (let i = 0, j = loop.length - 1; i < loop.length; j = i++) {
    const [xi, yi] = loop[i], [xj, yj] = loop[j];
    if ((yi > py) !== (yj > py) &&
        px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

/** Sort traced loops into outer shapes with their holes (nesting-aware). */
function buildShapes(loops) {
  const info = loops.map((pts) => ({ pts, area: Math.abs(signedArea(pts)) }));
  for (const a of info) {
    a.parents = info.filter((b) => b !== a &&
      b.area > a.area && pointInLoop(b.pts, a.pts[0][0], a.pts[0][1]));
    a.depth = a.parents.length;
    a.parent = a.parents.sort((p, q) => p.area - q.area)[0] || null;
  }
  // winding matters: the extruder derives wall normals from loop
  // direction (and the STL inherits them) — outers must run CCW,
  // holes CW, regardless of how the tracer happened to walk them
  const toVec = (pts) => pts.map(([x, y]) => new THREE.Vector2(x, -y));
  const shapes = [];
  for (const a of info) {
    if (a.depth % 2 === 0) {
      let v = toVec(a.pts);
      if (THREE.ShapeUtils.isClockWise(v)) v = v.reverse();
      a.shape = new THREE.Shape(v);
      shapes.push(a);
    }
  }
  for (const a of info) {
    if (a.depth % 2 === 1 && a.parent && a.parent.shape) {
      let v = toVec(a.pts);
      if (!THREE.ShapeUtils.isClockWise(v)) v = v.reverse();
      a.parent.shape.holes.push(new THREE.Path(v));
    }
  }
  return shapes.map((s) => s.shape);
}

function rebuild() {
  partGroup.clear();
  solids.length = 0;
  if (!drawing) return;
  let z = 0;
  let built = 0;
  for (const li of drawing.order) {
    const m = drawing.layers[li];
    solids[li] = null;
    if (m.isEmpty) continue;
    const fill = drawing.fillOn[li] ? computeFill(m) : null;
    // each level traces a whisker larger (lower iso) so upper walls
    // strictly cloak lower ones — no seams at any angle, and slicers
    // prefer a slight overlap between colour bodies
    const iso = Math.max(45, 127.5 - built * 50);
    const sil = traceSilhouette(m, fill, TRACE_SAMPLES, iso);
    if (!sil || !sil.loops.length) continue;
    const shapes = buildShapes(sil.loops);
    if (!shapes.length) continue;
    // terraced solids: every layer rises from the build plate to its
    // level in the stack, so nothing floats — overlaps interpenetrate,
    // which slicers union automatically
    z += thickness[li];
    const geo = new THREE.ExtrudeGeometry(shapes, {
      depth: z, bevelEnabled: false,
    });
    geo.scale(mmPerCell, mmPerCell, 1);
    const mesh = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({
      color: drawing.colors[li], roughness: 0.55, metalness: 0.05,
      side: THREE.DoubleSide,
    }));
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.position.z = 0;
    mesh.userData.layer = li;
    partGroup.add(mesh);
    solids[li] = mesh;
    built++;
  }
  fitCamera();
  status(built ? `${built} layer${built > 1 ? 's' : ''} · ` +
    `${Math.round(z)} mm tall stack — what you see is the mesh you print.`
    : 'Drawing is empty.');
}

function fitCamera() {
  const box = new THREE.Box3().setFromObject(partGroup);
  if (box.isEmpty()) { camera.position.set(60, -80, 80); controls.target.set(0, 0, 0); return; }
  const c = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3()).length() || 50;
  grid.position.set(c.x, c.y, box.min.z - 0.01);
  plate.position.set(c.x, c.y, box.min.z - 0.005);
  // size the shadow camera around the content
  sun.position.set(c.x + size * 0.5, c.y - size * 0.35, box.max.z + size);
  sun.target.position.copy(c);
  const sc = sun.shadow.camera;
  sc.left = -size; sc.right = size; sc.top = size; sc.bottom = -size;
  sc.near = 0.1; sc.far = size * 4;
  sc.updateProjectionMatrix();
  camera.position.set(c.x + size * 0.6, c.y - size * 0.9, box.max.z + size * 0.8);
  camera.up.set(0, 0, 1);
  if (camera.isOrthographicCamera) { orthoHalf = size * 0.55; camera.zoom = 1; }
  controls.target.copy(c);
  controls.update();
  resize();
}

// ---- export ----------------------------------------------------------
const exporter = new STLExporter();

function download(name, buffer) {
  const a = document.createElement('a');
  a.download = name;
  a.href = URL.createObjectURL(new Blob([buffer], { type: 'model/stl' }));
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}

function exportAll() {
  if (!partGroup.children.length) { status('Nothing to export.'); return; }
  scene.updateMatrixWorld(true);
  const data = exporter.parse(partGroup, { binary: true });
  download('drawncodes.stl', data.buffer ?? data);
  status(`drawncodes.stl — ${(data.byteLength / 1024).toFixed(0)} KB`);
}

function exportLayer(li) {
  const mesh = solids[li];
  if (!mesh) { status('Layer is empty.'); return; }
  scene.updateMatrixWorld(true);
  const data = exporter.parse(mesh, { binary: true });
  download(`drawncodes-layer${li + 1}.stl`, data.buffer ?? data);
  status(`layer ${li + 1} STL — ${(data.byteLength / 1024).toFixed(0)} KB`);
}

// ---- ui --------------------------------------------------------------
const $ = (id) => document.getElementById(id);
function status(t) { $('status').textContent = t; }

function rebuildLayerPanel() {
  const box = $('layers');
  box.innerHTML = '';
  if (!drawing) return;
  for (const li of [...drawing.order].reverse()) {   // top of stack first
    const row = document.createElement('div');
    row.className = 'layer';
    const sw = document.createElement('div');
    sw.className = 'swatch';
    sw.style.background = drawing.colors[li];
    const inp = document.createElement('input');
    inp.type = 'number'; inp.min = '0.4'; inp.step = '0.2';
    inp.value = thickness[li];
    inp.oninput = () => { thickness[li] = +inp.value || 1; rebuild(); };
    const lab = document.createElement('span');
    lab.textContent = 'mm';
    lab.style.color = '#888';
    const ex = document.createElement('button');
    ex.textContent = 'STL';
    ex.onclick = () => exportLayer(li);
    row.append(sw, inp, lab, ex);
    if (drawing.layers[li].isEmpty) row.style.opacity = 0.35;
    box.appendChild(row);
  }
}

// view presets — jump the camera to reset orientation
function setView(kind) {
  const box = new THREE.Box3().setFromObject(partGroup);
  const c = box.isEmpty() ? new THREE.Vector3() : box.getCenter(new THREE.Vector3());
  const s = box.isEmpty() ? 120 : (box.getSize(new THREE.Vector3()).length() || 50) * 1.1;
  const pos = {
    top: [c.x, c.y - s * 0.02, c.z + s],
    front: [c.x, c.y - s, c.z + s * 0.08],
    side: [c.x + s, c.y, c.z + s * 0.08],
    iso: [c.x + s * 0.55, c.y - s * 0.7, c.z + s * 0.55],
  }[kind];
  camera.position.set(pos[0], pos[1], pos[2]);
  camera.up.set(0, 0, 1);
  if (camera.isOrthographicCamera) { orthoHalf = s * 0.5; camera.zoom = 1; }
  controls.target.copy(c);
  controls.update();
  resize();
}
for (const b of document.querySelectorAll('#views button')) {
  b.onclick = () => b.dataset.v === 'fit' ? fitCamera() : setView(b.dataset.v);
}
document.getElementById('proj').onclick = toggleProjection;
$('fold').onclick = () => { document.body.classList.toggle('folded'); resize(); };

$('mmcell').oninput = () => { mmPerCell = +$('mmcell').value || 4; rebuild(); };
// arriving back from the drawing tool: pick up the latest drawing
addEventListener('focus', () => { drawing = loadDrawing(); rebuildLayerPanel(); rebuild(); });
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) { drawing = loadDrawing(); rebuildLayerPanel(); rebuild(); }
});
$('reload').onclick = () => { drawing = loadDrawing(); rebuildLayerPanel(); rebuild(); };
$('stl').onclick = exportAll;

// ---- boot ------------------------------------------------------------
$('ver').textContent = APP_VERSION;
window.__dc3d = { scene, renderer, sun, plate, partGroup, camera };
drawing = loadDrawing();
rebuildLayerPanel();
resize();
rebuild();
animate();
