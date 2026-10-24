package chat.allay.expansion;

/**
 * One tool the model may call. It runs on the server thread, for the player who asked, and returns data the model reads;
 * a tool that waits on the network or a database hands its work elsewhere and returns {@link ToolResult#later}.
 * Allay checks {@link #access()} and {@link #permission()} before every call, and calls it only in a run the backend
 * offered it to.
 */
public interface AllayTool {
  /** What a tool may do in the game. Allay offers it only where that's allowed. */
  enum Access {
    /** Reads the game and changes nothing: offered to everyone, in {@code /askx} too. */
    READ,
    /** Changes only the asker's own things, like sorting their inventory or setting their option: never in {@code /askx}. */
    OWN,
    /** Changes the game beyond the asker's things, or gives anything: operators only, checked on every call. */
    OPERATOR,
  }

  /**
   * Lower-case letters, digits, and underscores, 1 to 32 long, like {@code caffeine}. The model sees it as
   * {@code x_<expansion id>_<name>}.
   */
  String name();

  /**
   * What the tool does and when to call it, in English, for the model; at most 1000 characters. Say what the result
   * holds: the model reads only this before it calls.
   */
  String description();

  /** The arguments the model passes, as a JSON Schema object. {@link Schema#none()} for no arguments. */
  default Schema parameters() {
    return Schema.none();
  }

  default Access access() {
    return Access.READ;
  }

  /**
   * A permission the asker must also have, like {@code redbull.allay}; null for none. Where no permissions plugin or
   * mod answers, everyone has it.
   */
  default String permission() {
    return null;
  }

  /**
   * Whether the tool may run in a question everyone online reads ({@code /askall}). Leave it false when the result is
   * about the asker alone.
   */
  default boolean inPublic() {
    return false;
  }

  /**
   * Runs the call on the server thread. Throw {@link ToolException} with a short English reason the model can act on
   * (“the player holds no can”); any other exception reaches the model only as “failed”. Never block here on the
   * network, a file, or a database: read the game now, then return {@link ToolResult#later} and finish elsewhere.
   */
  ToolResult run(ToolCall call) throws ToolException;
}
