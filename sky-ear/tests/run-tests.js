// Sky Ear logic tests. Run with:  node tests/run-tests.js
// They load the script inside app/src/main/assets/index.html with a stubbed
// browser, so no Android device or browser is needed.
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const js = html.split('<script>')[1].split('</script>')[0];

// --- minimal stand-ins for the browser and Web Audio ---
const node = () => ({
  connect() {}, disconnect() {}, start() {}, stop() {}, fillRect() {}, putImageData() {}, drawImage() {},
  createImageData: (w, h) => ({ data: new Uint8ClampedArray(w * h * 4) }),
  getContext: () => node(), classList: { toggle() {} }, style: {}, dataset: {}, appendChild() {},
  querySelector: () => node(), addEventListener() {}, width: 320, height: 120, value: '1',
});
const cache = {};
global.document = { querySelector: s => cache[s] || (cache[s] = node()), createElement: () => node() };
global.performance = { now: () => 0 };
global.requestAnimationFrame = () => {};
global.window = global;

const api = new Function(js + ';return {ridParse, ptCalc, sources, analyze, llDelta, LIB}')();

let passed = 0, failed = 0;
function test(name, fn) {
  try { fn(); passed++; console.log('  ok   ' + name); }
  catch (e) { failed++; console.log('  FAIL ' + name + '\n       ' + e.message); }
}
const near = (a, b, tol, what) => assert.ok(Math.abs(a - b) <= tol, `${what || 'value'}: got ${a}, expected ${b} +/- ${tol}`);

// --- Remote ID: messages produced by the reference library ---
const V = Object.fromEntries(
  fs.readFileSync(path.join(__dirname, 'vectors.txt'), 'utf8').split('\n')
    .filter(l => /^[A-Z]+ [0-9a-f]+$/.test(l)).map(l => l.split(' ')));

console.log('Remote ID parser');
test('message pack decodes every field', () => {
  const u = api.ridParse('00' + V.PACK);
  assert.strictEqual(u.id, '1596F1234567890ABCDE');
  assert.strictEqual(u.idType, 1);
  assert.strictEqual(u.uaType, 2);
  assert.strictEqual(u.status, 2);
  near(u.track, 275, 1, 'track'); near(u.speed, 12.5, 0.01, 'speed'); near(u.vspeed, -1.5, 0.01, 'vspeed');
  near(u.lat, 34.1234567, 1e-6, 'lat'); near(u.lon, -117.7654321, 1e-6, 'lon');
  near(u.altBaro, 210.5, 0.5, 'baro altitude'); near(u.altGeo, 215, 0.5, 'geodetic altitude');
  near(u.height, 42.5, 0.5, 'height'); assert.strictEqual(u.heightRef, 'ground');
  near(u.opLat, 34.123, 1e-6, 'operator lat'); near(u.opLon, -117.765, 1e-6, 'operator lon');
  assert.strictEqual(u.operatorId, 'OP-TEST-0001');
  assert.strictEqual(u.selfId, 'Survey flight');
});
test('single messages decode (Bluetooth legacy style)', () => {
  assert.strictEqual(api.ridParse('00' + V.BASIC).id, '1596F1234567890ABCDE');
  near(api.ridParse('00' + V.LOC).lat, 34.1234567, 1e-6, 'lat');
  near(api.ridParse('00' + V.SYS).opLon, -117.765, 1e-6, 'operator lon');
});
test('short or empty input is ignored', () => {
  assert.deepStrictEqual(api.ridParse(''), {});
  assert.deepStrictEqual(api.ridParse('0012'), {});
});

// --- Pointing math ---
console.log('Phone pointing');
const vert = [1, 0, 0, 0, 0, -1, 0, 1, 0];          // phone upright, back facing north
const mul = (A, B) => { const o = []; for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) o.push(A[i*3]*B[j] + A[i*3+1]*B[3+j] + A[i*3+2]*B[6+j]); return o; };
test('upright phone faces north at the horizon', () => {
  const p = api.ptCalc(vert, 1); near(p.az, 0, 0.01); near(p.el, 0, 0.01);
});
test('phone lying flat: back points straight down, top edge points north', () => {
  const flat = [1, 0, 0, 0, 1, 0, 0, 0, 1];
  near(api.ptCalc(flat, 1).el, -90, 0.01); const t = api.ptCalc(flat, 2); near(t.az, 0, 0.01); near(t.el, 0, 0.01);
});
test('turning east gives 90 degrees', () => {
  const c = Math.cos(-Math.PI / 2), s = Math.sin(-Math.PI / 2);
  near(api.ptCalc(mul([c, -s, 0, s, c, 0, 0, 0, 1], vert), 1).az, 90, 0.01);
});
test('tilting up 45 degrees gives 45 degrees elevation', () => {
  const a = Math.PI / 4;
  near(api.ptCalc(mul([1, 0, 0, 0, Math.cos(a), -Math.sin(a), 0, Math.sin(a), Math.cos(a)], vert), 1).el, 45, 0.01);
});
test('distance and bearing between two points', () => {
  const d = api.llDelta(34, -117, 34.01, -117);       // about 1.11 km due north
  near(d.m, 1112, 5, 'distance'); near(d.br, 0, 0.1, 'bearing');
});

// --- Matching a recording to aircraft ---
console.log('Aircraft matching');
test('the nearer aircraft ranks first and the pointed-at one has a small aim error', () => {
  const t0 = 1e12, lat0 = 34, lon0 = -117, kx = 111320 * Math.cos(lat0 * Math.PI / 180);
  const mk = (off, east) => ({ t: t0 + off, ac: [
    { hex: 'a1', flight: 'N123', type: 'C172', altFt: 3281, gs: 116.6, track: 270, lat: lat0, lon: lon0 + east / kx },
    { hex: 'b2', flight: 'UAL9', type: 'B738', altFt: 30000, gs: 450, track: 90, lat: lat0 + 0.2, lon: lon0 } ] });
  const samples = [];
  for (let k = 0; k <= 40; k++) samples.push({ t: t0 + k * 1000, azm: 90, el: Math.atan2(1000, 3000 - 60 * k) * 180 / Math.PI, azm2: 0, el2: 0 });
  const meta = { lat: lat0, lon: lon0, startMs: t0, durationMs: 40000,
    snaps: [mk(0, 3000), mk(15000, 2100), mk(30000, 1200), mk(40000, 600)], point: { decl: 0, mode: 1, samples } };
  const c = api.sources(meta);
  assert.strictEqual(c[0].type, 'C172');
  assert.ok(c[0].minR < 2000, 'closest approach should be under 2 km');
  assert.ok(c[0].aimErr < 15, 'aim error for the tracked plane should be small, got ' + c[0].aimErr);
  assert.ok(c[1].aimErr > 60, 'aim error for the distant jet should be large');
});

// --- Sound analysis ---
console.log('Sound analysis');
test('finds the fundamental and ranks the right preset first', () => {
  const sr = 44100, N = 4096, hz = sr / 2 / N;
  let seed = 7; const rnd = () => (seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296;
  const d = new Float32Array(N).map(() => -95 + rnd() * 4);
  const f0 = 8500 * 2 * (1 - 0.12 * 0.2) / 60;      // the "DJI Air 3" preset
  for (let k = 1; k <= 10; k++) { const c = Math.round(f0 * k / hz), L = -35 + 20 * Math.log10(Math.pow(0.8, k - 1)); for (let o = -1; o <= 1; o++) d[c + o] = Math.max(d[c + o], L - (o ? 6 : 0)); }
  const r = api.analyze(d, sr);
  near(r.f0, f0, f0 * 0.02, 'fundamental');
  assert.strictEqual(r.mt[0].n, 'DJI Air 3');
});
test('noise alone gives no clear tone', () => {
  let seed = 3; const rnd = () => (seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296;
  const r = api.analyze(new Float32Array(4096).map(() => -95 + rnd() * 6), 44100);
  assert.strictEqual(r.mt, null);
});
test('silence is reported as too quiet', () => {
  assert.ok(api.analyze(new Float32Array(4096).fill(-120), 44100).quiet);
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
