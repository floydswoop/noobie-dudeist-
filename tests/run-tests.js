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
  querySelector: () => node(), querySelectorAll: () => [], addEventListener() {}, setAttribute() {}, focus() {}, width: 320, height: 120, value: '1',
});
const cache = {};
global.document = { querySelector: s => cache[s] || (cache[s] = node()), createElement: () => node() };
global.performance = { now: () => 0 };
global.requestAnimationFrame = () => {};
global.window = global;

const api = new Function(js + ';return {ridParse, ptCalc, sources, analyze, llDelta, cbSummary, rdSolve, rdPlace, rdDiff, soundSpeed, wxHints, wxLine, LIB}')();

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

// --- Combined scan readout ---
console.log('Combined scan readout');
const idle = { rec: false, micErr: null, snd: null, ovErr: null, ov: null, ridOn: false, ridErr: null, ridInfo: '', rid: [], phone: null, now: 100000 };
test('before any data, each line says what it is waiting for', () => {
  const s = api.cbSummary(idle);
  assert.strictEqual(s.sound, 'Listening...');
  assert.strictEqual(s.air, 'Checking for aircraft...');
  assert.strictEqual(s.drone, 'Drone scan is off.');
});
test('sound line: quiet, no tone, tone, recording, microphone error', () => {
  assert.ok(/Too quiet/.test(api.cbSummary({ ...idle, snd: { quiet: 1 } }).sound));
  assert.strictEqual(api.cbSummary({ ...idle, snd: { f0: 300, mt: null } }).sound, 'No clear repeating tone.');
  const t = api.cbSummary({ ...idle, snd: { f0: 276.4, count: 8, mt: [{ n: 'DJI Air 3', dr: 1 }, { n: 'Box fan', dr: 0 }] } }).sound;
  assert.ok(/Tone at 276 Hz, 8 of 8 harmonics/.test(t) && /DJI Air 3 \(drone\), Box fan \(not a drone\)/.test(t));
  assert.ok(/Paused/.test(api.cbSummary({ ...idle, rec: true, snd: { quiet: 1 } }).sound));
  assert.strictEqual(api.cbSummary({ ...idle, micErr: 'Microphone unavailable (NotAllowedError).' }).sound, 'Microphone unavailable (NotAllowedError).');
});
test('aircraft line: none, nearest aircraft, and error', () => {
  assert.strictEqual(api.cbSummary({ ...idle, ov: { t: 100000, all: 0, near: null } }).air, 'No aircraft reported nearby.');
  const a = api.cbSummary({ ...idle, ov: { t: 95000, all: 3, near: { type: 'C172', flight: 'N123', hex: 'a1', altFt: 3281, distM: 2100, bearing: 45 } } }).air;
  assert.ok(/3 aircraft nearby. Nearest: C172 N123, 2.1 km NE, 3,281 ft, 25° above the horizon. Updated 5 s ago./.test(a), a);
  assert.ok(/on ground/.test(api.cbSummary({ ...idle, ov: { t: 100000, all: 1, near: { type: 'C172', hex: 'a1', altFt: 'ground', distM: 800, bearing: 200 } } }).air));
  assert.strictEqual(api.cbSummary({ ...idle, ovErr: 'Location permission was denied.' }).air, 'Location permission was denied.');
});
test('drone line: error, nothing heard, and nearest drones first', () => {
  assert.ok(/could not start: Permission was denied/.test(api.cbSummary({ ...idle, ridErr: 'Permission was denied.' }).drone));
  const none = api.cbSummary({ ...idle, ridOn: true, ridInfo: 'Bluetooth on.' }).drone;
  assert.ok(/^None heard yet. Bluetooth on./.test(none) && /Only drones that broadcast Remote ID/.test(none));
  const phone = { lat: 34, lon: -117 };
  const mk = (id, dLat) => ({ id, uaType: 2, lat: 34 + dLat, lon: -117 });
  const d = api.cbSummary({ ...idle, ridOn: true, phone, rid: [mk('FAR', 0.004), mk('NEAR', 0.001), mk('MID', 0.002), mk('X4', 0.005), mk('X5', 0.006)] }).drone;
  assert.ok(/^5 broadcasting: NEAR \(Multirotor or helicopter\), 111 m N of you; MID/.test(d), d);
  assert.ok(/and 2 more\.$/.test(d), d);
});

test('radar: aircraft due east, north-up, lands right of centre', () => {
  const p = api.rdPlace(5000, 0, 10000, 0);
  assert(Math.abs(p.x - 0.5) < 1e-9 && Math.abs(p.y) < 1e-9 && Math.abs(p.bearing - 90) < 1e-9);
});
test('radar: pointing up rotates the picture so the heading is up', () => {
  const p = api.rdPlace(5000, 0, 10000, 90);   // facing east: the eastern plane is dead ahead
  assert(Math.abs(p.x) < 1e-9 && Math.abs(p.y - 0.5) < 1e-9);
  const q = api.rdPlace(0, 5000, 10000, 90);   // north plane is to the left when facing east
  assert(q.x < -0.49 && Math.abs(q.y) < 1e-9);
});
test('radar: sound delay for a stationary source is slant range / speed of sound', () => {
  const r = api.rdSolve({ distM: 4200, bearing: 0, altFt: 2500, gs: 0, track: 0 }, 10, 343);
  assert(Math.abs(r.tau - 12.445) < 0.01, 'tau ' + r.tau);          // hypot(4200, 762) / 343
  assert(r.lagM === 0 && Math.abs(r.hBr - r.nowBr) < 1e-9);
});
test('radar: sound comes from where a moving plane was tau seconds ago', () => {
  // 100 kt due east, 4 km north of you, level with you: tau = 4000 / sqrt(343^2 - 51.444^2)
  const r = api.rdSolve({ distM: 4000, bearing: 0, altFt: 0, gs: 100, track: 90 }, 0, 343);
  assert(Math.abs(r.tau - 11.795) < 0.01, 'tau ' + r.tau);
  assert(Math.abs(r.he + 606.8) < 1, 'he ' + r.he);                  // it was 607 m further west
  assert(Math.abs(r.nowBr) < 1e-9 && Math.abs(r.hBr - 351.38) < 0.1, 'hBr ' + r.hBr);
  assert(Math.abs(Math.hypot(r.he, r.hn) / 343 - r.tau) < 1e-6);   // self-consistent
});
test('radar: report age moves the plane along its track, and reports older than 45 s are capped', () => {
  const r = api.rdSolve({ distM: 1000, bearing: 0, altFt: 0, gs: 100, track: 90 }, 10, 343);
  assert(Math.abs(r.e - 514.44) < 0.1 && Math.abs(r.n - 1000) < 1e-6);
  const old = api.rdSolve({ distM: 1000, bearing: 0, altFt: 0, gs: 100, track: 90 }, 500, 343);
  assert(Math.abs(old.e - 51.444 * 45) < 0.1);
});
test('radar: a fast jet flying away is placed kilometres behind where it is now', () => {
  // level, 10 km north, 450 kt (231.5 m/s) straight away from you: tau = 10000 / (343 + 231.5)
  const r = api.rdSolve({ distM: 10000, bearing: 0, altFt: 0, gs: 450, track: 0 }, 0, 343);
  assert(Math.abs(r.tau - 17.41) < 0.02 && Math.abs(r.lagM - 4030) < 10, 'tau ' + r.tau + ' lag ' + r.lagM);
  // and one crossing at right angles, high up, is self-consistent
  const q = api.rdSolve({ distM: 10000, bearing: 90, altFt: 30000, gs: 450, track: 0 }, 0, 343);
  assert(Math.abs(Math.hypot(q.he, q.hn, q.alt) / 343 - q.tau) < 1e-3, 'not converged: ' + q.tau);
});
test('radar: heights are measured from the phone, not from sea level', () => {
  // plane at 2500 ft above sea level, phone on a hill 300 m above sea level: only 462 m above the phone
  const r = api.rdSolve({ distM: 3000, bearing: 0, altFt: 2500, gs: 0, track: 0 }, 0, 343, 300);
  assert(Math.abs(r.alt - 462) < 0.5, 'alt ' + r.alt);
  assert(Math.abs(r.nowEl - Math.atan2(462, 3000) * 180 / Math.PI) < 0.01);
});
test('radar: angle difference wraps around north', () => {
  assert.strictEqual(api.rdDiff(359, 2), 3);
  assert.strictEqual(api.rdDiff(90, 270), 180);
});

console.log('Weather');
test('speed of sound follows air temperature and falls back to 343 m/s without a reading', () => {
  near(api.soundSpeed(0), 331.3, 0.05, '0 C');
  near(api.soundSpeed(15), 340.3, 0.1, '15 C');
  near(api.soundSpeed(20), 343.2, 0.1, '20 C');
  assert.strictEqual(api.soundSpeed(undefined), 343);
  assert.strictEqual(api.soundSpeed(200), 343);   // implausible value is ignored
});
test('cold air lengthens the sound delay', () => {
  const a = { distM: 3430, bearing: 0, altFt: 0, gs: 0, track: 0 };
  const warm = api.rdSolve(a, 0, api.soundSpeed(20)), cold = api.rdSolve(a, 0, api.soundSpeed(-10));
  assert.ok(cold.tau > warm.tau + 0.2, `cold ${cold.tau} warm ${warm.tau}`);
});
test('listening hints: wind noise, direction, rain, visibility, and the all-clear', () => {
  assert.ok(api.wxHints({ tempC: 15, windMs: 7, windDir: 315 }).join(' ').includes('Wind this strong'));
  assert.ok(api.wxHints({ tempC: 15, windMs: 4, windDir: 315 }).join(' ').includes('from the NW carry farther'));
  assert.ok(api.wxHints({ tempC: 15, windMs: 4, windDir: 315 }).join(' ').includes('from the SE are weaker'));
  assert.ok(api.wxHints({ tempC: 15, precipMm: 0.4 }).join(' ').includes('Rain'));
  assert.ok(api.wxHints({ tempC: 15, visM: 1500 }).join(' ').includes('Low visibility'));
  assert.deepStrictEqual(api.wxHints({ tempC: 15, windMs: 1, cloudPct: 20 }), ['Conditions are good for listening.']);
});
test('clip weather line', () => {
  const l = api.wxLine({ tempC: 15.2, windMs: 5.4, windDir: 90, cloudPct: 40, precipMm: 0 });
  assert.strictEqual(l, '15 \u00b0C, wind 5 m/s from E, cloud 40%. Speed of sound 340 m/s.');
});

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
