#!/usr/bin/env bash
# Compiles the Android app's audio engine (app/src/main/cpp) to WebAssembly for the web app.
# Needs Emscripten (emcc) on the PATH. Output: web/public/engine.wasm
set -euo pipefail
cd "$(dirname "$0")/../.."
CPP=app/src/main/cpp
TP=$CPP/third_party
OUT=web/engine/out
mkdir -p "$OUT/obj" web/public
INC="-I$CPP -I$TP/tsf -I$TP/plaits -I$TP/daisysp/Source -I$TP/daisysp/Source/Utility -I$TP/stk/include"
FLAGS="-std=c++17 -O3 -fno-exceptions -fno-rtti"
pids=()
objs=()
# Third-party DSP used by the extra sound engines (DawCore links against them).
for f in $(find $TP/plaits/plaits $TP/plaits/stmlib -name '*.cc') $(find $TP/daisysp/Source -name '*.cpp') $TP/stk/src/*.cpp \
         $CPP/SoundEngines.cpp $CPP/MacroVoice.cpp $CPP/ModelVoice.cpp; do
  o="$OUT/obj/$(echo "$f" | tr '/' '_').o"
  objs+=("$o")
  if [ ! -f "$o" ] || [ "$f" -nt "$o" ]; then
    em++ -std=c++17 -O3 -w -DTEST -D__LITTLE_ENDIAN__ $INC -c "$f" -o "$o" &
    pids+=($!)
  fi
  if [ "${#pids[@]}" -ge 8 ]; then wait "${pids[@]}"; pids=(); fi
done
[ "${#pids[@]}" -gt 0 ] && wait "${pids[@]}"
em++ $FLAGS -Wall -Wextra $INC \
  web/engine/web_api.cpp $CPP/DawCore.cpp $CPP/fx/*.cpp $CPP/wavesynth/*.cpp "${objs[@]}" \
  --no-entry -sSTANDALONE_WASM -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=64MB -sSTACK_SIZE=1MB \
  -sEXPORTED_FUNCTIONS=_malloc,_free -o web/public/engine.wasm
ls -l web/public/engine.wasm
