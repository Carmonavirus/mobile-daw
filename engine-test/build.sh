#!/usr/bin/env bash
# Builds the desktop engine smoke test (same sources as the app's native library, minus Oboe/JNI).
set -euo pipefail
cd "$(dirname "$0")/.."
CPP=app/src/main/cpp
TP=$CPP/third_party
OUT=engine-test/out
mkdir -p "$OUT/obj"
INC="-I$CPP -I$TP/tsf -I$TP/plaits -I$TP/daisysp/Source -I$TP/daisysp/Source/Utility -I$TP/stk/include"
pids=()
objs=()
for f in $(find $TP/plaits/plaits $TP/plaits/stmlib -name '*.cc') $(find $TP/daisysp/Source -name '*.cpp') $TP/stk/src/*.cpp \
         $CPP/SoundEngines.cpp $CPP/MacroVoice.cpp $CPP/ModelVoice.cpp; do
  o="$OUT/obj/$(echo "$f" | tr '/' '_').o"
  objs+=("$o")
  if [ ! -f "$o" ] || [ "$f" -nt "$o" ]; then
    g++ -std=c++17 -O2 -w -DTEST -D__LITTLE_ENDIAN__ $INC -c "$f" -o "$o" &
    pids+=($!)
  fi
  if [ "${#pids[@]}" -ge 8 ]; then wait "${pids[@]}"; pids=(); fi
done
[ "${#pids[@]}" -gt 0 ] && wait "${pids[@]}"
g++ -std=c++17 -O2 -Wall -Wextra $INC engine-test/main.cpp $CPP/DawCore.cpp $CPP/fx/*.cpp $CPP/wavesynth/*.cpp "${objs[@]}" -pthread -o "$OUT/enginetest"
# Other tests that link the whole engine (DawCore needs the sound engine objects above).
# The MP3 encoder (LAME) is C.
lame=()
for f in $TP/lame/libmp3lame/*.c; do
  o="$OUT/obj/$(echo "$f" | tr '/' '_').o"
  lame+=("$o")
  if [ ! -f "$o" ] || [ "$f" -nt "$o" ]; then
    gcc -O2 -w -DHAVE_CONFIG_H -I$TP/lame -I$TP/lame/include -I$TP/lame/libmp3lame -c "$f" -o "$o" &
    pids+=($!)
  fi
done
[ "${#pids[@]}" -gt 0 ] && wait "${pids[@]}"
pids=()
for t in fx_test:fxtest metronome_test:metronometest export_test:exporttest; do
  src="engine-test/${t%%:*}.cpp"
  [ -f "$src" ] && g++ -std=c++17 -O2 -Wall -Wextra $INC "$src" $CPP/DawCore.cpp $CPP/fx/*.cpp $CPP/wavesynth/*.cpp -I$TP/lame/include $CPP/export/*.cpp "${objs[@]}" "${lame[@]}" -pthread -lm -o "$OUT/${t##*:}"
done
true
