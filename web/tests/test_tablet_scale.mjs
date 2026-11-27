// test_tablet_scale.mjs - runtime test for tablet-scale.js.
// Run: node web/tests/test_tablet_scale.mjs

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC = fs.readFileSync(
  path.join(root, 'src', 'patches', 'web_optimizations', 'tablet-scale.js'),
  'utf8',
);

const ORIGINAL =
  'width=device-width, initial-scale=1.0, viewport-fit=cover, maximum-scale=1.0, interactive-widget=resizes-content';

const MOBILE_UA =
  'Mozilla/5.0 (Linux; Android 17; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const DESKTOP_UA =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Antigravity/2.35.2';

function assert(cond, msg) {
  if (!cond) throw new Error('assert: ' + msg);
}

function makeEnv(deviceWidth, ua, withMeta = true) {
  const listeners = {};
  const meta = withMeta
    ? {
        content: ORIGINAL,
        getAttribute(name) {
          return name === 'content' ? this.content : null;
        },
        setAttribute(name, value) {
          if (name === 'content') this.content = value;
        },
      }
    : null;
  const doc = {
    querySelector(sel) {
      return sel === 'meta[name="viewport"]' ? meta : null;
    },
  };
  const win = {
    innerWidth: deviceWidth,
    navigator: { userAgent: ua },
    addEventListener(type, fn) {
      listeners[type] = fn;
    },
  };
  const sandbox = { global: win, doc, window: win, document: doc, console };
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox, { filename: 'tablet-scale.js' });
  return {
    metaContent() {
      return meta ? meta.content : null;
    },
    setDeviceWidth(w) {
      const m = /initial-scale=([\d.]+)/.exec(meta ? meta.content : '');
      const s = m ? parseFloat(m[1]) : 1;
      win.innerWidth = w / s;
      if (listeners.resize) listeners.resize();
    },
  };
}

function scaledMeta(width, scale) {
  return (
    'width=' + Math.round(width / scale) +
    ', initial-scale=' + scale +
    ', viewport-fit=cover, maximum-scale=' + scale +
    ', interactive-widget=resizes-content'
  );
}

// 1. Phone widths: meta untouched.
let env = makeEnv(390, MOBILE_UA);
assert(env.metaContent() === ORIGINAL, 'phone 390px must not touch meta, got ' + env.metaContent());
env = makeEnv(599, MOBILE_UA);
assert(env.metaContent() === ORIGINAL, '599px must not touch meta, got ' + env.metaContent());

// 2. Tablet steps (narrowed layout width + scale + interactive-widget preserved).
env = makeEnv(600, MOBILE_UA);
assert(env.metaContent() === scaledMeta(600, 1.2), '600px must scale 1.2x, got ' + env.metaContent());
env = makeEnv(800, MOBILE_UA);
assert(env.metaContent() === scaledMeta(800, 1.3), '800px must scale 1.3x, got ' + env.metaContent());
env = makeEnv(1280, MOBILE_UA);
assert(env.metaContent() === scaledMeta(1280, 1.4), '1280px must scale 1.4x, got ' + env.metaContent());

// 3. Desktop Mode UA: never scale, even on a wide tablet.
env = makeEnv(1280, DESKTOP_UA);
assert(env.metaContent() === ORIGINAL, 'desktop UA must not touch meta, got ' + env.metaContent());

// 4. Resize follows the width and restores original when narrow.
env = makeEnv(390, MOBILE_UA);
assert(env.metaContent() === ORIGINAL, 'starts unscaled at 390px');
env.setDeviceWidth(800);
assert(env.metaContent() === scaledMeta(800, 1.3), 'resize to 800px must scale 1.3x, got ' + env.metaContent());
env.setDeviceWidth(390);
assert(env.metaContent() === ORIGINAL, 'resize back to 390px must restore original meta, got ' + env.metaContent());

// 5. Step up/down while already scaled: no oscillation, device width recovered.
env = makeEnv(800, MOBILE_UA);
assert(env.metaContent() === scaledMeta(800, 1.3), 'starts scaled 1.3x at 800px');
env.setDeviceWidth(1280);
assert(env.metaContent() === scaledMeta(1280, 1.4), 'resize 800->1280 must step up to 1.4x, got ' + env.metaContent());
env.setDeviceWidth(640);
assert(env.metaContent() === scaledMeta(640, 1.2), 'resize 1280->640 must step down to 1.2x, got ' + env.metaContent());

// 6. Missing viewport meta: no crash, nothing to scale.
env = makeEnv(800, MOBILE_UA, false);
assert(env.metaContent() === null, 'missing meta must stay missing');

console.log('test_tablet_scale.mjs: OK');
