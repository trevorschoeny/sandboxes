#!/usr/bin/env python3
"""ci-cd.md stage 3: check every mixin target in this repo against the mapped Minecraft jar.

Run from anywhere: `python3 verify-mixins.py` (lives at the repo root). Reads
`minecraft_version` from the workspace gradle.properties two levels up, finds Loom's
mapped jar in the Gradle cache, and javaps each @Mixin target class. Exit 1 on any FAIL.

Checks, per mixin class:
  - target class exists in the jar
  - every `method = "name"` / `method = "name(desc)ret"` resolves (bare names with
    several overloads are a WARN: Mixin may refuse to apply)
  - every @Shadow / @Accessor / @Invoker member exists
  - every @At(target = "Lcls;member...") member exists on that class
Targets outside net.minecraft (Fabric API, YACL, ModMenu, our own mods) are reported
as SKIP; the green build already proves those compile.

ponytail: regex over source, not a Java parser. Good enough for our annotation style
(one annotation per line, string literals inline). Upgrade to a real parser if a
mixin ever slips past it.
"""
import glob, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
WORKSPACE = os.path.abspath(os.path.join(HERE, "..", ".."))
JAVAP = os.environ.get("JAVAP", "/opt/homebrew/opt/openjdk@25/bin/javap")


def mc_version():
    for line in open(os.path.join(WORKSPACE, "gradle.properties")):
        if line.startswith("minecraft_version="):
            return line.split("=", 1)[1].strip()
    sys.exit("minecraft_version not found in workspace gradle.properties")


def mapped_jar(ver):
    hits = glob.glob(os.path.expanduser(
        f"~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/{ver}/*.jar"))
    if not hits:
        sys.exit(f"no mapped jar for {ver}; run a build first")
    return hits[0]


_cache = {}


def members(jar, cls):
    """-> (methods: {name: [descriptors]}, fields: {name: descriptor}) or None if class missing."""
    if cls in _cache:
        return _cache[cls]
    out = subprocess.run([JAVAP, "-cp", jar, "-p", "-s", cls], capture_output=True, text=True)
    if out.returncode != 0:
        _cache[cls] = None
        return None
    methods, fields = {}, {}
    lines = out.stdout.splitlines()
    decl_line = re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", lines[1] if len(lines) > 1 else ""))  # drop generics (2 levels)
    sup = re.search(r"\bextends\s+([\w.$]+)", decl_line)
    superclass = sup.group(1) if sup and sup.group(1).startswith("net.minecraft") and sup.group(1) != cls else None
    _cache[cls] = (methods, fields)  # placeholder first so a cyclic chain can never recurse forever
    for i, line in enumerate(lines):
        line = line.strip()
        m = re.match(r"descriptor: (.*)", line)
        if not m or i == 0:
            continue
        decl = lines[i - 1].strip().rstrip(";")
        desc = m.group(1)
        if "(" in decl:  # method or constructor
            name = re.sub(r"\(.*", "", decl).split()[-1]
            if name == cls.split(".")[-1].split("$")[-1] or name == cls.replace("$", "."):
                name = "<init>"
            if "." in name:  # constructor printed with package, e.g. net.minecraft.x.Foo(...)
                name = "<init>"
            methods.setdefault(name, []).append(desc)
        else:
            fields[decl.split()[-1]] = desc
    if superclass:  # fold inherited members in; the constant pool may name the subclass as owner
        parent = members(jar, superclass)
        if parent:
            for n, ds in parent[0].items():
                if n != "<init>":
                    methods.setdefault(n, []).extend(d for d in ds if d not in methods.get(n, []))
            for n, d in parent[1].items():
                fields.setdefault(n, d)
    _cache[cls] = (methods, fields)
    return _cache[cls]


def resolve(simple, imports, pkg):
    """Simple or Outer.Inner name -> binary class name, via the file's imports."""
    outer, _, inner = simple.partition(".")
    fqn = imports.get(outer)
    if not fqn:
        return None
    return fqn + ("$" + inner.replace(".", "$") if inner else "")


def accessor_name(annotation, explicit, method_name):
    if explicit:
        return explicit
    if annotation == "Invoker":
        m = re.match(r"(?:call|invoke)([A-Z].*)", method_name)
    else:
        m = re.match(r"(?:get|set|is)([A-Z].*)", method_name)
    if not m:
        return method_name
    n = m.group(1)
    return n[0].lower() + n[1:]


def check_file(path, jar, report):
    src = open(path).read()
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)          # block comments / javadoc
    src = re.sub(r"//[^\n]*", "", src)                        # line comments
    src = re.sub(r'"\s*\+\s*"', "", src)                      # "Lcls;" + "member(...)" -> one literal
    imports = {}
    for m in re.finditer(r"^import\s+(?!static)([\w.]+)\.(\w+);", src, re.M):
        imports[m.group(2)] = m.group(1) + "." + m.group(2)
    pkg = re.search(r"^package\s+([\w.]+);", src, re.M).group(1)
    name = os.path.basename(path)[:-5]

    mix = re.search(r"@Mixin\s*\(([^)]*)\)", src, re.S)
    if not mix:
        return
    body = mix.group(1)
    targets = re.findall(r"([\w.]+)\.class", body)
    string_targets = re.findall(r'targets\s*=.*?"([^"]+)"', body, re.S)

    resolved = []
    for t in targets:
        fqn = resolve(t, imports, pkg)
        if fqn is None:
            report("WARN", name, t, "could not resolve via imports")
        elif not fqn.startswith("net.minecraft"):
            report("SKIP", name, fqn, "not a vanilla class")
        else:
            resolved.append(fqn)
    for t in string_targets:
        if t.startswith("net.minecraft"):
            resolved.append(t.replace("/", "."))
        else:
            report("SKIP", name, t, "string target (@Pseudo)")
    if not resolved:
        return

    infos = {}
    for fqn in resolved:
        info = members(jar, fqn)
        if info is None:
            report("FAIL", name, fqn, "target class not in mapped jar")
        else:
            infos[fqn] = info
    if not infos:
        return

    def find_method(mname, desc=None):
        """-> 'ok' | 'ambiguous' | 'missing' | 'desc-mismatch'."""
        found = []
        for meths, _ in infos.values():
            found += meths.get(mname, [])
        if not found:
            return "missing"
        if desc:
            return "ok" if desc in found else f"desc-mismatch (have {found})"
        return "ambiguous" if len(set(found)) > 1 else "ok"

    def find_field(fname):
        return any(fname in flds for _, flds in infos.values())

    # method = "..." on injectors (single string or array)
    for m in re.finditer(r"method\s*=\s*(\{[^}]*\}|\"[^\"]*\")", src):
        for lit in re.findall(r'"([^"]+)"', m.group(1)):
            mname, _, rest = lit.partition("(")
            desc = "(" + rest if rest else None
            r = find_method(mname, desc)
            if r == "ok":
                report("OK", name, mname, "")
            elif r == "ambiguous":
                report("WARN", name, mname, "bare name matches several overloads; qualify the descriptor")
            else:
                report("FAIL", name, mname, r)

    # @Shadow members: the next declaration after the annotation
    for m in re.finditer(r"@Shadow\b[^;{]*?(\w+)\s*(\(|;|=)", src, re.S):
        member, kind = m.group(1), m.group(2)
        ok = find_method(member) != "missing" if kind == "(" else find_field(member)
        report("OK" if ok else "FAIL", name, "@Shadow " + member, "" if ok else "not on target")

    # @Accessor / @Invoker
    for m in re.finditer(r"@(Accessor|Invoker)\s*(?:\(\s*\"([^\"]*)\"\s*\))?[^;]*?\s(\w+)\s*\(", src, re.S):
        ann, explicit, meth = m.groups()
        member = accessor_name(ann, explicit, meth)
        ok = find_method(member) != "missing" if ann == "Invoker" else find_field(member)
        report("OK" if ok else "FAIL", name, f"@{ann} {member}", "" if ok else "not on target")

    # @At(target = "Lcls;member...") on INVOKE / FIELD / NEW points
    for m in re.finditer(r'target\s*=\s*"L([^;"]+);([^"]*)"', src):
        cls, rest = m.group(1).replace("/", "."), m.group(2)
        if not cls.startswith("net.minecraft"):
            report("SKIP", name, f"@At {cls}", "not a vanilla class")
            continue
        info = members(jar, cls)
        if info is None:
            report("FAIL", name, f"@At {cls}", "class not in mapped jar")
            continue
        meths, flds = info
        if ":" in rest:  # field: name:Ldesc;
            fname, _, fdesc = rest.partition(":")
            ok = flds.get(fname) == fdesc
            report("OK" if ok else "FAIL", name, f"@At {cls.split('.')[-1]}.{fname}",
                   "" if ok else f"field missing or descriptor differs (have {flds.get(fname)})")
        else:
            mname, _, d = rest.partition("(")
            ok = ("(" + d) in meths.get(mname, [])
            report("OK" if ok else "FAIL", name, f"@At {cls.split('.')[-1]}.{mname}",
                   "" if ok else f"method missing or descriptor differs (have {meths.get(mname)})")


def main():
    ver = mc_version()
    jar = mapped_jar(ver)
    counts = {"OK": 0, "WARN": 0, "FAIL": 0, "SKIP": 0}

    def report(level, mixin, what, note):
        counts[level] += 1
        if level != "OK":
            print(f"{level:4} {mixin} -> {what}{': ' + note if note else ''}")

    files = sorted(f for f in glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
                   if "@Mixin" in open(f).read())
    print(f"verify-mixins: {os.path.basename(HERE)} against Minecraft {ver} ({len(files)} mixin classes)")
    for f in files:
        check_file(f, jar, report)
    print(f"SUMMARY: {counts['OK']} OK, {counts['WARN']} WARN, {counts['FAIL']} FAIL, {counts['SKIP']} SKIP")
    sys.exit(1 if counts["FAIL"] else 0)


if __name__ == "__main__":
    main()
