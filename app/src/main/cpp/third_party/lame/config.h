/* Hand-written config.h for building libmp3lame 3.100 with the NDK or a desktop compiler
   (stands in for the autoconf one). Mobile DAW only uses the encoder, not LAME's mp3 decoder. */
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1
#define PROTOTYPES 1

typedef float ieee754_float32_t;

#endif
