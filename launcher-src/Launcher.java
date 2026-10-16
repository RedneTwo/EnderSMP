import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * Launcher del modpack.
 * Lee manifest.json del repositorio de GitHub, descarga lo que falta o ha cambiado
 * y deja la carpeta del modpack igual que la del repositorio.
 *
 * Para cambiar el nombre o el repositorio, edita solo las constantes de abajo.
 */
public class Launcher {

    // ====== CONFIGURACIÓN ======
    static final String PACK_NAME = "EnderSMP";
    static final String REPO = "RedneTwo/EnderSMP";      // usuario/repositorio de GitHub
    static final String BRANCH = "main";
    static final String LAUNCHER_VERSION = "1.0.3";     // súbelo al publicar un launcher nuevo
    static final String RAM = "-Xmx6G";                  // memoria para el perfil del launcher oficial
    static final String PACK_DIR = "Modpack";           // carpeta del repositorio con mods/, config/… (ojo a las mayúsculas)
    static final boolean AUTO_UPDATE = true;            // actualizar el modpack solo al abrir el launcher
    static final String MC_VERSION = "1.20.1";           // por defecto; manda lo que diga manifest.json
    static final String FORGE_VERSION = "47.4.12";       // por defecto; manda lo que diga manifest.json
    // ===========================

    /** Solo para pruebas: -Dlauncher.raw=http://localhost:8000 */
    static final String RAW_BASE = System.getProperty("launcher.raw", "https://raw.githubusercontent.com/" + REPO);
    static final String PROFILE_ID = PACK_NAME.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "-launcher";
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();

    // ---------- Rutas ----------

    static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
    static boolean isMac() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"); }

    static Path minecraftDir() {
        String home = System.getProperty("user.home");
        if (isWindows()) {
            String appdata = System.getenv("APPDATA");
            return Paths.get(appdata != null ? appdata : home, ".minecraft");
        }
        if (isMac()) return Paths.get(home, "Library", "Application Support", "minecraft");
        return Paths.get(home, ".minecraft");
    }

    static Path defaultGameDir() {
        String folder = "." + PACK_NAME.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + "-modpack";
        return minecraftDir().getParent().resolve(folder);
    }

    // ---------- Modelo ----------

    record PackFile(String path, String sha1, long size) {}
    record Manifest(String version, String minecraft, String forge, String commit, List<PackFile> files, List<String> mirrorDirs) {}
    /**
     * deletions: archivos ajenos en carpetas espejo (mods/), se pregunta antes de quitarlos.
     * stale: archivos que instaló este launcher y ya no están en GitHub, se quitan solos.
     */
    record Plan(Manifest manifest, List<PackFile> downloads, List<Path> deletions, List<String> stale, long bytes) {
        boolean upToDate() { return downloads.isEmpty() && deletions.isEmpty() && stale.isEmpty(); }
    }

    interface Progress { void update(String text, double fraction); }

    // ---------- Red ----------

    static String getText(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", PACK_NAME + "-Launcher/" + LAUNCHER_VERSION).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " en " + url);
        return r.body();
    }

    /** Commit más reciente de la rama: así los archivos nunca llegan desde una caché vieja. */
    static String latestCommit() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/commits/" + BRANCH))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github.sha")
                    .header("User-Agent", PACK_NAME + "-Launcher").build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String sha = r.body().trim();
            if (r.statusCode() == 200 && sha.matches("[0-9a-f]{40}")) return sha;
        } catch (Exception ignored) { }
        return BRANCH; // si la API falla (límite de peticiones), usamos la rama
    }

    static String rawUrl(String commit, String path) {
        StringBuilder sb = new StringBuilder(RAW_BASE).append('/').append(commit);
        for (String seg : (PACK_DIR + "/" + path).split("/"))
            sb.append('/').append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Manifest fetchManifest() throws Exception {
        String commit = latestCommit();
        String url = RAW_BASE + "/" + commit + "/manifest.json";
        Map<String, Object> m = (Map<String, Object>) Json.parse(getText(url));
        List<PackFile> files = new ArrayList<>();
        for (Object o : (List<Object>) m.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            files.add(new PackFile((String) f.get("path"), (String) f.get("sha1"), Long.parseLong(f.get("size").toString())));
        }
        List<String> mirror = new ArrayList<>();
        Object md = m.get("mirrorDirs");
        if (md instanceof List<?> l) for (Object o : l) mirror.add(o.toString());
        else mirror.add("mods");
        return new Manifest(String.valueOf(m.get("version")), String.valueOf(m.getOrDefault("minecraft", MC_VERSION)),
                String.valueOf(m.getOrDefault("forge", FORGE_VERSION)), commit, files, mirror);
    }

    // ---------- Sincronización ----------

    static String sha1(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static Path safeResolve(Path root, String rel) throws IOException {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root.normalize())) throw new IOException("Ruta no válida en el manifest: " + rel);
        return p;
    }

    static Properties loadState(Path gameDir) {
        Properties p = new Properties();
        Path f = gameDir.resolve(".launcher").resolve("state.properties");
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    static void saveState(Path gameDir, Properties p) throws IOException {
        Path f = gameDir.resolve(".launcher").resolve("state.properties");
        Files.createDirectories(f.getParent());
        try (Writer w = Files.newBufferedWriter(f)) { p.store(w, PACK_NAME + " launcher"); }
    }

    static Plan plan(Path gameDir, Manifest m) throws IOException {
        Properties state = loadState(gameDir);
        List<PackFile> downloads = new ArrayList<>();
        long bytes = 0;
        Set<Path> wanted = new HashSet<>();
        for (PackFile f : m.files()) {
            Path local = safeResolve(gameDir, f.path());
            wanted.add(local);
            boolean mirrored = m.mirrorDirs().stream().anyMatch(d -> f.path().startsWith(d + "/"));
            boolean need;
            if (!Files.exists(local)) need = true;
            else {
                String localSha = sha1(local);
                if (localSha.equals(f.sha1())) need = false;
                else if (mirrored) need = true;
                // config y demás: si ya instalamos esta misma versión y el jugador la cambió, la respetamos
                else need = !f.sha1().equals(state.getProperty(f.path()));
            }
            if (need) { downloads.add(f); bytes += f.size(); }
        }
        List<Path> deletions = new ArrayList<>();
        for (String d : m.mirrorDirs()) {
            Path dir = safeResolve(gameDir, d);
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) if (Files.isRegularFile(p) && !wanted.contains(p.normalize())) deletions.add(p);
            }
        }
        List<String> stale = new ArrayList<>();
        Set<String> inManifest = new HashSet<>();
        for (PackFile f : m.files()) inManifest.add(f.path());
        for (String rel : state.stringPropertyNames()) {
            if (rel.startsWith("_") || inManifest.contains(rel)) continue;
            Path local;
            try { local = safeResolve(gameDir, rel); } catch (IOException e) { continue; }
            if (deletions.contains(local)) continue;
            // Solo si el jugador no lo ha cambiado (sigue siendo el que pusimos)
            try { if (Files.isRegularFile(local) && sha1(local).equals(state.getProperty(rel))) stale.add(rel); }
            catch (IOException ignored) { }
        }
        return new Plan(m, downloads, deletions, stale, bytes);
    }

    /** Si donde tiene que haber una carpeta hay un archivo que puso este launcher, se quita. */
    static void clearFileInTheWay(Path gameDir, Path dir, Properties state) throws IOException {
        for (Path d = dir; d != null && d.startsWith(gameDir) && !d.equals(gameDir); d = d.getParent()) {
            if (Files.isRegularFile(d)) {
                String rel = gameDir.relativize(d).toString().replace('\\', '/');
                if (state.getProperty(rel) == null) throw new IOException("Hay un archivo \"" + d.getFileName() + "\" donde debería haber una carpeta:\n" + d);
                Files.delete(d);
                state.remove(rel);
            }
        }
    }

    static void apply(Path gameDir, Plan plan, Progress progress) throws Exception {
        Files.createDirectories(gameDir);
        Properties state = loadState(gameDir);
        for (String rel : plan.stale()) {
            progress.update("Quitando " + rel, 0);
            Files.deleteIfExists(safeResolve(gameDir, rel));
            state.remove(rel);
        }
        long done = 0, total = Math.max(1, plan.bytes());
        int i = 0;
        for (PackFile f : plan.downloads()) {
            i++;
            String name = f.path().substring(f.path().lastIndexOf('/') + 1);
            progress.update("Descargando " + name + " (" + i + "/" + plan.downloads().size() + ")", (double) done / total);
            Path target = safeResolve(gameDir, f.path());
            clearFileInTheWay(gameDir, target.getParent(), state);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            HttpRequest req = HttpRequest.newBuilder(URI.create(rawUrl(plan.manifest().commit(), f.path())))
                    .header("User-Agent", PACK_NAME + "-Launcher/" + LAUNCHER_VERSION).build();
            HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("No se pudo descargar " + f.path() + " (HTTP " + r.statusCode() + ")");
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                long fileDone = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    fileDone += n;
                    progress.update("Descargando " + name + " (" + i + "/" + plan.downloads().size() + ")",
                            Math.min(1.0, (double) (done + fileDone) / total));
                }
            }
            if (!sha1(tmp).equals(f.sha1())) {
                Files.deleteIfExists(tmp);
                throw new IOException("El archivo " + f.path() + " llegó dañado. Vuelve a intentarlo.");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            state.setProperty(f.path(), f.sha1());
            done += f.size();
        }
        for (Path p : plan.deletions()) {
            progress.update("Quitando " + p.getFileName(), 1.0);
            Files.deleteIfExists(p);
        }
        for (PackFile f : plan.manifest().files()) state.putIfAbsent(f.path(), f.sha1());
        state.setProperty("_version", plan.manifest().version());
        saveState(gameDir, state);
        progress.update("Modpack actualizado", 1.0);
    }

    // ---------- Perfil del launcher oficial ----------

    /** Busca la versión de Forge instalada (p. ej. 1.20.1-forge-47.4.12) para la versión de Minecraft del pack. */
    static String findForge(String mcVersion) {
        String prefix = mcVersion + "-forge-";
        Path versions = minecraftDir().resolve("versions");
        if (!Files.isDirectory(versions)) return null;
        String best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(versions)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && Files.exists(p.resolve(n + ".json")))
                    if (best == null || compareVersions(n, best) > 0) best = n;
            }
        } catch (IOException ignored) { }
        return best;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int v = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (u != v) return Integer.compare(u, v);
        }
        return 0;
    }

    /** Crea o actualiza el perfil del modpack en el launcher oficial. Devuelve null si va bien, o el motivo. */
    @SuppressWarnings("unchecked")
    static String ensureProfile(Path gameDir, String mcVersion) {
        String neo = findForge(mcVersion);
        if (neo == null) return "FORGE";
        Path file = minecraftDir().resolve("launcher_profiles.json");
        if (!Files.exists(file)) return "No se encontró el launcher oficial de Minecraft (" + file + ").";
        try {
            Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(file));
            Map<String, Object> profiles = (Map<String, Object>) root.computeIfAbsent("profiles", k -> new LinkedHashMap<>());
            Map<String, Object> p = (Map<String, Object>) profiles.get(PROFILE_ID);
            String now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
            if (p == null) {
                p = new LinkedHashMap<>();
                p.put("created", now);
                p.put("icon", "Furnace");
                p.put("javaArgs", RAM + " -XX:+UseG1GC");
                profiles.put(PROFILE_ID, p);
            }
            p.put("name", PACK_NAME);
            p.put("type", "custom");
            p.put("lastVersionId", neo);
            p.put("gameDir", gameDir.toAbsolutePath().toString());
            p.put("lastUsed", now);
            Path backup = file.resolveSibling("launcher_profiles.json.bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, Json.write(root));
            return null;
        } catch (Exception e) {
            return "No se pudo crear el perfil: " + e.getMessage();
        }
    }

    static final String STORE_PACKAGE = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

    static String legacyLauncherExe() {
        for (String env : new String[]{"ProgramFiles(x86)", "ProgramFiles"}) {
            String pf = System.getenv(env);
            if (pf != null && Files.exists(Paths.get(pf, "Minecraft Launcher", "MinecraftLauncher.exe")))
                return Paths.get(pf, "Minecraft Launcher", "MinecraftLauncher.exe").toString();
        }
        return null;
    }

    /** ¿Está instalado el launcher oficial (Microsoft Store/Xbox o el instalador clásico)? */
    static boolean officialInstalled() {
        if (Files.exists(minecraftDir().resolve("launcher_profiles.json"))) return true;
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            return (local != null && Files.isDirectory(Paths.get(local, "Packages", STORE_PACKAGE))) || legacyLauncherExe() != null;
        }
        if (isMac()) return Files.exists(Paths.get("/Applications/Minecraft.app"));
        return false;
    }

    static void openOfficialLauncher() throws IOException {
        if (isWindows()) {
            String exe = legacyLauncherExe();
            String local = System.getenv("LOCALAPPDATA");
            boolean store = local != null && Files.isDirectory(Paths.get(local, "Packages", STORE_PACKAGE));
            if (exe != null && !store) new ProcessBuilder(exe).start();
            else new ProcessBuilder("explorer.exe", "shell:AppsFolder\\" + STORE_PACKAGE + "!Minecraft").start();
        } else if (isMac()) {
            new ProcessBuilder("open", "-a", "Minecraft").start();
        } else {
            new ProcessBuilder("minecraft-launcher").start();
        }
    }

    // ---------- Noticias y versión del launcher ----------

    @SuppressWarnings("unchecked")
    static String newsHtml(String commit) {
        try {
            Object parsed = Json.parse(getText(RAW_BASE + "/" + commit + "/news.json"));
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<Object>) parsed) {
                Map<String, Object> n = (Map<String, Object>) o;
                sb.append("<p style='margin:0 0 12px 0'><span style='font-family:\"" + gothicMedium.getFamily() + "\";font-size:15px;color:" + hex(ACCENT) + "'>")
                  .append(esc(String.valueOf(n.getOrDefault("titulo", "")))).append("</span>")
                  .append(" &nbsp;<span style='color:" + hex(MUTED) + "'>").append(esc(String.valueOf(n.getOrDefault("fecha", "")))).append("</span><br>")
                  .append(esc(String.valueOf(n.getOrDefault("texto", ""))).replace("\n", "<br>")).append("</p>");
            }
            return htmlWrap(sb.toString());
        } catch (Exception e) {
            return htmlWrap("<p style='color:" + hex(MUTED) + "'>No se pudieron cargar las novedades.</p>");
        }
    }

    static String hex(Color c) { return String.format("#%06X", c.getRGB() & 0xFFFFFF); }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    @SuppressWarnings("unchecked")
    static String newerLauncherUrl() {
        try {
            Map<String, Object> r = (Map<String, Object>) Json.parse(getText("https://api.github.com/repos/" + REPO + "/releases/latest"));
            String tag = String.valueOf(r.get("tag_name")).replaceFirst("^[vV]", "");
            if (compareVersions(tag, LAUNCHER_VERSION) > 0) return String.valueOf(r.get("html_url"));
        } catch (Exception ignored) { }
        return null;
    }

    // ---------- Destinos: launcher oficial, Modrinth, Prism ----------

    enum Kind { OFFICIAL, MODRINTH, PRISM, CUSTOM }

    record Target(Kind kind, String name, Path dir, String instanceId) {
        String label() {
            return switch (kind) {
                case OFFICIAL -> "Launcher oficial";
                case MODRINTH -> "Modrinth · " + name;
                case PRISM -> "Prism · " + name;
                case CUSTOM -> "Carpeta · " + dir.getFileName();
            };
        }
    }

    static Path appData() {
        String home = System.getProperty("user.home");
        if (isWindows()) { String a = System.getenv("APPDATA"); return Paths.get(a != null ? a : home); }
        if (isMac()) return Paths.get(home, "Library", "Application Support");
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null ? Paths.get(xdg) : Paths.get(home, ".local", "share");
    }

    static List<Path> subdirs(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path p : ds) if (!p.getFileName().toString().startsWith(".") && !p.getFileName().toString().startsWith("_")) out.add(p);
        } catch (IOException ignored) { }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Busca las instancias de Modrinth App y Prism Launcher que tenga el jugador. */
    static List<Target> detectTargets() {
        List<Target> t = new ArrayList<>();
        t.add(new Target(Kind.OFFICIAL, "Launcher oficial", defaultGameDir(), null));
        Set<Path> seen = new HashSet<>();
        Set<String> registered = modrinthRegisteredFolders();   // null = no se pudo saber
        for (Path root : modrinthProfileRoots())
            for (Path p : subdirs(root)) {
                String folder = p.getFileName().toString();
                // Un perfil borrado en Modrinth puede dejar la carpeta: si no está en app.db, no es un perfil
                if (registered != null && !registered.contains(folder.toLowerCase(Locale.ROOT))) continue;
                if (seen.add(p.toAbsolutePath().normalize()))
                    t.add(new Target(Kind.MODRINTH, folder, p, null));
            }
        List<Path> prismRoots = new ArrayList<>(List.of(appData().resolve("PrismLauncher").resolve("instances")));
        if (!isWindows() && !isMac()) prismRoots.add(Paths.get(System.getProperty("user.home"), ".var", "app",
                "org.prismlauncher.PrismLauncher", "data", "PrismLauncher", "instances"));
        for (Path root : prismRoots)
            for (Path p : subdirs(root)) {
                Path mc = Files.isDirectory(p.resolve("minecraft")) ? p.resolve("minecraft") : p.resolve(".minecraft");
                if (Files.exists(p.resolve("instance.cfg")))
                    t.add(new Target(Kind.PRISM, p.getFileName().toString(), mc, p.getFileName().toString()));
            }
        return t;
    }

    static Target targetFromSettings(Properties s, List<Target> detected) {
        String kind = s.getProperty("targetKind"), dir = s.getProperty("targetDir");
        if (kind != null && dir != null) {
            for (Target t : detected) if (t.kind().name().equals(kind) && t.dir().toString().equals(dir)) return t;
            if (kind.equals("CUSTOM")) return new Target(Kind.CUSTOM, dir, Paths.get(dir), null);
        }
        // Primera vez: si ya hay una instancia con el nombre del pack, usamos esa
        for (Target t : detected)
            if (t.kind() != Kind.OFFICIAL && t.name().toLowerCase(Locale.ROOT).contains(PACK_NAME.toLowerCase(Locale.ROOT))) return t;
        return detected.get(0);
    }

    static boolean tryStart(String... cmd) {
        try {
            if (cmd[0].contains(File.separator) && !Files.exists(Paths.get(cmd[0]))) return false;
            new ProcessBuilder(cmd).start();
            return true;
        } catch (IOException e) { return false; }
    }

    /** Posibles rutas de "Modrinth App.exe". */
    static List<String> modrinthExes() {
        List<String> l = new ArrayList<>();
        String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("ProgramFiles");
        if (local != null) { l.add(local + "\\Modrinth App\\Modrinth App.exe"); l.add(local + "\\Programs\\Modrinth App\\Modrinth App.exe"); }
        if (pf != null) l.add(pf + "\\Modrinth App\\Modrinth App.exe");
        for (Path cfg : modrinthConfigDirs()) {
            Path custom = modrinthCustomDir(cfg);
            if (custom != null) l.add(custom.resolve("Modrinth App.exe").toString());
        }
        Path chosen = chosenModrinthProfiles();
        if (chosen != null && chosen.getParent() != null) l.add(chosen.getParent().resolve("Modrinth App.exe").toString());
        return l;
    }

    static boolean openModrinth() {
        if (isWindows()) {
            for (String exe : modrinthExes()) if (tryStart(exe)) return true;
            String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("ProgramFiles");
            return (local != null && tryStart(local + "\\Modrinth App\\Modrinth App.exe"))
                    || (local != null && tryStart(local + "\\Programs\\Modrinth App\\Modrinth App.exe"))
                    || (pf != null && tryStart(pf + "\\Modrinth App\\Modrinth App.exe"));
        }
        if (isMac()) return tryStart("open", "-a", "Modrinth App");
        return tryStart("ModrinthApp") || tryStart("modrinth-app");
    }

    /** Carpetas de configuración de Modrinth App (donde está app.db). */
    static List<Path> modrinthConfigDirs() {
        List<Path> out = new ArrayList<>();
        for (String app : List.of("ModrinthApp", "com.modrinth.theseus")) {
            Path d = appData().resolve(app);
            if (Files.isDirectory(d)) out.add(d);
        }
        return out;
    }

    interface DbQuery<T> { T run(java.sql.Connection c) throws Exception; }

    /** Abre una copia de app.db (y su WAL) para no interferir con Modrinth mientras está abierto. */
    static <T> T withModrinthDb(Path configDir, DbQuery<T> q) {
        if (!Files.exists(configDir.resolve("app.db"))) return null;
        Path tmp = null;
        try {
            Class.forName("org.sqlite.JDBC");
            tmp = Files.createTempDirectory("mrdb");
            Files.copy(configDir.resolve("app.db"), tmp.resolve("app.db"));
            if (Files.exists(configDir.resolve("app.db-wal"))) Files.copy(configDir.resolve("app.db-wal"), tmp.resolve("app.db-wal"));
            try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("app.db"))) {
                return q.run(c);
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (tmp != null) try (var w = Files.walk(tmp)) {
                w.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
    }

    static boolean hasTable(java.sql.Connection c, String table) throws java.sql.SQLException {
        try (var rs = c.getMetaData().getTables(null, null, table, null)) { return rs.next(); }
    }

    static final Map<Path, Optional<Path>> CUSTOM_DIR_CACHE = new HashMap<>();

    /** Carpeta de datos elegida en Ajustes de Modrinth ("App directory"), o null si es la de por defecto. */
    static synchronized Path modrinthCustomDir(Path configDir) {
        return CUSTOM_DIR_CACHE.computeIfAbsent(configDir, d -> Optional.ofNullable(readModrinthCustomDir(d))).orElse(null);
    }

    static Path readModrinthCustomDir(Path configDir) {
        String dir = withModrinthDb(configDir, c -> {
            if (!hasTable(c, "settings")) return null;
            try (var st = c.prepareStatement("SELECT custom_dir FROM settings LIMIT 1"); var rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
        return dir == null || dir.isBlank() ? null : Paths.get(dir);
    }

    /** Todas las carpetas "profiles" de Modrinth: la de por defecto y la personalizada (p. ej. E:\\Modrinth\\profiles). */
    static List<Path> modrinthProfileRoots() {
        List<Path> roots = new ArrayList<>();
        Path chosen = chosenModrinthProfiles();
        if (chosen != null) roots.add(chosen);
        for (Path cfg : modrinthConfigDirs()) {
            Path custom = modrinthCustomDir(cfg);
            if (custom != null) roots.add(custom.resolve("profiles"));
            roots.add(cfg.resolve("profiles"));
        }
        return roots;
    }

    static Set<String> registeredCache;
    static long registeredStamp, registeredReadAt;

    /**
     * Nombres de carpeta (en minúsculas) de los perfiles que Modrinth tiene registrados en app.db.
     * null si no hay app.db con tabla de instancias (Modrinth antiguo): entonces vale cualquier carpeta.
     * Se guarda en caché y solo se relee si app.db cambió (y como mucho cada 10 s): puede pesar cientos de MB.
     */
    static synchronized Set<String> modrinthRegisteredFolders() {
        long stamp = 0;
        for (Path cfg : modrinthConfigDirs())
            for (String n : new String[]{"app.db", "app.db-wal"})
                try { Path f = cfg.resolve(n); if (Files.exists(f)) stamp += Files.getLastModifiedTime(f).toMillis() + Files.size(f); }
                catch (IOException ignored) { }
        long now = System.currentTimeMillis();
        if (registeredReadAt != 0 && (stamp == registeredStamp || now - registeredReadAt < 10_000)) return registeredCache;
        Set<String> all = null;
        for (Path cfg : modrinthConfigDirs()) {
            Set<String> names = withModrinthDb(cfg, c -> {
                if (!hasTable(c, "instances")) return null;
                Set<String> out = new HashSet<>();
                try (var st = c.prepareStatement("SELECT path FROM instances"); var rs = st.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString(1).replace('\\', '/');
                        out.add(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
                    }
                }
                return out;
            });
            if (names != null) { if (all == null) all = new HashSet<>(); all.addAll(names); }
        }
        registeredCache = all;
        registeredStamp = stamp;
        registeredReadAt = now;
        return all;
    }

    /** Carpeta "profiles" que el jugador eligió a mano en el launcher (o null). */
    static Path chosenModrinthProfiles() {
        String v = settings != null ? settings.getProperty("modrinthProfiles") : null;
        return v == null || v.isBlank() ? null : Paths.get(v);
    }

    /** La carpeta de perfiles que el launcher cree que usa Modrinth en este PC (para proponerla). */
    static Path guessModrinthProfiles() {
        Path chosen = chosenModrinthProfiles();
        if (chosen != null) return chosen;
        List<Path> roots = new ArrayList<>(modrinthProfileRoots());
        for (Path r : roots) if (!subdirs(r).isEmpty()) return r;   // la primera que tenga perfiles
        for (Path r : roots) if (Files.isDirectory(r)) return r;
        return roots.isEmpty() ? appData().resolve("ModrinthApp").resolve("profiles") : roots.get(0);
    }

    /**
     * Acepta lo que elija el jugador: la carpeta "profiles", la carpeta de Modrinth que la contiene
     * o incluso un perfil concreto (se sube a "profiles").
     */
    static Path normalizeProfilesDir(Path p) {
        if (p == null) return null;
        if (p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("profiles")) return p;
        if (Files.isDirectory(p.resolve("profiles"))) return p.resolve("profiles");
        Path parent = p.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase("profiles")) return parent;
        return p;
    }

    /**
     * Identificador interno de un perfil de Modrinth App.
     * Desde 2026 Modrinth guarda las instancias en app.db (SQLite) con un id tipo "local:<uuid>";
     * en versiones anteriores el id era el nombre de la carpeta.
     * Devuelve null si no se puede averiguar.
     */
    static String modrinthInstanceId(Path profileDir) {
        String folder = profileDir.getFileName().toString();
        // app.db de configuración (AppData) y, por si acaso, la que haya junto a la carpeta de perfiles
        List<Path> dbs = new ArrayList<>(modrinthConfigDirs());
        Path near = profileDir.getParent() != null ? profileDir.getParent().getParent() : null;
        if (near != null && !dbs.contains(near)) dbs.add(near);
        boolean anyDb = false;
        for (Path cfg : dbs) {
            if (!Files.exists(cfg.resolve("app.db"))) continue;
            anyDb = true;
            String id = withModrinthDb(cfg, c -> {
                if (!hasTable(c, "instances")) return "";  // Modrinth antiguo: id = carpeta
                try (var st = c.prepareStatement("SELECT id, path FROM instances"); var rs = st.executeQuery()) {
                    while (rs.next()) {
                        String path = rs.getString(2).replace('\\', '/');
                        if (path.equals(folder) || path.endsWith("/" + folder)) return rs.getString(1);
                    }
                }
                return null;
            });
            if ("".equals(id)) return folder;
            if (id != null) return id;
        }
        return anyDb ? null : folder;
    }

    /** Modrinth App arranca un perfil con el enlace modrinth://launch/instance/<id>. */
    static boolean launchModrinth(Target t) {
        String rawId = modrinthInstanceId(t.dir());
        if (rawId == null) return false;
        String id = URLEncoder.encode(rawId, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create("modrinth://launch/instance/" + id);
        try {
            if (isWindows()) { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toString()).start(); return true; }
            if (isMac()) return tryStart("open", uri.toString());
            if (tryStart("xdg-open", uri.toString())) return true;
            Desktop.getDesktop().browse(uri);
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean launchPrism(String instanceId) {
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("ProgramFiles");
            return (local != null && tryStart(local + "\\Programs\\PrismLauncher\\prismlauncher.exe", "--launch", instanceId))
                    || (pf != null && tryStart(pf + "\\PrismLauncher\\prismlauncher.exe", "--launch", instanceId));
        }
        if (isMac()) return tryStart("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher", "--launch", instanceId);
        return tryStart("prismlauncher", "--launch", instanceId)
                || tryStart("flatpak", "run", "org.prismlauncher.PrismLauncher", "--launch", instanceId);
    }

    // ---------- Crear la instancia automáticamente ----------

    static boolean modrinthInstalled() {
        Path chosen = chosenModrinthProfiles();
        if (chosen != null && Files.isDirectory(chosen)) return true;
        return Files.isDirectory(appData().resolve("ModrinthApp")) || Files.isDirectory(appData().resolve("com.modrinth.theseus"));
    }

    static boolean prismInstalled() {
        return Files.isDirectory(appData().resolve("PrismLauncher"))
                || Files.isDirectory(Paths.get(System.getProperty("user.home"), ".var", "app", "org.prismlauncher.PrismLauncher"));
    }

    static Path cacheDir() throws IOException {
        Path d = defaultGameDir().resolveSibling("." + PROFILE_ID + "-cache");
        Files.createDirectories(d);
        return d;
    }

    /**
     * Crea un .mrpack mínimo (formato de Modrinth) que solo dice "Minecraft X + Forge Y".
     * Modrinth y Prism lo importan y crean la instancia con Forge ya instalado;
     * después este launcher se encarga de los mods.
     */
    static Path buildMrpack(Manifest m) throws IOException {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("minecraft", m.minecraft());
        deps.put("forge", m.forge());
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("formatVersion", new Json.Num("1"));
        idx.put("game", "minecraft");
        idx.put("versionId", m.version());
        idx.put("name", PACK_NAME);
        idx.put("summary", "Instancia del modpack " + PACK_NAME + ". Los mods los instala " + PACK_NAME + " Launcher.");
        idx.put("files", new ArrayList<>());
        idx.put("dependencies", deps);
        Path out = cacheDir().resolve(PACK_NAME + ".mrpack");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(out))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("modrinth.index.json"));
            zip.write(Json.write(idx).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out;
    }

    static boolean openWithModrinth(Path mrpack) {
        if (isWindows()) {
            for (String exe : modrinthExes())
                if (tryStart(exe, mrpack.toString())) return true;
        }
        if (isMac() && tryStart("open", "-a", "Modrinth App", mrpack.toString())) return true;
        try { Desktop.getDesktop().open(mrpack.toFile()); return true; } catch (Exception e) { return false; }
    }

    static boolean openWithPrism(Path mrpack) {
        String p = mrpack.toString();
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA"), pf = System.getenv("ProgramFiles");
            return (local != null && tryStart(local + "\\Programs\\PrismLauncher\\prismlauncher.exe", "--import", p))
                    || (pf != null && tryStart(pf + "\\PrismLauncher\\prismlauncher.exe", "--import", p));
        }
        if (isMac()) return tryStart("/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher", "--import", p);
        return tryStart("prismlauncher", "--import", p) || tryStart("flatpak", "run", "org.prismlauncher.PrismLauncher", "--import", p);
    }

    /** Descarga el instalador oficial de Forge y lo ejecuta sin ventanas sobre .minecraft. */
    static void installForge(String mcVersion, String version, Progress progress) throws Exception {
        Path mc = minecraftDir();
        if (!Files.exists(mc.resolve("launcher_profiles.json"))) {
            // Launcher oficial instalado pero nunca abierto: el instalador de Forge solo necesita que exista este archivo
            Files.createDirectories(mc);
            Files.writeString(mc.resolve("launcher_profiles.json"), "{\n  \"profiles\" : {},\n  \"settings\" : {},\n  \"version\" : 3\n}\n");
        }
        String full = mcVersion + "-" + version;
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + full + "/forge-" + full + "-installer.jar";
        Path jar = cacheDir().resolve("forge-" + full + "-installer.jar");
        progress.update("Descargando Forge " + version + "…", 0.05);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", PACK_NAME + "-Launcher").build();
        HttpResponse<Path> r = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(jar));
        if (r.statusCode() != 200) throw new IOException("No se pudo descargar Forge (HTTP " + r.statusCode() + ")");
        Path java = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        if (!Files.exists(java)) throw new IOException("No se encontró Java para ejecutar el instalador de Forge.");
        progress.update("Instalando Forge " + version + " (puede tardar un par de minutos)…", 0.3);
        Process proc = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--installClient", mc.toString())
                .directory(cacheDir().toFile()).redirectErrorStream(true)
                .redirectOutput(cacheDir().resolve("forge-install.log").toFile()).start();
        double f = 0.3;
        while (proc.isAlive()) {
            Thread.sleep(500);
            f = Math.min(0.95, f + 0.004);
            progress.update("Instalando Forge " + version + " (puede tardar un par de minutos)…", f);
        }
        if (proc.exitValue() != 0 || findForge(mcVersion) == null)
            throw new IOException("La instalación de Forge falló. Mira el registro en:\n" + cacheDir().resolve("forge-install.log"));
        progress.update("Forge instalado", 1);
    }

    // ---------- Estilo ----------

    // Colores por defecto (tema End). Se pueden cambiar sin recompilar con launcher-assets/tema.json
    static Color BG_TOP = new Color(0x0A0612), BG_BOTTOM = new Color(0x2B1745);
    static Color TEXT = new Color(0xEEE8F6), MUTED = new Color(0xA597BC), LINE = new Color(255, 255, 255, 40);
    static Color ACCENT = new Color(0x5FE3B4), ACCENT_SOFT = new Color(0xC9F7E6), BAR_START = new Color(0x8A5CD0);
    static Color PANEL = new Color(0x1B1128), BUTTON_TEXT = new Color(0x0A0612);

    // ---------- Imágenes y tema personalizables (carpeta launcher-assets/ del repositorio) ----------
    //
    //  launcher-assets/fondo.png  -> fondo de la ventana (se recorta para llenarla). Si no está, degradado.
    //  launcher-assets/logo.png   -> imagen grande de la izquierda (PNG con transparencia).
    //  launcher-assets/icono.png  -> icono de la ventana (y del .exe al compilar).
    //  launcher-assets/tema.json  -> colores, título, subtítulo, si el logo gira…
    //  launcher-assets/fuente.ttf -> tipografía de botones y "Novedades" (opcional).
    //  launcher-assets/fuente-titulo.ttf -> tipografía solo del título grande (opcional).
    //
    // El launcher los descarga de GitHub al abrirse y los guarda en caché, así que basta con
    // subir otra imagen al repositorio: la próxima vez que alguien abra el launcher, la verá.

    static final String[] ASSET_FILES = {"fondo.png", "logo.png", "icono.png", "tema.json", "fuente.ttf", "fuente-titulo.ttf"};
    static Image bgImage, logoImage, iconImage;
    static Map<String, Object> theme = new LinkedHashMap<>();

    static Path assetCache() throws IOException {
        Path d = cacheDir().resolve("assets");
        Files.createDirectories(d);
        return d;
    }

    /** Imagen de la caché (última descargada) o, si no hay, la que va dentro del launcher. */
    static Image loadAsset(String name) {
        try {
            Path f = assetCache().resolve(name);
            if (Files.exists(f)) { Image i = javax.imageio.ImageIO.read(f.toFile()); if (i != null) return i; }
        } catch (Exception ignored) { }
        try (InputStream in = Launcher.class.getResourceAsStream("/" + name)) {
            return in == null ? null : javax.imageio.ImageIO.read(in);
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static void loadAssets() {
        bgImage = loadAsset("fondo.png");
        logoImage = loadAsset("logo.png");
        iconImage = loadAsset("icono.png");
        String json = null;
        try { Path f = assetCache().resolve("tema.json"); if (Files.exists(f)) json = Files.readString(f); } catch (Exception ignored) { }
        if (json == null) try (InputStream in = Launcher.class.getResourceAsStream("/tema.json")) {
            if (in != null) json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
        try { if (json != null) theme = (Map<String, Object>) Json.parse(json); } catch (Exception ignored) { theme = new LinkedHashMap<>(); }
        BG_TOP = color("fondoArriba", BG_TOP);
        BG_BOTTOM = color("fondoAbajo", BG_BOTTOM);
        TEXT = color("texto", TEXT);
        MUTED = color("textoSecundario", MUTED);
        ACCENT = color("botones", ACCENT);
        ACCENT_SOFT = color("botonesHover", ACCENT_SOFT);
        BUTTON_TEXT = color("textoBotones", BUTTON_TEXT);
        BAR_START = color("barraInicio", BAR_START);
        PANEL = color("dialogos", PANEL);
    }

    static Color color(String key, Color def) {
        Object v = theme.get(key);
        if (v == null) return def;
        try {
            String h = v.toString().trim().replace("#", "");
            if (h.length() == 8) return new Color((int) Long.parseLong(h, 16), true); // AARRGGBB
            return new Color(Integer.parseInt(h, 16));
        } catch (Exception e) { return def; }
    }

    static double themeNum(String key, double def) {
        Object v = theme.get(key);
        try { return v == null ? def : Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }

    static String themeText(String key, String def) {
        Object v = theme.get(key);
        return v == null || v.toString().isBlank() ? def : v.toString();
    }

    static boolean themeBool(String key, boolean def) {
        Object v = theme.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    /**
     * Descarga de GitHub las imágenes y el tema que hayan cambiado.
     * Devuelve true si algo cambió (para repintar la ventana).
     */
    static boolean refreshAssets(String commit) {
        boolean changed = false;
        for (String name : ASSET_FILES) {
            try {
                Path cache = assetCache().resolve(name);
                String url = RAW_BASE + "/" + commit + "/launcher-assets/" + name;
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", PACK_NAME + "-Launcher/" + LAUNCHER_VERSION).build();
                HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 404) {
                    // Lo has borrado del repositorio: se vuelve a lo de por defecto
                    if (Files.deleteIfExists(cache)) changed = true;
                    continue;
                }
                if (r.statusCode() != 200) continue;
                byte[] data = r.body();
                if (Files.exists(cache) && Arrays.equals(Files.readAllBytes(cache), data)) continue;
                if (name.endsWith(".png") && javax.imageio.ImageIO.read(new ByteArrayInputStream(data)) == null) continue; // imagen rota
                Path tmp = cache.resolveSibling(name + ".part");
                Files.write(tmp, data);
                Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
                changed = true;
            } catch (Exception ignored) { }
        }
        return changed;
    }
    static Font gothicBold, gothicMedium, body, titleFont;

    static Font loadFont(String res, float fallbackSize) {
        try (InputStream in = Launcher.class.getResourceAsStream("/" + res)) {
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f;
        } catch (Exception e) { return new Font(Font.SERIF, Font.BOLD, (int) fallbackSize); }
    }

    /** Tipografía de títulos: launcher-assets/fuente.ttf si existe; si no, la incluida. */
    static void loadFonts() {
        gothicBold = loadFont("GrenzeGotisch-Bold.ttf", 48);
        gothicMedium = loadFont("GrenzeGotisch-Medium.ttf", 20);
        try {
            Path f = assetCache().resolve("fuente.ttf");
            if (Files.exists(f)) {
                Font custom = Font.createFont(Font.TRUETYPE_FONT, f.toFile());
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(custom);
                gothicBold = custom;
                gothicMedium = custom;
            }
        } catch (Exception ignored) { }
        // Título: launcher-assets/fuente-titulo.ttf (caché) -> la que va dentro del launcher -> la de botones
        titleFont = null;
        try {
            Path f = assetCache().resolve("fuente-titulo.ttf");
            if (Files.exists(f)) titleFont = Font.createFont(Font.TRUETYPE_FONT, f.toFile());
        } catch (Exception ignored) { }
        if (titleFont == null) try (InputStream in = Launcher.class.getResourceAsStream("/fuente-titulo.ttf")) {
            if (in != null) titleFont = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) { }
        if (titleFont != null) GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(titleFont);
        else titleFont = gothicBold;
    }

    static void loadStyle() {
        loadFonts();
        String fam = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains("Segoe UI")
                ? "Segoe UI" : Font.SANS_SERIF;
        body = new Font(fam, Font.PLAIN, 13);
        Color panel = PANEL;
        for (String k : List.of("OptionPane.background", "Panel.background")) UIManager.put(k, panel);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("PopupMenu.background", panel);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(LINE));
        UIManager.put("MenuItem.background", panel);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", new Color(0x3E2A5C));
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
        UIManager.put("Separator.foreground", LINE);
    }

    static Image appIcon() { return iconImage; }

    /** Fondo con degradado. */
    static final class Backdrop extends JPanel {
        Backdrop(LayoutManager l) { super(l); setOpaque(true); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, BG_TOP, getWidth() * 0.35f, getHeight(), BG_BOTTOM));
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (bgImage != null) {
                // Imagen de fondo recortada para cubrir toda la ventana, con un velo para que el texto se lea
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int iw = bgImage.getWidth(null), ih = bgImage.getHeight(null);
                double sc = Math.max((double) getWidth() / iw, (double) getHeight() / ih);
                int w = (int) Math.ceil(iw * sc), h = (int) Math.ceil(ih * sc);
                g2.drawImage(bgImage, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                int veil = Math.max(0, Math.min(255, (int) Math.round(255 * themeNum("oscurecerFondo", 0.45))));
                g2.setColor(new Color(0, 0, 0, veil));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.dispose();
        }
    }

    /** El sigilo: gira mientras se descarga y frena suavemente al terminar. */
    static final class Sigil extends JComponent {
        private double angle, speed, targetSpeed;
        private final javax.swing.Timer timer;

        Sigil() {
            setOpaque(false);
            timer = new javax.swing.Timer(16, e -> {
                speed += (targetSpeed - speed) * 0.04;
                if (targetSpeed == 0 && speed < 0.01) { speed = 0; ((javax.swing.Timer) e.getSource()).stop(); }
                angle = (angle + speed) % 360;
                repaint();
            });
        }

        void spin(boolean on) {
            if (!themeBool("logoGira", true)) { targetSpeed = 0; if (angle != 0) { angle = 0; repaint(); } return; }
            targetSpeed = on ? 2.2 : 0;
            if (on && !timer.isRunning()) timer.start();
            if (!on && speed > 0 && !timer.isRunning()) timer.start();
        }

        @Override protected void paintComponent(Graphics g) {
            Image img = logoImage;
            if (img == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = (int) ((Math.min(getWidth(), getHeight()) - 20) * Math.max(0.2, Math.min(1.0, themeNum("tamanoLogo", 1.0))));
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            // halo suave detrás
            float r = size * 0.5f;
            g2.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Double(cx, cy), r,
                    new float[]{0f, 1f}, new Color[]{new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), themeBool("halo", true) ? 34 : 0),
                            new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0)}));
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
            g2.rotate(Math.toRadians(angle), cx, cy);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.92f));
            // respeta la proporción de la imagen
            double ar = (double) img.getWidth(null) / Math.max(1, img.getHeight(null));
            int dw = ar >= 1 ? size : (int) (size * ar), dh = ar >= 1 ? (int) (size / ar) : size;
            g2.drawImage(img, (int) (cx - dw / 2.0), (int) (cy - dh / 2.0), dw, dh, null);
            g2.dispose();
        }
    }

    /** Barra de progreso fina. */
    static final class Bar extends JComponent {
        double value;
        Bar() { setPreferredSize(new Dimension(10, 8)); }
        void set(double v) { value = Math.max(0, Math.min(1, v)); repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight(), w = getWidth();
            g2.setColor(new Color(255, 255, 255, 30));
            g2.fillRoundRect(0, 0, w, h, h, h);
            int fw = (int) Math.round(w * value);
            if (fw > 0) {
                g2.setPaint(new GradientPaint(0, 0, BAR_START, w, 0, ACCENT));
                g2.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
            }
            g2.dispose();
        }
    }

    /** Botón plano: relleno blanco (principal) o contorno (secundario). */
    static final class FlatButton extends JButton {
        private final boolean primary;
        private boolean hover;
        FlatButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setFont(gothicMedium.deriveFont(primary ? 20f : 17f));
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(6, primary ? 26 : 16, 6, primary ? 26 : 16));
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean on = isEnabled();
            if (primary) {
                g2.setColor(!on ? new Color(255, 255, 255, 45) : hover ? ACCENT_SOFT : ACCENT);
                g2.fillRoundRect(0, 0, w, h, 10, 10);
                g2.setColor(on ? BUTTON_TEXT : new Color(255, 255, 255, 110));
            } else {
                g2.setColor(hover && on ? new Color(255, 255, 255, 28) : new Color(0, 0, 0, 0));
                g2.fillRoundRect(0, 0, w, h, 10, 10);
                g2.setColor(on ? new Color(255, 255, 255, 90) : new Color(255, 255, 255, 35));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
                g2.setColor(on ? TEXT : new Color(255, 255, 255, 80));
            }
            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    // ---------- Interfaz ----------

    static Target target;
    static Path gameDir;
    static JLabel status, targetLabel;
    static Bar bar;
    static Sigil sigil;
    static FlatButton updateBtn, playBtn;
    static JEditorPane news;
    static Plan currentPlan;
    static Properties settings;
    static boolean checking;

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--sync")) { headless(Paths.get(args[1])); return; }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(Launcher::buildUi);
    }

    /** Modo sin ventana para pruebas: java Launcher.java --sync <carpeta> */
    static void headless(Path dir) throws Exception {
        Manifest m = fetchManifest();
        Plan p = plan(dir, m);
        System.out.println("Versión " + m.version() + " (commit " + m.commit() + "): " + p.downloads().size()
                + " descargas, " + p.deletions().size() + " borrados, " + p.bytes() / 1024 + " KB");
        apply(dir, p, (t, f) -> { });
        System.out.println("Tras actualizar: " + (plan(dir, m).upToDate() ? "todo al día" : "FALTAN COSAS"));
    }

    static JFrame frame;
    static JLabel titleLabel, subtitleLabel, newsTitle;
    static FlatButton folderBtn;

    static void buildUi() {
        loadAssets();
        loadStyle();
        settings = loadSettings();
        Target initial = targetFromSettings(settings, detectTargets());
        // El perfil guardado ya no existe (p. ej. se borró en Modrinth y se creó otro): recordar el nuevo
        boolean changed = settings.getProperty("targetKind") != null && !initial.dir().toString().equals(settings.getProperty("targetDir"));
        setTarget(initial, changed);

        JFrame f = new JFrame(themeText("tituloVentana", PACK_NAME + " Launcher"));
        frame = f;
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Image icon = appIcon();
        if (icon != null) f.setIconImage(icon);

        Backdrop root = new Backdrop(new BorderLayout(24, 0));
        root.setBorder(new EmptyBorder(22, 22, 22, 26));

        sigil = new Sigil();
        sigil.setPreferredSize(new Dimension(340, 340));
        sigil.setToolTipText(null);
        root.add(sigil, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        titleLabel = label(themeText("titulo", PACK_NAME), titleFont.deriveFont((float) themeNum("tamanoTitulo", 60)), color("colorTitulo", Color.WHITE));
        subtitleLabel = label(subtitle(), body.deriveFont(12f), MUTED);
        header.add(titleLabel);
        header.add(subtitleLabel);
        right.add(header, BorderLayout.NORTH);

        news = new JEditorPane("text/html", "");
        news.setEditable(false);
        news.setOpaque(false);
        news.setBackground(new Color(0, 0, 0, 0));
        news.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        news.setFont(body);
        news.setText(htmlWrap("<p style='color:" + hex(MUTED) + "'>Cargando novedades…</p>"));
        JScrollPane sp = new JScrollPane(news);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        JPanel newsBox = new JPanel(new BorderLayout(0, 6));
        newsBox.setOpaque(false);
        newsTitle = label(themeText("tituloNovedades", "Novedades"), gothicMedium.deriveFont(24f), color("colorTitulo", Color.WHITE));
        newsTitle.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(0, 0, 4, 0)));
        newsBox.add(newsTitle, BorderLayout.NORTH);
        newsBox.add(sp, BorderLayout.CENTER);
        right.add(newsBox, BorderLayout.CENTER);

        status = label("Comprobando actualizaciones…", body.deriveFont(13f), TEXT);
        bar = new Bar();
        updateBtn = new FlatButton("Actualizar", true);
        playBtn = new FlatButton("Jugar", true);
        folderBtn = new FlatButton("Abrir carpeta", false);
        updateBtn.setEnabled(false);
        playBtn.setEnabled(false);
        updateBtn.addActionListener(e -> { if (currentPlan == null) check(); else doUpdate(); });
        playBtn.addActionListener(e -> doPlay());
        folderBtn.addActionListener(e -> {
            try { Files.createDirectories(gameDir); Desktop.getDesktop().open(gameDir.toFile()); }
            catch (Exception ex) { error(ex.getMessage()); }
        });

        targetLabel = label("", body.deriveFont(12f), MUTED);
        targetLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        targetLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) { showTargetMenu(f, targetLabel); }
        });
        refreshTargetLabel();
        targetLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
        header.add(targetLabel);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.add(status, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(folderBtn);
        buttons.add(updateBtn);
        buttons.add(playBtn);

        JPanel actionRow = new JPanel(new BorderLayout());
        actionRow.setOpaque(false);
        actionRow.add(buttons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(0, 10));
        bottom.setOpaque(false);
        bottom.add(statusRow, BorderLayout.NORTH);
        bottom.add(bar, BorderLayout.CENTER);
        bottom.add(actionRow, BorderLayout.SOUTH);
        right.add(bottom, BorderLayout.SOUTH);

        root.add(right, BorderLayout.CENTER);
        f.setContentPane(root);
        f.setSize(940, 520);
        f.setMinimumSize(new Dimension(820, 460));
        f.setLocationRelativeTo(null);
        f.setVisible(true);
        firstRun(f);
    }

    static String subtitle() {
        return themeText("subtitulo", "Modpack · Minecraft " + MC_VERSION + " · Forge " + FORGE_VERSION) + " · launcher " + LAUNCHER_VERSION;
    }

    /** Vuelve a leer imágenes y colores y repinta la ventana (tras descargar los de GitHub). */
    static void applyAssetsLive() {
        if (frame == null) return;
        loadFonts();
        status.setFont(body.deriveFont(13f));
        if (iconImage != null) frame.setIconImage(iconImage);
        frame.setTitle(themeText("tituloVentana", PACK_NAME + " Launcher"));
        titleLabel.setText(themeText("titulo", PACK_NAME));
        titleLabel.setFont(titleFont.deriveFont((float) themeNum("tamanoTitulo", 60)));
        titleLabel.setForeground(color("colorTitulo", Color.WHITE));
        subtitleLabel.setText(subtitle());
        newsTitle.setText(themeText("tituloNovedades", "Novedades"));
        newsTitle.setFont(gothicMedium.deriveFont(24f));
        newsTitle.setForeground(color("colorTitulo", Color.WHITE));
        updateBtn.setFont(gothicMedium.deriveFont(20f));
        playBtn.setFont(gothicMedium.deriveFont(20f));
        folderBtn.setFont(gothicMedium.deriveFont(17f));
        subtitleLabel.setForeground(MUTED);
        targetLabel.setForeground(MUTED);
        status.setForeground(TEXT);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        frame.repaint();
    }

    /** Primera vez: si tiene Modrinth o Prism y no hay instancia del pack, ofrece crearla. */
    static boolean anyLauncherInstalled() { return modrinthInstalled() || prismInstalled() || officialInstalled(); }

    /** PC sin ningún launcher: ofrece instalar uno y sigue solo en cuanto aparece. */
    static void noLauncher(JFrame f) {
        String[] opts = {"Instalar Modrinth (recomendado)", "Instalar launcher oficial", "Cancelar"};
        int r = JOptionPane.showOptionDialog(f, "Para jugar necesitas un launcher de Minecraft y no hay ninguno en este PC.\n\n"
                        + "Instala uno (y entra con tu cuenta de Microsoft). Cuando termine, este launcher seguirá solo.",
                PACK_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if (r == 0 || r == 1) {
            try { Desktop.getDesktop().browse(URI.create(r == 0 ? "https://modrinth.com/app" : "https://www.minecraft.net/download")); }
            catch (Exception ignored) { }
        }
        status.setText("Esperando a que instales un launcher de Minecraft…");
        if (waitTimer != null) waitTimer.stop();
        waitTimer = new javax.swing.Timer(3000, e -> {
            if (anyLauncherInstalled()) {
                ((javax.swing.Timer) e.getSource()).stop();
                setTarget(targetFromSettings(new Properties(), detectTargets()), false);
                refreshTargetLabel();
                firstRun(f);
            }
        });
        waitTimer.start();
        check();
    }

    static void firstRun(JFrame f) {
        boolean firstTime = settings.getProperty("targetKind") == null;
        if (firstTime && !anyLauncherInstalled()) { noLauncher(f); return; }
        boolean m = modrinthInstalled(), p = prismInstalled();
        // Ya existe un perfil "EnderSMP" en Modrinth/Prism: se usa directamente y se recuerda
        if (firstTime && target.kind() != Kind.OFFICIAL) {
            if (target.kind() == Kind.MODRINTH && chosenModrinthProfiles() == null && target.dir().getParent() != null)
                saveModrinthProfiles(target.dir().getParent());
            setTarget(target, true);
        }
        if (!firstTime || target.kind() != Kind.OFFICIAL || (!m && !p)) { check(); return; }
        List<String> opts = new ArrayList<>();
        if (m) opts.add("Modrinth");
        if (p) opts.add("Prism");
        opts.add("Launcher oficial");
        int r = JOptionPane.showOptionDialog(f, "¿Con qué launcher juegas?\n\nSe creará la instancia de " + PACK_NAME
                        + " con Forge ya instalado; no tienes que configurar nada.",
                PACK_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts.toArray(), opts.get(0));
        String pick = r >= 0 ? opts.get(r) : "Launcher oficial";
        if (pick.equals("Modrinth")) {
            if (!askModrinthProfiles(f)) { check(); return; }
            // ¿Ya hay un perfil del pack en esa carpeta? Entonces no hace falta crear otro
            for (Target t : detectTargets())
                if (t.kind() == Kind.MODRINTH && t.dir().startsWith(chosenModrinthProfiles())
                        && t.name().toLowerCase(Locale.ROOT).contains(PACK_NAME.toLowerCase(Locale.ROOT))) { chooseTarget(t); return; }
            createInstance(Kind.MODRINTH);
        }
        else if (pick.equals("Prism")) createInstance(Kind.PRISM);
        else chooseTarget(target);
    }

    static javax.swing.Timer waitTimer;

    /**
     * Pregunta dónde tiene Modrinth los perfiles. Propone la carpeta detectada y deja elegir otra.
     * Devuelve false si el jugador cancela.
     */
    static boolean askModrinthProfiles(Component parent) {
        Path guess = guessModrinthProfiles();
        String[] opts = {"Sí, es esa", "Elegir otra carpeta…", "Cancelar"};
        int r = JOptionPane.showOptionDialog(parent,
                "¿Dónde guarda Modrinth tus perfiles?\n\n" + guess + "\n\n"
                        + "Si en Modrinth (en sus Ajustes, App directory) usas otra carpeta,\n"
                        + "elige la carpeta \"profiles\" que hay dentro.",
                PACK_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (r == 0) { saveModrinthProfiles(guess); return true; }
        if (r != 1) return false;
        return pickModrinthProfiles(parent, guess);
    }

    /** Selector de carpeta. Devuelve true si el jugador eligió una. */
    static boolean pickModrinthProfiles(Component parent, Path start) {
        Path from = start;
        while (from != null && !Files.isDirectory(from)) from = from.getParent();
        JFileChooser ch = new JFileChooser(from != null ? from.toFile() : null);
        ch.setDialogTitle("Carpeta \"profiles\" de Modrinth");
        ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        ch.setAcceptAllFileFilterUsed(false);
        if (ch.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return false;
        Path dir = normalizeProfilesDir(ch.getSelectedFile().toPath());
        if (subdirs(dir).isEmpty()) {
            int ok = JOptionPane.showConfirmDialog(parent, "En " + dir + " no hay ningún perfil todavía.\n¿Usar esta carpeta igualmente?",
                    PACK_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return false;
        }
        saveModrinthProfiles(dir);
        return true;
    }

    static void saveModrinthProfiles(Path dir) {
        settings.setProperty("modrinthProfiles", dir.toAbsolutePath().normalize().toString());
        saveSettings(settings);
    }

    /** Tras cambiar la carpeta: si ahí ya hay un perfil del pack, se selecciona. */
    static void afterModrinthFolderChanged() {
        Path root = chosenModrinthProfiles();
        for (Target t : detectTargets())
            if (t.kind() == Kind.MODRINTH && root != null && t.dir().startsWith(root)
                    && t.name().toLowerCase(Locale.ROOT).contains(PACK_NAME.toLowerCase(Locale.ROOT))) { chooseTarget(t); return; }
        status.setText("Carpeta de Modrinth guardada. Si aún no tienes el perfil, usa \"Crear perfil en Modrinth\".");
    }

    /** Genera el .mrpack, lo abre en Modrinth/Prism y espera a que aparezca la instancia nueva. */
    static void createInstance(Kind kind) {
        String app = kind == Kind.MODRINTH ? "Modrinth" : "Prism";
        Set<Path> before = new HashSet<>();
        for (Target t : detectTargets()) before.add(t.dir());
        updateBtn.setEnabled(false);
        playBtn.setEnabled(false);
        status.setText("Preparando la instancia para " + app + "…");
        new SwingWorker<Boolean, Void>() {
            protected Boolean doInBackground() throws Exception {
                Manifest m = currentPlan != null ? currentPlan.manifest() : fetchManifest();
                Path pack = buildMrpack(m);
                return kind == Kind.MODRINTH ? openWithModrinth(pack) : openWithPrism(pack);
            }
            protected void done() {
                boolean ok;
                try { ok = get(); } catch (Exception e) { ok = false; }
                if (!ok) {
                    error("No se pudo abrir " + app + ". Comprueba que está instalado.");
                    check();
                    return;
                }
                status.setText("Confirma la instalación en " + app + "… este launcher la detectará solo.");
                loadNewsOnly();
                sigil.spin(true);
                long start = System.currentTimeMillis();
                boolean[] askedFolder = {false};
                if (waitTimer != null) waitTimer.stop();
                waitTimer = new javax.swing.Timer(2000, e -> {
                    for (Target t : detectTargets()) {
                        if (t.kind() == kind && !before.contains(t.dir())) {
                            ((javax.swing.Timer) e.getSource()).stop();
                            sigil.spin(false);
                            chooseTarget(t);
                            return;
                        }
                    }
                    if (kind == Kind.MODRINTH && System.currentTimeMillis() - start > 60_000 && !askedFolder[0]) {
                        askedFolder[0] = true;
                        status.setText("¿No aparece? Comprueba la carpeta de perfiles en \"Instalar en\" ▾ → Carpeta de perfiles de Modrinth.");
                    }
                    if (System.currentTimeMillis() - start > 15 * 60_000) {
                        ((javax.swing.Timer) e.getSource()).stop();
                        sigil.spin(false);
                        status.setText("No apareció la instancia. Elígela en \"Instalar en\" cuando esté creada.");
                    }
                });
                waitTimer.start();
            }
        }.execute();
    }

    /** Carga solo las novedades (mientras se espera a que Modrinth/Prism cree la instancia). */
    static void loadNewsOnly() {
        new SwingWorker<String, Void>() {
            protected String doInBackground() { return newsHtml(latestCommit()); }
            protected void done() { try { news.setText(get()); news.setCaretPosition(0); } catch (Exception ignored) { } }
        }.execute();
    }

    static String htmlWrap(String inner) {
        return "<html><body style='font-family:\"" + body.getFamily() + "\";font-size:11px;color:" + hex(TEXT) + ";margin:0'>" + inner + "</body></html>";
    }

    static void setTarget(Target t, boolean save) {
        target = t;
        gameDir = t.dir();
        if (save) {
            settings.setProperty("targetKind", t.kind().name());
            settings.setProperty("targetDir", t.dir().toString());
            saveSettings(settings);
        }
    }

    static void refreshTargetLabel() {
        targetLabel.setText("<html>Instalar en: <u>" + esc(target.label()) + "</u> ▾</html>");
    }

    static void showTargetMenu(Component parent, JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        List<Target> list = detectTargets();
        for (Target t : list) {
            JMenuItem it = new JMenuItem((t.equals(target) ? "✓ " : "   ") + t.label());
            it.setFont(body);
            it.addActionListener(e -> chooseTarget(t));
            menu.add(it);
        }
        menu.addSeparator();
        if (modrinthInstalled()) {
            JMenuItem it = new JMenuItem("＋ Crear perfil en Modrinth");
            it.setFont(body);
            it.addActionListener(e -> { if (chosenModrinthProfiles() != null || askModrinthProfiles(parent)) createInstance(Kind.MODRINTH); });
            menu.add(it);
        }
        if (prismInstalled()) {
            JMenuItem it = new JMenuItem("＋ Crear instancia en Prism");
            it.setFont(body);
            it.addActionListener(e -> createInstance(Kind.PRISM));
            menu.add(it);
        }
        JMenuItem mrDir = new JMenuItem("   Carpeta de perfiles de Modrinth…");
        mrDir.setFont(body);
        mrDir.setToolTipText(String.valueOf(guessModrinthProfiles()));
        mrDir.addActionListener(e -> { if (pickModrinthProfiles(parent, guessModrinthProfiles())) afterModrinthFolderChanged(); });
        menu.add(mrDir);
        JMenuItem other = new JMenuItem("   Otra carpeta…");
        other.setFont(body);
        other.addActionListener(e -> {
            JFileChooser ch = new JFileChooser(gameDir.toFile());
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                Path p = ch.getSelectedFile().toPath();
                chooseTarget(new Target(Kind.CUSTOM, p.toString(), p, null));
            }
        });
        menu.add(other);
        menu.show(anchor, 0, anchor.getHeight());
    }

    static JMenuItem disabledItem(String text) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(body.deriveFont(11f));
        i.setEnabled(false);
        return i;
    }

    static void chooseTarget(Target t) {
        if (waitTimer != null && waitTimer.isRunning()) { waitTimer.stop(); sigil.spin(false); }
        setTarget(t, true);
        refreshTargetLabel();
        check();
    }

    static void check() {
        if (checking) return;
        checking = true;
        currentPlan = null;
        updateBtn.setEnabled(false);
        playBtn.setEnabled(false);
        status.setText("Comprobando actualizaciones…");
        new SwingWorker<Plan, Void>() {
            String html, launcherUrl;
            boolean assetsChanged;
            protected Plan doInBackground() throws Exception {
                Manifest m = fetchManifest();
                assetsChanged = refreshAssets(m.commit());
                if (assetsChanged) loadAssets(); // colores nuevos antes de montar las noticias
                html = newsHtml(m.commit());
                launcherUrl = newerLauncherUrl();
                return plan(gameDir, m);
            }
            protected void done() {
                checking = false;
                try {
                    currentPlan = get();
                    if (assetsChanged) applyAssetsLive();
                    news.setText(html);
                    news.setCaretPosition(0);
                    if (currentPlan.upToDate()) {
                        status.setText("Todo al día · versión " + currentPlan.manifest().version());
                        bar.set(1);
                        playBtn.setEnabled(true);
                    } else {
                        status.setText("Actualización disponible · " + currentPlan.downloads().size() + " archivo(s), "
                                + mb(currentPlan.bytes()));
                        bar.set(0);
                        updateBtn.setEnabled(true);
                        // Lo normal: el modpack cambió en GitHub y se actualiza solo, sin pulsar nada.
                        // Si además habría que quitar mods que no son del pack, se espera a que el jugador lo confirme.
                        if (AUTO_UPDATE && currentPlan.deletions().isEmpty() && settings.getProperty("targetKind") != null)
                            doUpdate();
                    }
                    if (launcherUrl != null) {
                        int r = JOptionPane.showConfirmDialog(null, "Hay una versión nueva del launcher. ¿Abrir la página de descarga?",
                                PACK_NAME, JOptionPane.YES_NO_OPTION);
                        if (r == JOptionPane.YES_OPTION) Desktop.getDesktop().browse(URI.create(launcherUrl));
                    }
                } catch (Exception e) {
                    status.setText("No se pudo comprobar. ¿Tienes internet?");
                    updateBtn.setText("Reintentar");
                    updateBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    static void doUpdate() {
        List<Path> del = currentPlan.deletions();
        if (!del.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, del.size()); i++) sb.append("\n  • ").append(del.get(i).getFileName());
            if (del.size() > 8) sb.append("\n  … y ").append(del.size() - 8).append(" más");
            int r = JOptionPane.showConfirmDialog(null, "En " + target.label() + " hay " + del.size()
                            + " mod(s) que no son del modpack y se quitarán:" + sb + "\n\n¿Continuar?",
                    PACK_NAME, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
        }
        updateBtn.setText("Actualizar");
        updateBtn.setEnabled(false);
        sigil.spin(true);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                apply(gameDir, currentPlan, (text, frac) -> SwingUtilities.invokeLater(() -> {
                    status.setText(text);
                    bar.set(frac);
                }));
                return null;
            }
            protected void done() {
                sigil.spin(false);
                try {
                    get();
                    status.setText("Todo al día · versión " + currentPlan.manifest().version());
                    bar.set(1);
                    playBtn.setEnabled(true);
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    status.setText("Error al actualizar");
                    error(c.getMessage());
                    updateBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    static void doPlay() {
        switch (target.kind()) {
            case MODRINTH -> {
                if (launchModrinth(target)) status.setText("Iniciando " + target.name() + " en Modrinth…");
                else if (openModrinth()) status.setText("Abriendo Modrinth… dale a Jugar en el perfil \"" + target.name() + "\".");
                else status.setText("Abre Modrinth y dale a Jugar en el perfil \"" + target.name() + "\".");
            }
            case PRISM -> {
                if (launchPrism(target.instanceId())) status.setText("Iniciando " + target.name() + " con Prism…");
                else status.setText("Abre Prism y dale a Jugar en \"" + target.name() + "\".");
            }
            case CUSTOM -> JOptionPane.showMessageDialog(null, "Modpack listo en:\n" + gameDir
                    + "\n\nAbre tu launcher y juega con la instancia que usa esa carpeta.", PACK_NAME, JOptionPane.INFORMATION_MESSAGE);
            case OFFICIAL -> {
                if (!officialInstalled()) {
                    if (modrinthInstalled() || prismInstalled()) {
                        error("El launcher oficial no está instalado. Elige Modrinth o Prism en \"Instalar en\".");
                    } else noLauncher((JFrame) SwingUtilities.getWindowAncestor(playBtn));
                    return;
                }
                playOfficial();
            }
        }
    }

    static void playOfficial() {
        String res = ensureProfile(gameDir, currentPlan.manifest().minecraft());
        if ("FORGE".equals(res)) {
            String mcv = currentPlan.manifest().minecraft(), neo = currentPlan.manifest().forge();
            playBtn.setEnabled(false);
            sigil.spin(true);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception {
                    installForge(mcv, neo, (text, frac) -> SwingUtilities.invokeLater(() -> { status.setText(text); bar.set(frac); }));
                    return null;
                }
                protected void done() {
                    sigil.spin(false);
                    playBtn.setEnabled(true);
                    try { get(); playOfficial(); }
                    catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        status.setText("No se pudo instalar Forge");
                        error(c.getMessage());
                    }
                }
            }.execute();
            return;
        }
        if (res != null) {
            JOptionPane.showMessageDialog(null, res + "\n\nPuedes jugar igualmente: crea un perfil de Forge " + currentPlan.manifest().forge() + " en tu launcher\n"
                    + "con esta carpeta de juego:\n" + gameDir, PACK_NAME, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            openOfficialLauncher();
            status.setText("Abriendo Minecraft… elige el perfil \"" + PACK_NAME + "\" y dale a Jugar.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Perfil \"" + PACK_NAME + "\" listo. Abre el launcher de Minecraft y elígelo.",
                    PACK_NAME, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static String mb(long b) { return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0); }

    static void error(String msg) { JOptionPane.showMessageDialog(null, msg, PACK_NAME, JOptionPane.ERROR_MESSAGE); }

    static Path settingsFile() { return defaultGameDir().resolveSibling("." + PROFILE_ID + ".properties"); }

    static Properties loadSettings() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(settingsFile())) { p.load(r); } catch (IOException ignored) { }
        return p;
    }

    static void saveSettings(Properties p) {
        try (Writer w = Files.newBufferedWriter(settingsFile())) { p.store(w, null); } catch (IOException ignored) { }
    }

    // ---------- JSON mínimo (sin dependencias) ----------

    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("JSON con basura al final");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = str(); ws(); expect(':'); ws();
                    m.put(k, value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    ws(); l.add(value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']'); return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON no válido en la posición " + i);
            return new Num(s.substring(st, i));
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("Se esperaba '" + c + "' en la posición " + i);
            i++;
        }

        private String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }

        /** Número guardado tal cual, para no alterar archivos ajenos al reescribirlos. */
        record Num(String raw) { public String toString() { return raw; } }

        static String write(Object v) { StringBuilder b = new StringBuilder(); write(v, b, 0); return b.append('\n').toString(); }

        @SuppressWarnings("unchecked")
        private static void write(Object v, StringBuilder b, int ind) {
            if (v == null) b.append("null");
            else if (v instanceof Boolean || v instanceof Num) b.append(v);
            else if (v instanceof Number) b.append(v);
            else if (v instanceof String str) quote(str, b);
            else if (v instanceof Map<?, ?> m) {
                if (m.isEmpty()) { b.append("{}"); return; }
                b.append("{\n");
                Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> e = it.next();
                    b.append("  ".repeat(ind + 1)); quote(e.getKey().toString(), b); b.append(" : ");
                    write(e.getValue(), b, ind + 1);
                    if (it.hasNext()) b.append(',');
                    b.append('\n');
                }
                b.append("  ".repeat(ind)).append('}');
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) { b.append("[]"); return; }
                b.append("[\n");
                for (int k = 0; k < l.size(); k++) {
                    b.append("  ".repeat(ind + 1)); write(l.get(k), b, ind + 1);
                    if (k < l.size() - 1) b.append(',');
                    b.append('\n');
                }
                b.append("  ".repeat(ind)).append(']');
            } else quote(v.toString(), b);
        }

        private static void quote(String s, StringBuilder b) {
            b.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                    default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
                }
            }
            b.append('"');
        }
    }
}
