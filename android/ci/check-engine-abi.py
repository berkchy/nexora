#!/usr/bin/env python3
"""Guard the cl_enginefunc_t ABI between the engine and the client dll.

The engine hands its function table to a client dll by value:

    gEngfuncs = *pEnginefuncs;          // cl_dll/cdll_int.cpp

There is no length and no size in that call, so a dll compiled against a struct
with MORE members than the engine that loaded it reads past the end of the
engine's table. The extra members then hold whatever pointers followed in the
engine's memory, and calling one of them is a jump into the void. Every engine
APK older than the dll is affected, which is every player who has not updated
yet.

Grow this check's inputs, not the struct. Optional engine features go through
pfnGetNativeObject(name), which is looked up by name and returns NULL when the
engine does not know it.

The same guard covers engine_motdapi_t, the table that replaced the two MOTD
fields the struct used to carry.

engine_members.txt is a fingerprint of the engine's cl_enginefunc_t: one member
per line, in order. It is what the engine ships today; update it deliberately
when the engine really changes, never to make this check pass.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))

API_PROXY = os.path.join(REPO, "vcs16", "engine", "APIProxy.h")
FINGERPRINT = os.path.join(HERE, "engine_members.txt")

# The engine_motdapi_t / android_motdapi_t table, which replaced the struct
# fields. Same reasoning as above: it crosses the dll boundary by pointer, so
# its size and member order have to match on both sides.
MOTD_ENGINE_DECL = os.path.join(
    HERE, "engine", "platform", "platform.h"
)
MOTD_ENGINE_FINGERPRINT = os.path.join(HERE, "engine_motd_members.txt")


def struct_body(text, struct_name):
    """Return the text between the braces of the struct that typedefs to
    `struct_name`.

    The first mention is usually a forward declaration, so look for the
    `} <struct_name>;` that closes the definition and walk back to its brace.
    """
    close = re.search(r"\}\s*" + re.escape(struct_name) + r"\s*;", text)
    if not close:
        raise SystemExit(f"FAIL: definition of {struct_name} not found")

    depth = 0
    for i in range(close.start(), -1, -1):
        if text[i] == "}":
            depth += 1
        elif text[i] == "{":
            depth -= 1
            if depth == 0:
                return text[i + 1 : close.start()]
    raise SystemExit(f"FAIL: no body for {struct_name}")


def declarations(body):
    """Split a struct body into its top-level declarations.

    Splitting on ';' only counts at paren depth 0, so the parameters inside a
    member's own signature never look like separate members. Without that,
    `int (*pfnAddCommand)( const char *name, void (*function)( void ) );`
    reports a member called `function`.
    """
    chunks = []
    current = []
    depth = 0

    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)

        if ch == ";" and depth == 0:
            chunks.append("".join(current))
            current = []
        else:
            current.append(ch)

    if current:
        chunks.append("".join(current))

    return [c for c in (c.strip() for c in chunks) if c]


def members(body):
    """Member names in declaration order.

    Two spellings show up and both have to be understood:
      engine  `int  (*pfnFoo)( int a );`      - pointer written in place
      client  `pfnEngSrc_pfnFoo_t pfnFoo;`   - member of a pointer typedef
    """
    body = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)
    body = re.sub(r"//[^\n]*", " ", body)

    names = []
    for decl in declarations(body):
        # The member's own `(*name)` comes before any pointer in its arguments.
        match = re.search(r"\(\s*\*\s*([A-Za-z_]\w*)\s*\)", decl)
        if not match:
            # Members of a pointer typedef, e.g. `pfnEngSrc_pfnFoo_t pfnFoo;`.
            # Not every such typedef ends in _t (COM_GetApproxWavePlayLength
            # does not), so the suffix cannot be required.
            match = re.search(r"\bpfnEngSrc_\w+\s+([A-Za-z_]\w*)\s*$", decl)
        if match:
            names.append(match.group(1))

    if not names:
        raise SystemExit("FAIL: no members found")
    return names


def load_fingerprint():
    if not os.path.exists(FINGERPRINT):
        raise SystemExit(f"FAIL: missing {FINGERPRINT}")
    with open(FINGERPRINT, encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def main():
    with open(API_PROXY, encoding="utf-8") as f:
        client_text = f.read()

    try:
        client = members(struct_body(client_text, "cl_enginefunc_t"))
    except SystemExit as exc:
        print(exc)
        return 1

    engine = load_fingerprint()

    # Position is the whole contract: the engine fills its own table by static
    # initializer order and the dll copies it out by value. Same number of slots
    # means every slot lines up. Member NAMES may differ (they get renamed
    # upstream all the time) and that is harmless.
    if len(client) != len(engine):
        print(f"FAIL: client has {len(client)} slots, engine has {len(engine)}")
        if len(client) > len(engine):
            print(
                f"  client-only: {client[len(engine):]}\n"
                "  A dll that copies more slots than the engine has reads past "
                "the end of the engine's table and calls garbage."
            )
        else:
            print(
                f"  missing in client: {engine[len(client):]}\n"
                "  Every slot after the gap holds the wrong function pointer."
            )
        print(
            "  Move the feature behind pfnGetNativeObject() instead of "
            "appending to the struct."
        )
        return 1

    renamed = [
        (i, e, c) for i, (e, c) in enumerate(zip(engine, client)) if e != c
    ]
    if renamed:
        print(f"OK: {len(client)} slots match (names differ, which is fine):")
        for i, e, c in renamed:
            print(f"  slot {i}: engine={e} client={c}")
    else:
        print(f"OK: client and engine cl_enginefunc_t agree ({len(client)} slots)")

    return check_motd()


def check_motd():
    """The MOTDAPI table must have the same layout on both sides."""
    with open(API_PROXY, encoding="utf-8") as f:
        client_text = f.read()

    try:
        client = members(struct_body(client_text, "engine_motdapi_t"))
    except SystemExit as exc:
        print(exc)
        return 1

    if not os.path.exists(MOTD_ENGINE_FINGERPRINT):
        print(f"FAIL: missing {MOTD_ENGINE_FINGERPRINT}")
        return 1

    with open(MOTD_ENGINE_FINGERPRINT, encoding="utf-8") as f:
        engine = [line.strip() for line in f if line.strip()]

    if client != engine:
        print(f"FAIL: MOTDAPI table differs. engine={engine} client={client}")
        return 1

    print(f"OK: MOTDAPI table matches ({len(client)} entries: {client})")
    return 0


if __name__ == "__main__":
    sys.exit(main())