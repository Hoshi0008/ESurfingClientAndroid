package com.esurfing.client.core;

/**
 * 双层 AES-128-CBC（官方客户端 PortalServer 的报文加密）。
 *
 * <p>规则（与参考后端一致）：</p>
 * <ul>
 *     <li>32 字节 key 拆成两层 16 字节，两层各用一套轮密钥；</li>
 *     <li>每层都是 <b>IV 前缀模式</b>：输出 = IV || CBC(零填充明文)，不做 PKCS7；</li>
 *     <li>先加密第一层再加密第二层，解密顺序相反，末尾去掉 {@code \0}；</li>
 *     <li>密文以大写 hex 作为 HTTP body。</li>
 * </ul>
 */
final class DoubleAesCbc {

    private final byte[] iv;
    private final int[] sk1;
    private final int[] sk2;

    DoubleAesCbc(byte[] key, byte[] iv) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("double AES-128-CBC 需要 32 字节 key");
        }
        if (iv == null || iv.length != 16) {
            throw new IllegalArgumentException("double AES-128-CBC 需要 16 字节 iv");
        }
        this.iv = iv.clone();
        byte[] k1 = new byte[16];
        byte[] k2 = new byte[16];
        System.arraycopy(key, 0, k1, 0, 16);
        System.arraycopy(key, 16, k2, 0, 16);
        this.sk1 = Aes.expandKey(k1);
        this.sk2 = Aes.expandKey(k2);
    }

    /** 按 Algo-ID 取加密器；未收录/未实现返回 null。 */
    static DoubleAesCbc of(String algoId) {
        if (algoId == null) {
            return null;
        }
        String[] row = Algos.ALGOS.get(algoId.toUpperCase(java.util.Locale.US));
        if (row == null) {
            return null;
        }
        return new DoubleAesCbc(hexToBytes(row[1]), hexToBytes(row[2]));
    }

    /** 明文 -&gt; 大写 hex 密文。 */
    String encrypt(byte[] data) {
        byte[] inner = encryptLayer(sk1, data);
        byte[] outer = encryptLayer(sk2, inner);
        return bytesToHex(outer);
    }

    String encrypt(String text) {
        return encrypt(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 大写/小写/带空白的 hex 密文 -&gt; 明文（去掉尾部 0 填充）。 */
    byte[] decrypt(String hexText) {
        if (hexText == null) {
            throw new IllegalArgumentException("密文为空");
        }
        byte[] raw = hexToBytes(hexText);
        byte[] outer = decryptLayer(sk2, raw);
        byte[] plain = decryptLayer(sk1, outer);
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) {
            end--;
        }
        if (end == plain.length) {
            return plain;
        }
        byte[] trimmed = new byte[end];
        System.arraycopy(plain, 0, trimmed, 0, end);
        return trimmed;
    }

    private byte[] encryptLayer(int[] sk, byte[] data) {
        int total = (data.length + 15) & ~15;
        if (total == 0) {
            total = 16;
        }
        byte[] body = new byte[total];
        System.arraycopy(data, 0, body, 0, data.length);
        byte[] out = new byte[total + 16];
        System.arraycopy(iv, 0, out, 0, 16);
        byte[] prev = iv.clone();
        byte[] mixed = new byte[16];
        byte[] cipher = new byte[16];
        for (int off = 0; off < total; off += 16) {
            for (int i = 0; i < 16; i++) {
                mixed[i] = (byte) (body[off + i] ^ prev[i]);
            }
            Aes.encryptBlock(sk, mixed, 0, cipher, 0);
            System.arraycopy(cipher, 0, out, 16 + off, 16);
            System.arraycopy(cipher, 0, prev, 0, 16);
        }
        return out;
    }

    private byte[] decryptLayer(int[] sk, byte[] data) {
        if (data.length % 16 != 0 || data.length < 32) {
            throw new IllegalArgumentException("密文长度非法: " + data.length);
        }
        int total = data.length - 16;
        byte[] out = new byte[total];
        byte[] prev = new byte[16];
        System.arraycopy(data, 0, prev, 0, 16);
        byte[] cipher = new byte[16];
        byte[] plain = new byte[16];
        for (int off = 0; off < total; off += 16) {
            System.arraycopy(data, 16 + off, cipher, 0, 16);
            Aes.decryptBlock(sk, cipher, 0, plain, 0);
            for (int i = 0; i < 16; i++) {
                out[off + i] = (byte) (plain[i] ^ prev[i]);
            }
            System.arraycopy(cipher, 0, prev, 0, 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ hex

    static byte[] hexToBytes(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                n++;
            }
        }
        if ((n & 1) != 0) {
            throw new IllegalArgumentException("hex 长度必须是偶数");
        }
        byte[] out = new byte[n / 2];
        int idx = 0;
        int hi = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int v = Character.digit(ch, 16);
            if (v < 0) {
                if (Character.isWhitespace(ch)) {
                    continue;
                }
                throw new IllegalArgumentException("非法 hex 字符: " + ch);
            }
            if (hi < 0) {
                hi = v;
            } else {
                out[idx++] = (byte) ((hi << 4) | v);
                hi = -1;
            }
        }
        return out;
    }

    static String bytesToHex(byte[] data) {
        char[] table = HEX;
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0F];
        }
        return new String(out);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
}
