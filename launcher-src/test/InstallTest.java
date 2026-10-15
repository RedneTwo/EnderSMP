/** Prueba usada por la Action "Pruebas en Windows": instala Forge en una .minecraft vacía. Uso: InstallTest <mc> <forge> */
public class InstallTest {
    public static void main(String[] a) throws Exception {
        java.nio.file.Path mc = Launcher.minecraftDir();
        java.nio.file.Files.createDirectories(mc);
        // Sin launcher_profiles.json: simula un launcher oficial instalado pero nunca abierto
        System.out.println("java.home = " + System.getProperty("java.home"));
        System.out.println("mrpack = " + Launcher.buildMrpack(new Launcher.Manifest("t", a[0], a[1], "main", java.util.List.of(), java.util.List.of("mods"))));
        Launcher.installForge(a[0], a[1], (t, f) -> { });
        System.out.println("Versión detectada: " + Launcher.findForge(a[0]));
        System.out.println("Perfil: " + (Launcher.ensureProfile(java.nio.file.Paths.get("C:/test-pack"), a[0]) == null ? "OK" : "FALLO"));
    }
}
