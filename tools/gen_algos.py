#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从参考后端 esurfing_algos.py 生成 Java 版 Algos.java（保证与 Python 表逐字节一致）。

用法:
    python tools/gen_algos.py <path-to-esurfing_algos.py> <out-Algos.java>
"""
import re
import sys


def main():
    src = sys.argv[1]
    out = sys.argv[2]
    text = open(src, encoding="utf-8").read()

    entries = []
    for m in re.finditer(
        r'"([0-9A-Fa-f-]{36})"\s*:\s*\(\s*"([a-z_]+)",\s*bytes\(\[(.*?)\]\),\s*bytes\(\[(.*?)\]\),\s*\)',
        text, re.S):
        algo, kind, key, iv = m.group(1), m.group(2), m.group(3), m.group(4)
        kb = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", key)]
        ib = [int(x, 16) for x in re.findall(r"0x([0-9A-Fa-f]{2})", iv)]
        assert len(kb) == 32 and len(ib) == 16, (algo, len(kb), len(ib))
        assert kind == "aes_double_cbc"
        entries.append((algo.upper(), bytes(kb), bytes(ib)))

    bad = set()
    m = re.search(r"BAD_ALGOS\s*=\s*set\(\[(.*?)\]\)", text, re.S)
    if m:
        bad = {s.upper() for s in re.findall(r"'([0-9A-Fa-f-]{36})'", m.group(1))}

    entries.sort(key=lambda e: e[0])
    lines = []
    lines.append("package com.esurfing.client.core;")
    lines.append("")
    lines.append("import java.util.HashMap;")
    lines.append("import java.util.HashSet;")
    lines.append("import java.util.Map;")
    lines.append("import java.util.Set;")
    lines.append("")
    lines.append("/**")
    lines.append(" * Algo-ID -&gt; (key, iv) 表与失效算法黑名单。")
    lines.append(" * <p>由 tools/gen_algos.py 从参考后端 esurfing_algos.py 自动生成，请勿手改。</p>")
    lines.append(" */")
    lines.append("final class Algos {")
    lines.append("")
    lines.append("    /** 每个条目: {Algo-ID, key(32 字节 hex), iv(16 字节 hex)} */")
    lines.append("    private static final String[][] TABLE = {")
    for algo, key, iv in entries:
        lines.append('        {"%s",\n         "%s",\n         "%s"},' % (
            algo, key.hex().upper(), iv.hex().upper()))
    lines.append("    };")
    lines.append("")
    lines.append("    /** 服务端会随机下发的失效算法，遇到直接丢弃重取。 */")
    lines.append("    private static final String[] BAD = {")
    bad_list = sorted(bad)
    for i in range(0, len(bad_list), 4):
        chunk = ", ".join('"%s"' % u for u in bad_list[i:i + 4])
        lines.append("        " + chunk + ",")
    lines.append("    };")
    lines.append("")
    lines.append("    static final Map<String, String[]> ALGOS = new HashMap<String, String[]>(64);")
    lines.append("    static final Set<String> BAD_ALGOS = new HashSet<String>(128);")
    lines.append("")
    lines.append("    static {")
    lines.append("        for (String[] row : TABLE) {")
    lines.append("            ALGOS.put(row[0], row);")
    lines.append("        }")
    lines.append("        for (String s : BAD) {")
    lines.append("            BAD_ALGOS.add(s);")
    lines.append("        }")
    lines.append("    }")
    lines.append("")
    lines.append("    private Algos() {")
    lines.append("    }")
    lines.append("}")
    lines.append("")
    open(out, "w", encoding="utf-8", newline="\n").write("\n".join(lines))
    print("algorithms: %d, bad: %d -> %s" % (len(entries), len(bad), out))


if __name__ == "__main__":
    main()
