package shots;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs what shoot.py writes to plugins/driver.txt, a line at a time, as the first player online: a command as typed,
 * `@command` from the console, `!chat text` as a chat line, `!clear` to push old lines out of the chat, `!respawn`.
 * Deletes the file once done, which tells shoot.py the lines ran.
 */
public final class Driver extends JavaPlugin {
  @Override
  public void onEnable() {
    Bukkit.getScheduler().runTaskTimer(this, () -> {
      Path file = getDataFolder().toPath().getParent().resolve("driver.txt");
      if (!Files.exists(file)) return;
      try {
        String text = Files.readString(file);
        Files.delete(file);
        Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        for (String line : text.split("\n")) {
          line = line.strip();
          if (line.isEmpty()) continue;
          if (line.startsWith("@")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.substring(1));
          else if (player == null) continue;
          else if (line.equals("!respawn")) player.spigot().respawn();
          else if (line.equals("!clear")) for (int i = 0; i < 40; i++) player.sendMessage("");
          else if (line.startsWith("!chat ")) player.chat(line.substring(6));
          else player.performCommand(line);
        }
      } catch (Exception error) {
        getLogger().warning(error.toString());
      }
    }, 10, 10);
  }
}
