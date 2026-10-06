package zombie.plz;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;

public final class PLZLiveToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static String secret;
    private static long secretModified = -1L;

    private PLZLiveToken() {
    }

    static File iniFile() {
        return new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "Lua" + File.separator + "PLZ" + File.separator + "live-relay.ini");
    }

    private static synchronized String secret() {
        File ini = iniFile();
        long modified = ini.lastModified();
        if (modified != secretModified) {
            secretModified = modified;
            secret = null;
            if (modified != 0L) {
                try {
                    for (String line : Files.readAllLines(ini.toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.startsWith("secret=") && t.length() > "secret=".length()) {
                            secret = t.substring("secret=".length()).trim();
                        }
                    }
                } catch (Exception e) {
                    DebugLog.log("PLZLiveToken: " + ini + " unreadable: " + e);
                }
            }
        }
        return secret;
    }

    public static String newStream() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    public static String token(String stream, long ttlSeconds) {
        String key = secret();
        if (key == null || key.isEmpty() || stream == null || !stream.matches("[0-9a-f]{32}")) {
            return null;
        }
        long exp = System.currentTimeMillis() / 1000L + Math.max(60L, ttlSeconds);
        return sign(key, stream, exp);
    }

    static String sign(String key, String stream, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(("plz-live-v1\n" + stream + "\n" + exp).getBytes(StandardCharsets.UTF_8));
            return "v1." + stream + "." + exp + "." + hex(out);
        } catch (Exception e) {
            DebugLog.log("PLZLiveToken: " + e);
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }
}
