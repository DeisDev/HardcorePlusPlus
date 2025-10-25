package insidate.hardcoreplus;

public final class NameUtil {
    private NameUtil() {}

    // Replace characters that are illegal in Windows/macOS/Linux filenames and tidy up
    public static String sanitizeName(String input) {
        if (input == null) return "world_" + System.currentTimeMillis();
        String t = input.replaceAll("[\\\\/:*?\"<>|]", "-");
        t = t.trim();
        while (!t.isEmpty() && (t.endsWith(" ") || t.endsWith("."))) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? ("world_" + System.currentTimeMillis()) : t;
    }

    // Remove repeated trailing timestamp segments like "_HH-mm-ss_yyyy-MM-dd"
    public static String stripTimeSuffixes(String name) {
        if (name == null || name.isBlank()) return name;
        String out = name;
    String regex = "(?:_\\d{2}-\\d{2}-\\d{2}_\\d{4}-\\d{2}-\\d{2})+$";
        out = out.replaceFirst(regex, "");
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
