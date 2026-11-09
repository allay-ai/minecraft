package chat.allay.expansion.v2;

import chat.allay.expansion.AllayExpansions;
import chat.allay.expansion.ItemInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * What a plugin or mod adds to Allay: tools the model may call, items and recipes the game's registries don't show,
 * and a few lines for the model's prompt. Extend it with a class or a Kotlin {@code object}, and declare the arguments
 * and tools as its fields; keep each call in a method of its own:
 *
 * <pre>{@code
 * object BossesTools : Expansion("mobosses", "Mo’Bosses") {
 *     val radius = integer("radius", "How far to look, in blocks", 8, 128).orElse(64)
 *     val nearby = tool("nearby", "The bosses near the asker, nearest first …", ::nearby).args(radius).summary { "${it.bosses.size}" }
 *
 *     override fun prompt() = PROMPT
 *
 *     private fun nearby(call: Call): Nearby = …
 * }
 *
 * BossesTools.register()
 * }</pre>
 *
 * Call {@link #register()} once Allay is installed, or name the class in
 * {@code META-INF/services/chat.allay.expansion.v2.Expansion}: Allay finds it in every plugin and mod by itself, a
 * Kotlin {@code object} through its {@code INSTANCE}, a class through a constructor without parameters.
 *
 * <p>Arguments and tools are fixed once the expansion registers. {@link #version()}, {@link #prompt()}, and
 * {@link #items()} are read when it registers and again on {@code /allay reload}, so they may follow your config.
 */
public abstract class Expansion extends ArgGroup {
  private final String id;
  private final String name;
  private final List<Tool<?>> tools = new ArrayList<>();

  /**
   * @param id a short id of lower-case letters, digits, and underscores, 1 to 24 long, like {@code redbull}. It names the
   *     expansion in the model's tools ({@code x_redbull_caffeine}), in the log, and in {@code expansions.disabled}.
   */
  protected Expansion(String id) {
    this(id, id);
  }

  /** @param name the name people know your plugin or mod by, like {@code Red Bull} */
  protected Expansion(String id, String name) {
    if (id == null || !id.matches("[a-z0-9_]{1,24}")) throw new IllegalArgumentException("an expansion's id is 1–24 lower-case letters, digits, or underscores: " + id);
    this.id = id;
    this.name = name == null || name.isBlank() ? id : name.trim();
  }

  public final String id() {
    return id;
  }

  public final String name() {
    return name;
  }

  /** Your plugin's or mod's own version, for the log and the backend's records. */
  public String version() {
    return "";
  }

  /**
   * Lines the model reads as part of its prompt, under “## Name's plugin prompt”: what your plugin adds to the game and
   * how to help players with it. Plain English text, at most 2000 characters; null for none. Allay's own rules always
   * come first, and the model reads this as advice from the server's plugin.
   */
  public String prompt() {
    return null;
  }

  /** Items, with their names and recipes, that the server's registries and recipes don't show Allay on their own. */
  public List<ItemInfo> items() {
    return List.of();
  }

  /** Every tool it declared, in order. */
  public final List<Tool<?>> tools() {
    return List.copyOf(tools);
  }

  /** A tool that answers on the server thread. At most 32 per expansion. */
  protected final <T> Tool<T> tool(String name, String description, Tool.Handler<T> handler) {
    return add(new Tool<>(this, name, description, handler, null));
  }

  /** A tool that reads the game on the server thread and answers later from another, for one that waits on a database or the network. */
  protected final <T> Tool<T> toolLater(String name, String description, Tool.LaterHandler<T> handler) {
    return add(new Tool<>(this, name, description, null, handler));
  }

  private <T> Tool<T> add(Tool<T> tool) {
    checkOpen();
    if (tools.size() >= 32) throw new IllegalArgumentException(id + " has more than 32 tools");
    if (tools.stream().anyMatch(it -> it.name().equals(tool.name()))) throw new IllegalArgumentException(id + " declares the tool " + tool.name() + " twice");
    tools.add(tool);
    return tool;
  }

  /**
   * Hands the expansion to Allay, or replaces the one with its id. It stays registered until {@link #unregister()}: an
   * Allay that starts later, like the one under each singleplayer world, gets it too. A broken expansion is refused
   * with a line in the server's log.
   */
  @SuppressWarnings("deprecation")
  public final void register() {
    freeze();
    AllayExpansions.register(new Adapter(this));
  }

  /** Takes the expansion out, for a plugin that's being disabled. */
  @SuppressWarnings("deprecation")
  public final void unregister() {
    AllayExpansions.unregister(id);
  }

  /** Whether Allay runs and takes expansions now. */
  @SuppressWarnings("deprecation")
  public static boolean available() {
    return AllayExpansions.available();
  }
}
