package com.esurfing.client.core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;

/**
 * 加密一致性校验：读取由 Python 参考实现生成的向量，逐字节比对。
 *
 * <p>仅开发期使用，不打进 APK（放在 tools/devtest 下）。</p>
 */
final class CryptoCheck {

    private CryptoCheck() {
    }

    /** @return 失败条数 */
    static int run(String vectorFile) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(vectorFile));
        int total = 0;
        int failed = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 6) {
                System.out.println("向量格式错误: " + line);
                failed++;
                continue;
            }
            total++;
            String name = parts[0];
            String kind = parts[1];
            String expected = parts[5].trim();
            String plainHex = parts[4].trim();
            DoubleAesCbc cipher;
            if ("algo".equals(kind)) {
                cipher = DoubleAesCbc.of(parts[2].trim());
                if (cipher == null) {
                    System.out.println("[FAIL] " + name + " 算法表里没有 " + parts[2]);
                    failed++;
                    continue;
                }
            } else {
                byte[] key = DoubleAesCbc.hexToBytes(parts[2].trim());
                byte[] iv = DoubleAesCbc.hexToBytes(parts[3].trim());
                cipher = new DoubleAesCbc(key, iv);
            }
            byte[] plain = DoubleAesCbc.hexToBytes(plainHex);
            String actual = cipher.encrypt(plain);
            if (!actual.equalsIgnoreCase(expected)) {
                System.out.println("[FAIL] " + name + " 加密不一致");
                System.out.println("   expect=" + expected);
                System.out.println("   actual=" + actual);
                failed++;
                continue;
            }
            byte[] back = cipher.decrypt(actual);
            if (!Arrays.equals(back, plain)) {
                System.out.println("[FAIL] " + name + " 解密回环不一致");
                failed++;
            }
        }
        reader.close();
        System.out.println("加密向量: " + (total - failed) + "/" + total + " 通过");
        return failed;
    }
}
