package app.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** CSV export (semicolon separated, UTF-8 with BOM so Excel opens accents correctly). */
public final class Csv {
    private Csv() { }

    private static String q(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    public static void write(Path file, List<String> headers, List<List<String>> rows) throws IOException {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(";", headers.stream().map(Csv::q).toList())).append("\r\n");
        for (List<String> r : rows) sb.append(String.join(";", r.stream().map(Csv::q).toList())).append("\r\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
