package app.service;

import app.core.Config;
import app.security.LocalVault;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations that could not reach MySQL, kept in an AES-GCM encrypted local file and replayed when the network returns. */
public final class OfflineQueue {
    public record Op(String type, Map<String, String> data) {
        public static Op of(String type, String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
            return new Op(type, m);
        }
        public String get(String k) { return data.getOrDefault(k, ""); }
        public int getInt(String k) { return Integer.parseInt(get(k)); }
    }

    private final List<Op> ops = new ArrayList<>();
    private final Path file = Config.vaultDir().resolve("pending.bin");

    public OfflineQueue() { load(); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            String text = new String(LocalVault.decrypt(Files.readAllBytes(file)), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 2);
                Map<String, String> m = new LinkedHashMap<>();
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    for (String kv : parts[1].split("&")) {
                        String[] x = kv.split("=", 2);
                        m.put(dec(x[0]), x.length > 1 ? dec(x[1]) : "");
                    }
                }
                ops.add(new Op(dec(parts[0]), m));
            }
        } catch (IOException | GeneralSecurityException e) {
            // unreadable file: keep it for inspection, start empty
        }
    }

    private void persist() {
        try {
            if (ops.isEmpty()) { Files.deleteIfExists(file); return; }
            StringBuilder sb = new StringBuilder();
            for (Op o : ops) {
                sb.append(enc(o.type())).append('\t');
                boolean first = true;
                for (Map.Entry<String, String> e : o.data().entrySet()) {
                    if (!first) sb.append('&');
                    sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                    first = false;
                }
                sb.append('\n');
            }
            Files.write(file, LocalVault.encrypt(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | GeneralSecurityException ignored) { }
    }

    public synchronized void add(Op op) { ops.add(op); persist(); }

    public synchronized int size() { return ops.size(); }

    public synchronized List<Op> snapshot() { return new ArrayList<>(ops); }

    public synchronized void removeFirst(int n) {
        for (int i = 0; i < n && !ops.isEmpty(); i++) ops.remove(0);
        persist();
    }
}
