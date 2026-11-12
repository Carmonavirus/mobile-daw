cmake_minimum_required(VERSION 3.22.1)
project(mobiledaw LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

# Extra instrument engines and the free third-party DSP code they use (see THIRD_PARTY_NOTICES.md).
file(GLOB_RECURSE PLAITS_SOURCES third_party/plaits/plaits/*.cc third_party/plaits/stmlib/*.cc)
file(GLOB_RECURSE DAISYSP_SOURCES third_party/daisysp/Source/*.cpp)
file(GLOB STK_SOURCES third_party/stk/src/*.cpp)
add_library(dawengines STATIC
        SoundEngines.cpp
        MacroVoice.cpp
        ModelVoice.cpp
        ${PLAITS_SOURCES}
        ${DAISYSP_SOURCES}
        ${STK_SOURCES})
target_include_directories(dawengines PUBLIC
        .
        third_party/tsf
        third_party/plaits
        third_party/daisysp/Source
        third_party/daisysp/Source/Utility
        third_party/stk/include)
# TEST selects stmlib's portable code paths (no ARM Cortex-M assembly).
target_compile_definitions(dawengines PRIVATE TEST __LITTLE_ENDIAN__)
target_compile_options(dawengines PRIVATE -O2 -w)

add_library(mobiledaw SHARED
        DawCore.cpp
        wavesynth/WaveSynth.cpp
        wavesynth/Wavetables.cpp
        AudioEngine.cpp
        jni_bridge.cpp
        synth_jni.cpp)

target_compile_options(mobiledaw PRIVATE -Wall -Wextra -ffast-math)
target_link_libraries(mobiledaw dawengines oboe::oboe android log)
