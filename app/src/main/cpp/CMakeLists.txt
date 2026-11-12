cmake_minimum_required(VERSION 3.22.1)
project(mobiledaw LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(mobiledaw SHARED
        DawCore.cpp
        wavesynth/WaveSynth.cpp
        wavesynth/Wavetables.cpp
        AudioEngine.cpp
        jni_bridge.cpp
        synth_jni.cpp)

target_compile_options(mobiledaw PRIVATE -Wall -Wextra -ffast-math)
target_link_libraries(mobiledaw oboe::oboe android log)
