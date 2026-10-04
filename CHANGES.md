# Changes vs upstream

Every change made to run AMXX as a **64-bit-cell** build inside Nexora (Xash3D) on
**aarch64 Android**, delivered through a patcher APK.

Upstream baseline: `alliedmodders/amxmodx` **master** (fetched fresh in CI) + the in-order
`patches/` below. Nothing else is vendored into the tree.

## 64-bit cell enablement

The upstream build defaults to `PAWN_CELL_SIZE=32`. On aarch64 `amx_BrowseRelocate` cannot store
a function pointer in a 4-byte cell; the whole toolchain must use 64-bit cells.

- Force `-DPAWN_CELL_SIZE=64` when compiling core, modules and the compiler (build flags in
  `android/ci/build-amxx.sh`).
- `amxmodx-64bit-cell-casts.diff` — `file.cpp` `ke::Max(0, params[2])`: cast the literal to `cell`
  (two call sites) so the amtl template <cell> accepts the 0 when deduced as `int`.
- `amxmodx-amtl-64bit.diff` — amtl (submodule `public/amtl`) `Min`/`Max` only had a one-argument
  template `<T>`; 64-bit builds mix `cell` and `long`/`int`, so give them a two-template
  `Min<T1,T2>`/`Max<T1,T2>` with `decltype` return. This replaces the need for every per-callsite
  cast in the codebase (e.g. `string.cpp` `ke::Min(params[arg_inputsize], inputLength)`).
- `amxmodx-CDetour-cell.diff` — `public/memtools/CDetour/detours.h` defined `typedef int32_t cell`,
  which collided with the 64-bit `cell` from `amx.h`/`amxxmodule.h`. The port defines
  `cell_t32`/`cell_t64` and picks the right one via `#ifdef PAWN_CELL_SIZE` (matching the 64-bit
  driver, unlike a plain `AMX_H_INCLUDED` guard, because `amxxmodule.h` itself typedefs `cell`).
- `amxmodx-pawncc-64bit.patch` — compiler side:
  - `compiler/amxxpc/amxxpc.cpp` — `dlsym("Compile64")` with `Compile32` fallback (the 64-bit
    `libpawnc` exports `Compile64`), and write `pl32.cellsize = (char)sizeof(cell)` instead of the
    hardcoded `4` (which made the runtime reject every plugin with "section not found").
  - `compiler/sc1.c` — drop `#include <prefix.h>`: upstream has no `compiler/linux/` BinReloc
    support on master.
  - build flags include `-DLINUX` so `sclinux.h` (stricmp/strnicmp, `<unistd.h>`) is used.
- `amxmodx-memtools-dlfcn.diff` — `public/memtools/MemoryUtils.cpp` Linux path needs
  `#include <dlfcn.h>` on the upstream tree.

## Base-system porting (metamod, modules, plugin compilation)

- `metamod-p-aarch64.patch` — port of **Bots-United/metamod-p** to aarch64 Android:
  Android-independent loader used as the AMXX metaplugin host (`libmetamod.so`).
  (Replaces the abandoned `metamod-fwgs` fork approach.)
- `amxmodx-android-load-CModule.patch` — Android module loading goes through
  `dlopen`/`dlsym`/`dlclose` (no `__android_log_print` dependency, no mod-relative path assumption).
- `amxmodx-android-load-modules.patch` — `modules.cpp` resolves the modules dir on Linux/Android
  via dlopen from `<gamedir>/addons/amxmodx/modules/`.
- Vendored `android/hlsdk` — Half-Life SDK headers (fork SDK, `interface.h` included) required to
  compile AMXX/metamod.
- **Host `pawncc`** — libpawnc + amxxpc driver compiled for the runner (in CI) with
  `-DPAWN_CELL_SIZE=64`; every `.sma` is compiled into a 64-bit `.amxx` and shipped so core and
  plugin cell sizes always match.

## Release pipeline (from scratch)

- `android/ci/build-amxx.sh` — one script: fetch upstream + patches, then cross-compile
  `libamxmodx.so`, the 11 modules, `libmetamod.so`, static pcre and the host pawncc with the NDK.
- `android/ci/gen-bundle.py` — produces `amxx-bundle.zip` (STORE 13 libraries + fold the whole
  `addons/` tree DEFLATED + freshly compiled plugins) with a `bundle.json` manifest consumed by the
  patcher; plus `amxx-plugins.zip` / `amxx-addons.zip`.
- `.github/workflows/build-and-release.yml` — fetch upstream + patches, native build, plugin
  compile, bundle, release upload; `app-apk` job embeds the bundle into app assets for offline use.

## Patcher APK

- Picks a Nexora APK, validates ABI (`arm64-v8a` only), prunes the exact 13 AMXX/metamod
  libraries + `META-INF/` (previously a `lib/arm64-v8a/lib` **prefix** match would have deleted
  every native engine library), injects the bundle payload (16 KB-aligned STORED `.so`, DEFLATED
  addons) and re-signs with the bundled keystore.
- Bundle source: newest GitHub release, embedded asset as offline fallback.

## ReGameDLL / CS game DLL first-spawn fix

- The fix is committed in the ReGameDLL_CS fork (`berkchy/ReGameDLL_CS`,
  `fix/first-spawn-equip`, `regamedll/dlls/player.cpp`) that produces `libcs.so`.

  Symptom: joining a server, the player's **first** spawn happens unarmed
  (no ammo HUD, cannot fire); switching team and respawning fixes it.

  Cause: `CBasePlayer::Spawn()` can run while the joining state machine has not
  reached `GetIntoGame()` yet (round-restart/respawn firing right after
  `JoinTeam` set `GETINTOGAME`). `m_bJustConnected` is still true, so
  `PlayerSpawn` skips `OnSpawnEquip`, and `OnSpawnEquip` skips
  `GiveDefaultItems` while `m_bNotKilled` still holds — the player spawns alive
  but weapon-less until a team switch runs `GetIntoGame`.

  Fix: in `Spawn()`, promote such a spawn to a regular in-game spawn (clear
  `m_bJustConnected`/`m_bNotKilled`, set `m_iJoiningState = JOINED`) when the
  player already has a team. The connect-time no-op spawn
  (`m_iJoiningState == PICKINGTEAM`) keeps its guarded behaviour.

  Ship: rebuild `libcs.so` from the ReGameDLL_CS fork with this patch applied
  and publish the game APK release; the patcher picks it up unchanged.

## Known residual issues

- On-device runtime smoke test is still pending (native build + patcher logic verified in CI/ locally).
- 32-bit `.amxx` files are intentionally rejected by the 64-bit core.