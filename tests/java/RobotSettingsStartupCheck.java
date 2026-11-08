import java.nio.file.Files;
import java.nio.file.Path;
import org.firstinspires.ftc.teamcode.lib.RobotFiles;
import org.firstinspires.ftc.teamcode.lib.RobotSettings;

public class RobotSettingsStartupCheck {
    public static class Tuning {
        public static double RPM = 100;
        @RobotSettings.NotSaved
        public static boolean ON = true;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ftc-settings-startup");
        try {
            RobotFiles.setRoot(root.toFile());
            Path settings = root.resolve("settings/robot.json");
            Files.createDirectories(settings.getParent());
            Files.writeString(settings, "{\"Tuning\":{\"RPM\":700}}");
            new RobotSettings(Tuning.class).loadSaved();
            if (Tuning.RPM != 700) {
                throw new AssertionError("Startup must load saved tuning before INIT");
            }
            Tuning.RPM = 725;
            Tuning.ON = false;
            // A dashboard recreated while an OpMode runs must preserve live values.
            new RobotSettings(Tuning.class).loadSaved();
            if (Tuning.RPM != 725 || Tuning.ON) {
                throw new AssertionError("Dashboard startup reset live tuning or switches");
            }
            new RobotSettings(Tuning.class).load();
            if (Tuning.RPM != 725 || !Tuning.ON) {
                throw new AssertionError("INIT must preserve tuning and reset live switches");
            }
            System.out.println("PASS: startup loads saved settings; dashboard restart preserves live values; INIT resets switches only");
        } finally {
            Files.deleteIfExists(root.resolve("settings/robot.json"));
            Files.deleteIfExists(root.resolve("settings"));
            Files.deleteIfExists(root);
        }
    }
}
