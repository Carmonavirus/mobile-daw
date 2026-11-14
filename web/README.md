# Mobile DAW for the web

The browser version of the app: same project files, same sound engine.

- `engine/` – `web_api.cpp` exposes the Android app's C++ engine (`app/src/main/cpp`, unchanged) as
  plain C functions; `build.sh` compiles it with Emscripten to `public/engine.wasm`.
- `public/worklet.js` – AudioWorklet that runs the engine on the browser's audio thread.
- `src/` – Preact + TypeScript UI: channel rack, piano roll, playlist, mixer, songs.
  `src/model/project.ts` mirrors `Project.kt`, so a project file opens on both.

```
source /path/to/emsdk/emsdk_env.sh
bash engine/build.sh     # once, and after engine changes
npm install
npm run dev              # http://localhost:5173
npm test                 # engine smoke test
```

GitHub Actions (`.github/workflows/web.yml`) builds every push and publishes the install branch to
GitHub Pages.

Offline: the published site has a service worker (`web/app/src/wasmJsMain/resources/sw.js`). The
publish step stamps it with the build and a content hash of every file
(`web/app/tools/offline-files.mjs`), so after one visit the whole app works with no internet and an
update only downloads changed files. The page itself is always fetched from the network first (the
saved copy is used only offline), so updates and the reload-once check keep working.
`web/app/tests/offline.mjs` tests it on the assembled site.
