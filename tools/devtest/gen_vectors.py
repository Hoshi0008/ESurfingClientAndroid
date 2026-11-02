#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用参考后端（Python）生成加解密向量，供 Java 实现做逐字节一致性校验。

用法:
    python tools/devtest/gen_vectors.py <path-to-esurfing.py-dir> <out.txt>

输出每行: arg0|arg1|arg2|arg3|arg4
    case|kind|key_hex|iv_hex|plain_hex|expected_hex
Java 侧按同样的算法再算一遍，比 hex 是否完全一致。
"""
import os
import random
import sys


def main():
    ref = sys.argv[1]
    out = sys.argv[2]
    sys.path.insert(0, ref)
    from esurfing import DoubleAesCbc  # noqa: E402

    random.seed(20260101)
    lines = []

    # 1) 纯随机 key/iv/明文，验证两层 CBC + 魔改 key schedule
    for i in range(120):
        key = bytes(random.getrandbits(8) for _ in range(32))
        iv = bytes(random.getrandbits(8) for _ in range(16))
        n = random.choice([0, 1, 5, 16, 17, 31, 32, 33, 48, 64, 100, 255])
        plain = bytearray(random.getrandbits(8) for _ in range(n))
        while plain and plain[-1] == 0:
            plain[-1] = random.getrandbits(8) or 1
        plain = bytes(plain)
        c = DoubleAesCbc(key, iv)
        ct = c.encrypt(plain)
        assert c.decrypt(ct) == plain, "python 自检失败 case=%d" % i
        lines.append("R%d|pair|%s|%s|%s|%s" % (
            i, key.hex(), iv.hex(), plain.hex(), ct))

    # 2) 表里的真实 Algo-ID，只给明文，Java 侧自己从表里取 key/iv
    from esurfing_algos import ALGOS
    for j, (algo, (kind, key, iv)) in enumerate(sorted(ALGOS.items())):
        assert kind == "aes_double_cbc"
        plain = ('<?xml version="1.0" encoding="utf-8"?>\n<request>\n'
                 '    <user-agent>CCTP/android11_64/2104</user-agent>\n'
                 '    <client-id>%s</client-id>\n'
                 '</request>\n' % ("uuid-%02d" % j)).encode()
        ct = DoubleAesCbc(key, iv).encrypt(plain)
        lines.append("A%d|algo|%s||%s|%s" % (j, algo, plain.hex(), ct))

    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("wrote %d vectors -> %s" % (len(lines), out))


if __name__ == "__main__":
    main()
