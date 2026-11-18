// Fills in the offline service worker (site/sw.js) for one build: the build name and a content
// hash of every file in the site, split into the main app and classic/ (older browsers).
// Usage: node web/app/tools/offline-files.mjs <site folder> <build>
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const [root, build] = process.argv.slice(2);
if (!root || !build) throw new Error('usage: offline-files.mjs <site folder> <build>');
const skip = (rel) => rel === 'sw.js' || rel === '.nojekyll' || rel.endsWith('.map') || rel.endsWith('.LICENSE.txt');
const files = { main: {}, classic: {} };
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    const rel = path.relative(root, full).split(path.sep).join('/');
    if (e.isDirectory()) { walk(full); continue; }
    if (skip(rel)) continue;
    const hash = crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex').slice(0, 16);
    files[rel.startsWith('classic/') ? 'classic' : 'main'][rel] = hash;
  }
})(root);
const swPath = path.join(root, 'sw.js');
let sw = fs.readFileSync(swPath, 'utf8');
if (!sw.includes('__FILES__') || !sw.includes("'__BUILD__'")) throw new Error('sw.js has no placeholders');
sw = sw.replace("'__BUILD__'", JSON.stringify(build)).replace('__FILES__', JSON.stringify(files, null, 1));
fs.writeFileSync(swPath, sw);
const count = (g) => Object.keys(files[g]).length;
console.log(`sw.js: ${count('main')} app files, ${count('classic')} classic files, build ${build}`);
