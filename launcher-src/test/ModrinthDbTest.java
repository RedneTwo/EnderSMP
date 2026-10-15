import java.nio.file.*;
import java.sql.*;

/** Comprueba que el launcher encuentra el id interno de un perfil de Modrinth en app.db (con WAL, como Modrinth abierto). */
public class ModrinthDbTest {
    public static void main(String[] a) throws Exception {
        Path app = Files.createTempDirectory("ModrinthApp");
        Path profile = Files.createDirectories(app.resolve("profiles").resolve("EnderSMP (1)"));

        // 1) Modrinth antiguo: sin app.db -> id = nombre de carpeta
        check("sin app.db", Launcher.modrinthInstanceId(profile), "EnderSMP (1)");

        // 2) Modrinth actual: tabla instances en app.db, datos solo en el WAL y la conexión abierta
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + app.resolve("app.db"))) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA wal_autocheckpoint=0");
                st.execute("CREATE TABLE instances (id TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY (id), UNIQUE (path))");
                st.execute("INSERT INTO instances VALUES ('local:aaaa', 'Otro', 'Otro')");
                st.execute("INSERT INTO instances VALUES ('local:5f0c2b9e-1111-4c2a-9d7e-abcdefabcdef', 'EnderSMP (1)', 'EnderSMP')");
            }
            System.out.println("WAL existe: " + Files.exists(app.resolve("app.db-wal")));
            check("con app.db", Launcher.modrinthInstanceId(profile), "local:5f0c2b9e-1111-4c2a-9d7e-abcdefabcdef");
            check("perfil que no está", Launcher.modrinthInstanceId(Files.createDirectories(app.resolve("profiles").resolve("Nuevo"))), null);
        }
    }

    static void check(String what, String got, String want) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "OK    " : "FALLO ") + what + ": " + got);
        if (!ok) throw new AssertionError(what);
    }
}
