# References, pinned versions and licensing

## Toolchain (pinned in `gradle.properties` / `build.gradle`)

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.265 (runtime range `[21.1.265,21.2)` until older builds are tested) |
| FML loader | 4.x |
| ModDevGradle | 2.0.148 |
| Parchment | 1.21.1 / 2024.11.17 |
| Gradle | 9.2.1 (wrapper) |
| Java | 21 |
| JUnit | 5.11.3 |

## Reference projects (read for architecture, not copied)

Pinned to the head of each project's 1.21.1 branch as of 2026-11-19.

| Project | Branch | Commit | Licence |
|---|---|---|---|
| [C2ME](https://github.com/RelativityMC/C2ME-fabric) | `backports/1.21.1` | `12896cb2ad92ae31bf0d8e1562e5ee2144646f32` | LGPL-3.0-only (one subdirectory, `c2me-opts-accel-opencl/`, is All Rights Reserved) |
| [Moonrise](https://github.com/Tuinity/Moonrise) | `mc/1.21.1` | `2eae1b1f7318907cd9b6552e59156f6dc619b6cc` | GPL-3.0 |
| [Lithium](https://github.com/CaffeineMC/lithium) | `1.21.1` | `749edb8e3e2ce48a37e23cd457b97d46671524fb` | LGPL-3.0 |
| [Moonrise-Compats](https://github.com/DeusSixik/Moonrise-Compats) | `main` | `370ddfa42ecbda6fa83a9dbb9b109bb2bc9673b8` | not stated by GitHub — check before reading closely |

## Licensing

Chunk Express is licensed under the **GNU General Public License, version 3 only** (SPDX
`GPL-3.0-only`); the full text is in `LICENSE` and ships in every jar.

Chosen on 2026-11-20 from four questions:

| Question | Answer | Effect |
|---|---|---|
| Copy code from Moonrise / C2ME / Lithium? | No, clean-room only | No licence forced by reuse |
| Must modified forks stay open? | Yes | Copyleft: rules out MIT and Apache-2.0 |
| May closed-source mods build on Chunk Express' code? | No, only open-source mods | Strong copyleft: rules out LGPL |
| Explicit patent / contribution terms? | Keep it simple | GPL-3.0 (which has a patent clause) without extra terms |

"Only" rather than "or later": the terms are exactly GPL-3.0's, and a future GPL version does not
apply automatically. Changing that later needs the agreement of every copyright holder, so decide
before accepting outside contributions.

What it means in practice (a plain summary, not legal advice; the LICENSE text is what counts):

- Anyone may use, share and modify Chunk Express; distributed modified versions must stay GPL-3.0 and
  come with their source.
- Modpacks may include the unmodified jar. GPL-3.0 requires that recipients can get the source;
  for Chunk Express the usual way is a link to this project's public source (see GPL-3.0 section 6).
  Publish the source before or with the first public release.
- Code that copies from or builds on Chunk Express' code and is distributed must be GPL-3.0 as well.
- Reading C2ME, Lithium or Moonrise for ideas is unaffected. The project rule stays **clean-room
  only**: never paste or closely transliterate their source. GPL-3.0 would legally allow importing
  LGPL-3.0 or GPL-3.0 code with notices, but that is a deliberate project decision, not a default;
  any such reuse must name the source file and commit in the commit message.
