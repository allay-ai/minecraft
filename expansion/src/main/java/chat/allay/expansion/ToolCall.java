package chat.allay.expansion;

import java.util.UUID;

/**
 * One call of a tool: who asked, with what arguments, and how. {@link #nameOf} and {@link #canSee} read the game, so call
 * them on the server thread; the rest is safe anywhere.
 *
 * @deprecated Expansion API 1 stays for plugins built against it. New code extends {@link chat.allay.expansion.v2.Expansion}.
 */
@Deprecated
public interface ToolCall {
  /** How the player asked. */
  enum Mode {
    /** A question: {@code /ask}, {@code /askall}, or chat mode. */
    ASK,
    /** A request turned into commands: {@code /askx}. Only {@link AllayTool.Access#READ} tools run here. */
    ASKX,
  }

  Asker asker();

  Args args();

  Mode mode();

  /** True when everyone online reads the question and the answer. */
  boolean isPublic();

  /**
   * How the model may call another player: their name when they agreed to Allay's notice on this server, else this run's
   * “Player N”. Name other players only through this.
   */
  String nameOf(UUID player, String name);

  /** Whether the asker may learn about another player at all: vanished players stay hidden. */
  boolean canSee(UUID player);

  /**
   * Whether the asker mustn't learn about a player who's online now, like a vanished one; false for players who are
   * offline. Taken when the call starts, so safe on any thread. API 2 leaves such players out of a tool's result.
   *
   * @since 2.0.0
   */
  default boolean hides(UUID player) {
    return false;
  }
}
