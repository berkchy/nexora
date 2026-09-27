#!/usr/bin/env python3
"""Per-ABI gamedata offset overrides measured from the built libcs.

The ReGameDLL fork's member layout differs from stock CS16Client/ReGameDLL, so
AMXX's shipped cstrike pdata offsets are wrong and plugins that read/write
cstrike pdata (admin.amxx auth, Zombie Plague, ...) misbehave or crash the
gamedll.  The offsets are ABI-specific (pointer size, member ordering), so one
override file cannot serve both arm64-v8a and armeabi-v7a.

Rather than hand-maintaining a table, this tool reads the member offsets out of
the DWARF debug info that the very same libcs build emits:  DWARF's
DW_AT_data_member_location for a class member is exactly what offsetof() would
return for that build, so the table cannot drift from the shipped binary.

Commands:
  list   <template.txt>                       - print the measured field list
  check  <template.txt> <measurements>        - verify measurements vs template
  emit   <template.txt> <measurements> <out>  - write a per-ABI override file

The template is the committed arm64 override; its class/field/type list is the
source of truth for what gets measured.
"""

import argparse
import os
import re
import sys

# --- template parsing -------------------------------------------------------

CLASS_RE = re.compile(r'^\t\t\t"(\w+)"$')
SECTION_RE = re.compile(r'^\t\t\t\t"(\w+)"$')
FIELD_RE = re.compile(r'^\t\t\t\t\t"(\w+)"$')
VALUE_RE = re.compile(r'^\t\t\t\t\t\t"(\w+)"\s+"(.*)"$')


def parse_template(path):
    """-> ordered list of (class, section, field, {key: value}) plus raw head."""
    fields = []
    head = []
    cur_class = cur_section = cur_field = None
    for line in open(path, encoding="utf-8").read().split("\n"):
        m = CLASS_RE.match(line)
        if m:
            cur_class, cur_section, cur_field = m.group(1), None, None
            continue
        m = SECTION_RE.match(line)
        if m:
            cur_section, cur_field = m.group(1), None
            continue
        m = FIELD_RE.match(line)
        if m and cur_class and cur_section:
            cur_field = (cur_class, cur_section, m.group(1))
            fields.append((cur_field, {}))
            continue
        m = VALUE_RE.match(line)
        if m and cur_field is not None and fields:
            fields[-1][1][m.group(1)] = m.group(2)
    return fields


def parse_measurements(path):
    """'CBasePlayer.m_iTeam=576' lines -> {(class, field): offset}."""
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        cls, _, field = key.strip().rpartition(".")
        out[(cls, field)] = int(value.strip(), 0)
    return out


# --- DWARF parsing ----------------------------------------------------------

DIE_RE = re.compile(r"^(0x[0-9a-f]+):(\s+)(DW_TAG_\w+|NULL)\s*$")
ATTR_RE = re.compile(r"^\s+(DW_AT_\w+)\s*(?:\((.*)\))?\s*$")
QUOTED_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
REF_RE = re.compile(r"^0x([0-9a-f]+)")
TYPE_TAGS = ("DW_TAG_structure_type", "DW_TAG_class_type", "DW_TAG_union_type")
# A gamedll's DWARF is dominated by subprograms and enums; only the type graph
# is needed here, and dropping the rest keeps the parse inside memory limits.
KEEP_TAGS = frozenset(
    ("DW_TAG_compile_unit", "DW_TAG_member", "DW_TAG_inheritance") + TYPE_TAGS
)


class Die(object):
    __slots__ = ("offset", "tag", "attrs", "children", "indent")

    def __init__(self, offset, tag, indent):
        self.offset = offset
        self.tag = tag
        self.indent = indent
        self.attrs = {}
        self.children = []

    def name(self):
        return self.attrs.get("name", (None,))[0]

    def ref(self, attr):
        value = self.attrs.get(attr)
        return int(value[0], 16) if value else None

    def number(self, attr):
        value = self.attrs.get(attr)
        if not value:
            return None
        text = value[0].strip()
        try:
            if text.lower().startswith("0x"):
                return int(text, 16)
            return int(text, 10)
        except ValueError:
            return None


def parse_dwarf(path):
    """llvm-dwarfdump --debug-info text -> {die_offset: Die} (type graph only)."""
    dies = {}
    root = Die(0, "DW_TAG_compile_unit", -1)
    dies[0] = root
    stack = [root]
    for line in open(path, encoding="utf-8", errors="replace"):
        m = DIE_RE.match(line)
        if m:
            if m.group(3) == "NULL":
                continue
            indent = len(m.group(2))
            while len(stack) > 1 and stack[-1].indent >= indent:
                stack.pop()
            tag = m.group(3)
            die = Die(int(m.group(1), 16), tag, indent)
            keep = tag in KEEP_TAGS
            if keep:
                dies[die.offset] = die
                stack[-1].children.append(die)
            stack.append(die)
            continue
        m = ATTR_RE.match(line)
        if m and len(stack) > 1 and stack[-1].tag in KEEP_TAGS:
            attr = m.group(1)[len("DW_AT_"):]
            raw = m.group(2)
            if raw is None:
                continue
            ref = REF_RE.match(raw.strip())
            name = QUOTED_RE.search(raw)
            if attr == "name" and name:
                stack[-1].attrs[attr] = (name.group(1),)
            elif ref:
                stack[-1].attrs[attr] = ("0x" + ref.group(1),)
            else:
                stack[-1].attrs[attr] = (raw.strip(),)
    return dies


def build_layout(dies):
    """{class name: {member: offset}} including inherited members."""
    by_name = {}
    for die in dies.values():
        if die.tag not in TYPE_TAGS or "declaration" in die.attrs:
            continue
        name = die.name()
        if not name:
            continue
        # The same type can be described more than once across compile units;
        # the complete definition is the largest one.
        best = by_name.get(name)
        if best is None or (die.number("byte_size") or 0) > (best.number("byte_size") or 0):
            by_name[name] = die

    def members(die, seen):
        if die.offset in seen:
            return {}
        seen.add(die.offset)
        out = {}
        for child in die.children:
            if child.tag == "DW_TAG_member":
                name = child.name()
                loc = child.number("data_member_location")
                if loc is None:
                    continue
                if name:
                    out[name] = loc
                    continue
                # Anonymous struct/union member: its own members live one level
                # down, shifted by the anonymous member's offset.
                nested = child.ref("type")
                if nested is not None and nested in dies:
                    for member, offset in members(dies[nested], seen).items():
                        out.setdefault(member, offset + loc)
            elif child.tag == "DW_TAG_inheritance":
                base = child.ref("type")
                base_loc = child.number("data_member_location") or 0
                if base is None or base not in dies:
                    continue
                for member, offset in members(dies[base], seen).items():
                    out.setdefault(member, offset + base_loc)
        return out

    return {name: members(die, set()) for name, die in by_name.items()}


def dwarf_diagnostics(path):
    """Why did the parse come up short? Cheap enough to always print on failure."""
    try:
        size = os.path.getsize(path)
    except OSError as exc:
        return "dwarf diagnostics unavailable: %s" % exc
    tags = {}
    classes = []
    total = 0
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            total += 1
            m = DIE_RE.match(line)
            if not m or m.group(3) == "NULL":
                continue
            tags[m.group(3)] = tags.get(m.group(3), 0) + 1
            if m.group(3) in TYPE_TAGS and len(classes) < 8:
                classes.append(line.strip())
    top = sorted(tags.items(), key=lambda kv: -kv[1])[:8]
    return "dwarf: %d bytes, %d lines, most common tags: %s\nfirst class/struct DIEs:\n%s" % (
        size,
        total,
        ", ".join("%s=%d" % (tag, count) for tag, count in top),
        "\n".join("  " + c for c in classes) or "  (none)",
    )


def measure(dwarf_dump, fields):
    dies = parse_dwarf(dwarf_dump)
    layout = build_layout(dies)
    out = []
    missing = []
    for (cls, _section, field), _values in fields:
        if cls not in layout:
            missing.append("%s (class not in DWARF)" % cls)
            continue
        if field not in layout[cls]:
            missing.append("%s.%s (member not in DWARF)" % (cls, field))
            continue
        out.append((cls, field, layout[cls][field]))
    return out, missing


# --- emission ---------------------------------------------------------------

def emit(fields, measurements, out_path, abi, note=""):
    lines = []
    lines.append("/**")
    lines.append(" * %s offset overrides for CS16Client (ReGameDLL_CS fork)." % abi)
    lines.append(" * Measured with android/ci/gamedata-offsets.py from the DWARF debug")
    lines.append(" * info of this build's libcs, so the table cannot drift from the")
    lines.append(" * shipped gamedll. Only the \"linux\" column is overridden; parsed")
    lines.append(" * after AMXX's own files, so fixed offsets win.")
    if note:
        lines.append(" * %s" % note)
    lines.append(" */")
    lines.append("")
    lines.append('"Games"')
    lines.append("{")
    lines.append('\t"#default"')
    lines.append("\t{")
    lines.append('\t\t"Classes"')
    lines.append("\t\t{")

    current_class = None
    for (cls, section, field), values in fields:
        if cls != current_class:
            if current_class is not None:
                lines.append("\t\t\t\t}")
                lines.append("")
            current_class = cls
            lines.append('\t\t\t"%s"' % cls)
            lines.append("\t\t\t{")
            lines.append('\t\t\t\t"%s"' % section)
            lines.append("\t\t\t\t{")
        key = (cls, field)
        if key not in measurements:
            raise SystemExit("emit: no measurement for %s.%s" % key)
        lines.append('\t\t\t\t\t"%s"' % field)
        lines.append("\t\t\t\t\t{")
        lines.append('\t\t\t\t\t\t"type"%s"%s"' % (" " * 6, values.get("type", "integer")))
        lines.append('\t\t\t\t\t\t"linux"%s"%d"' % (" " * 5, measurements[key]))
        lines.append("\t\t\t\t\t}")
        lines.append("")
    if current_class is not None:
        lines.append("\t\t\t\t}")
        lines.append("\t\t\t}")
        lines.append("")
    lines.append("\t\t\t}")
    lines.append("\t}")
    lines.append("}")
    lines.append("")

    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_list = sub.add_parser("list")
    p_list.add_argument("template")

    p_check = sub.add_parser("check")
    p_check.add_argument("template")
    p_check.add_argument("measurements")
    p_check.add_argument("--field")

    p_emit = sub.add_parser("emit")
    p_emit.add_argument("template")
    p_emit.add_argument("measurements")
    p_emit.add_argument("out")
    p_emit.add_argument("--abi", default="unknown")
    p_emit.add_argument("--note", default="")

    p_measure = sub.add_parser("measure")
    p_measure.add_argument("template")
    p_measure.add_argument("dwarf")
    p_measure.add_argument("out")

    args = parser.parse_args()
    fields = parse_template(args.template)

    if args.cmd == "list":
        for (cls, section, field), values in fields:
            print("%s\t%s\t%s\t%s" % (cls, section, field, values.get("type", "")))
        return 0

    if args.cmd == "measure":
        rows, missing = measure(args.dwarf, fields)
        if missing:
            for item in sorted(set(missing)):
                print("MISSING %s" % item, file=sys.stderr)
            print(dwarf_diagnostics(args.dwarf), file=sys.stderr)
            return 1
        with open(args.out, "w", encoding="utf-8") as handle:
            for cls, field, offset in rows:
                handle.write("%s.%s=%d\n" % (cls, field, offset))
        print("measured %d fields -> %s" % (len(rows), args.out))
        return 0

    measurements = parse_measurements(args.measurements)
    if args.cmd == "check":
        bad = []
        for (cls, _section, field), values in fields:
            want = values.get("linux")
            got = measurements.get((cls, field))
            if want is None or got is None:
                continue
            if args.field and args.field != field:
                continue
            if int(want) != got:
                bad.append("%s.%s template=%s dwarf=%d" % (cls, field, want, got))
        if bad:
            for line in bad:
                print("DRIFT %s" % line, file=sys.stderr)
            return 1
        print("template matches DWARF for %d fields" % len(fields))
        return 0

    if args.cmd == "emit":
        emit(fields, measurements, args.out, args.abi, args.note)
        print("wrote %s (%d fields)" % (args.out, len(fields)))
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
