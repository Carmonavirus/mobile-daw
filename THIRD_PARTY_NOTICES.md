# Third-party notices

Mobile DAW includes the following third-party code and content. Each is used under the licence reproduced below.

## TinySoundFont

SoundFont (.sf2/.sf3) player, by Bernhard Schelling. Source: https://github.com/schellingb/TinySoundFont. Location: `app/src/main/cpp/third_party/tsf`.

```
Copyright (C) 2017-2023 Bernhard Schelling (Based on SFZero, Copyright (C) 2012 Steve Folta, https://github.com/stevefolta/SFZero)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Plaits and stmlib (Mutable Instruments)

Synthesis engines behind the Macro synth, by Emilie Gillet. Source: https://github.com/pichenettes/eurorack. Location: `app/src/main/cpp/third_party/plaits`. Modified for Mobile DAW: the six-op FM and speech engines and their ROM data tables were removed, and nothing else was changed in the DSP code.

```
Except when noted otherwise, all code is copyright Emilie Gillet and is
released under the MIT License with the following notice:

Copyright 2012 Emilie Gillet.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

-------------------------------------------------------------------------------

The code in third_party/STM is from STMicroelectronics, and released with the
following notice:

THE PRESENT FIRMWARE WHICH IS FOR GUIDANCE ONLY AIMS AT PROVIDING CUSTOMERS
WITH CODING INFORMATION REGARDING THEIR PRODUCTS IN ORDER FOR THEM TO SAVE TIME.
AS A RESULT, STMICROELECTRONICS SHALL NOT BE HELD LIABLE FOR ANY DIRECT,
INDIRECT OR CONSEQUENTIAL DAMAGES WITH RESPECT TO ANY CLAIMS ARISING FROM THE
CONTENT OF SUCH FIRMWARE AND/OR THE USE MADE BY CUSTOMERS OF THE CODING
INFORMATION CONTAINED HEREIN IN CONNECTION WITH THEIR PRODUCTS.

-------------------------------------------------------------------------------

The code in programming/serial is from Ivan A-R, and released under the GPL with
the following notice:

Author: Ivan A-R <ivan@tuxotronic.org>
Project page: http://tuxotronic.org/wiki/projects/stm32loader

This file is part of stm32loader.

stm32loader is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free
Software Foundation; either version 3, or (at your option) any later
version.

stm32loader is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
for more details.

You should have received a copy of the GNU General Public License
along with stm32loader; see the file COPYING3.  If not see
<http://www.gnu.org/licenses/>.```

## DaisySP

DSP modules behind the Modeled instruments, by Electrosmith. Source: https://github.com/electro-smith/DaisySP. Location: `app/src/main/cpp/third_party/daisysp`. Only the MIT-licensed modules are compiled; the LGPL modules are not used. Modified for Mobile DAW: the ARM min/max assembly in Utility/dsp.h is only used where the CPU supports it.

```
DaisySP, copyright (c) 2020 Electrosmith, Corp. 

Published under the MIT license:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

-------------------------------------------------------------------------------

Plaits, copyright 2016 Emilie Gillet (emilie.o.gillet@gmail.com)

Published under the MIT license:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

-------------------------------------------------------------------------------

Soundpipe, copyright 2015 Paul Batchelor

Published under the MIT license:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.```

## The Synthesis ToolKit in C++ (STK)

Physical models behind the Modeled instruments, by Perry R. Cook and Gary P. Scavone. Source: https://github.com/thestk/stk. Location: `app/src/main/cpp/third_party/stk` (a subset of classes).

```
The Synthesis ToolKit in C++ (STK)

Copyright (c) 1995-2023 Perry R. Cook and Gary P. Scavone

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

Any person wishing to distribute modifications to the Software is
asked to send the modifications to the original developer so that they
can be incorporated into the canonical version.  This is, however, not
a binding provision of this license.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## GeneralUser GS

The built-in General MIDI SoundFont, by S. Christian Collins. Source: https://github.com/mrbumpy409/GeneralUser-GS. It is downloaded at build time (pinned commit, checksum verified) and bundled in the APK under `assets/soundfonts`.

```
*** GeneralUser GS v2.0.3 ***
***      License v2.0     ***

** License of the complete work **
You may use GeneralUser GS without restriction for your own music creation,
private or commercial. This SoundFont bank is provided to the community free of
charge. Please feel free to use it in your software projects, and to modify the
SoundFont bank or its packaging to suit your needs.

** License of contained samples **
GeneralUser GS inherits the usage rights of the samples contained within, all of
which allow full use in music production, including the ability to make profit
from musical recordings created with GeneralUser GS.

Many of the samples are original, but some were taken from other banks freely
(and legally) available on the Internet from various SoundFont websites. Because
GeneralUser GS originated as a personal project with no intention for
publication, I cannot be 100% sure where all of the samples originated, although
I do know that none of them came from commercially published SoundFont packages
or sample CDs. Regardless, many "free" SoundFonts available on the web may
indeed contain samples of questionable origin. My understanding of the
copyrights of all samples is only as good as the information provided by the
original sources. If you become aware of any restricted samples being used in
GeneralUser GS, please let me know so I can replace them.

This uncertainty may concern you if you intend to use GeneralUser GS in a
commercial software product. That being said, I have never received any
complaint regarding sample ownership since I published the original GeneralUser
GS back in 2000, and as far as I am aware, neither have any of the companies
creating commercial software products using GeneralUser GS.

** More info **
If you plan to feature GeneralUser GS on your own website, please do not link
directly to my download files. Either link to my website, or provide your own
local copy instead.

I hope you enjoy GeneralUser GS! This SoundFont bank is the product of many
years of hard work.

You can find updates to GeneralUser GS and more of my virtual instruments at:
http://www.schristiancollins.com

I can be reached via the contact page on my website here:
https://www.schristiancollins.com/contact

Thank you!
-~Chris
```

## Online sounds

Sounds found through Freesound, Wikimedia Commons and the Internet Archive are not bundled. Each one a user saves keeps its author, licence and page link, listed in Settings > Sounds > Sound library > Credits.

## LAME

MP3 encoder used by song export, version 3.100, from https://lame.sourceforge.io (lame-3.100.tar.gz, SHA-256 ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e). Location: `app/src/main/cpp/third_party/lame`. Only the encoder library (`libmp3lame`) and `include/lame.h` are included, plus a hand-written `config.h` for the build; the mp3 decoder (`mpglib_interface.c`) was left out. Nothing else was changed. LAME is licensed under the GNU Library General Public License, version 2 or later; the full licence text is in `app/src/main/cpp/third_party/lame/COPYING`, and LAME's source can be obtained from the address above.

```
Copyright (c) 1999-2017 The LAME Project
Copyright (c) 1999-2008 Mark Taylor and others (see the AUTHORS of LAME)

This library is free software; you can redistribute it and/or modify it under
the terms of the GNU Library General Public License as published by the Free
Software Foundation; either version 2 of the License, or (at your option) any
later version.
```

## Web version only

- **Roboto** fonts (`web/app/src/wasmJsMain/resources/fonts`), from https://github.com/googlefonts/roboto-2, Copyright 2011 Google Inc., licensed under the Apache License, Version 2.0. The browser version bundles them because browsers give the app no system font (Android has Roboto built in).
- **fflate** 0.8.2 (`web/app/src/wasmJsMain/resources/fflate.js`), https://github.com/101arrowz/fflate, Copyright (c) 2023 Arjun Barrelet, MIT License. Reads and writes .zip files (kit imports, stem export) in the browser.
