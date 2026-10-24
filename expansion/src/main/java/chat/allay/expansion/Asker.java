package chat.allay.expansion;

import java.util.UUID;

/** The player who asked, as every platform describes them. Read it on the server thread, where the tool runs. */
public interface Asker {
  UUID uuid();

  String name();

  /** The language their game is set to, like {@code en_us} or {@code ru_ru}. */
  String locale();

  boolean isOperator();

  /** Whether they have a permission; {@code byDefault} answers where no permissions plugin or mod does. */
  boolean hasPermission(String permission, boolean byDefault);

  /** The dimension they stand in, like {@code overworld}, {@code the_nether}, or a mod's {@code mymod:moon}. */
  String dimension();

  int x();

  int y();

  int z();

  /**
   * The platform's own player: {@code org.bukkit.entity.Player} on Paper and Spigot,
   * {@code net.minecraft.server.level.ServerPlayer} on Fabric and NeoForge. Null when it isn't that type.
   */
  <T> T unwrap(Class<T> type);
}
