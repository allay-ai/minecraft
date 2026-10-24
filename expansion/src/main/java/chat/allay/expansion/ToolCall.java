package chat.allay.expansion;

import java.util.UUID;

/** One call of a tool: who asked, with what arguments, and how. */
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
}
