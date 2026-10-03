#if defined _reapi_version_included
	#endinput
#endif
#define _reapi_version_included

// Version of the ReAPI module that CI builds and the release ships.
//
// android/ci/build-amxx.sh writes exactly these values into the ReAPI checkout
// before compiling (see the "Generate reapi_version.inc" step), so these have
// to stay in sync with it. The include set in this folder comes from the same
// ReAPI checkout (rehlds/ReAPI, reapi/reapi/extra/amxmodx/scripting/include),
// which is what the module is compiled from.
//
// A mismatch here is fatal at plugin init:
//   [ReAPI]: Api major version mismatch; expected X, real Y
//   Plugin ("...") is setting itself as failed.
#define REAPI_VERSION_MAJOR 3
#define REAPI_VERSION_MINOR 1
#define REAPI_VERSION_PATCH 0
#define REAPI_VERSION_STR "3.1.0"
