package chat.allay.expansion.v2;

import chat.allay.expansion.Asker;
import chat.allay.expansion.ToolException;
import java.util.UUID;

/**
 * One call of a {@link Tool}: its arguments, who asked, and how. {@link #player} and {@link #canSee} read the game, so
 * call them on the server thread, before a slow tool hands its work elsewhere; the rest is safe anywhere.
 */
public interface Call extends Values {
  /** Who asked, taken when the call started. */
  Asker asker();

  /**
   * The platform's own player who asked: {@code org.bukkit.entity.Player} on Paper and Spigot,
   * {@code net.minecraft.server.level.ServerPlayer} on Fabric and NeoForge. Throws a {@link ToolException} the model
   * reads when the asker isn't that type.
   */
  <P> P player(Class<P> type) throws ToolException;

  /** True when everyone online reads the question and the answer ({@code /askall}). */
  boolean isPublic();

  /** True in {@code /askx}, a request turned into commands, where only reading tools run. */
  boolean askx();

  /** Whether the asker may learn about another player at all: vanished players stay hidden. */
  boolean canSee(UUID player);
}
