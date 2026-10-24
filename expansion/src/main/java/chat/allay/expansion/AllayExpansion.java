package chat.allay.expansion;

import java.util.List;

/**
 * What a plugin or mod adds to Allay: tools the model may call, items and recipes the game's registries don't show,
 * and a few lines for the model's prompt. Write one class for every platform; register it with
 * {@link AllayExpansions#register} or name it in {@code META-INF/services/chat.allay.expansion.AllayExpansion}.
 *
 * <p>Allay reads {@link #tools()}, {@link #items()}, and {@link #promptExpansion()} when the expansion registers and
 * again on {@code /allay reload}, so keep them cheap and the same between calls.
 */
public interface AllayExpansion {
  /**
   * A short id of lower-case letters, digits, and underscores, 1 to 24 long, like {@code redbull}. It names the
   * expansion in the model's tools ({@code x_redbull_caffeine}), in the log, and in {@code expansions.disabled}.
   */
  String id();

  /** Return {@link AllayApi#VERSION}, so the version you compiled against is the one Allay sees. */
  String requiresApi();

  /** The name people know your plugin or mod by, like {@code Red Bull}; the id by default. */
  default String name() {
    return id();
  }

  /** Your plugin's or mod's own version, for the log and the backend's records. */
  default String version() {
    return "";
  }

  /** Tools the model may call. At most 32 per expansion. */
  default List<AllayTool> tools() {
    return List.of();
  }

  /** Items, with their names and recipes, that the server's registries and recipes don't show Allay on their own. */
  default List<ItemInfo> items() {
    return List.of();
  }

  /**
   * Lines the model reads as part of its prompt, under “## Name's plugin prompt”: what your plugin adds to the game and
   * how to help players with it. Plain English text, at most 2000 characters; null or empty for none. Allay's own rules
   * (safety, privacy, operator tools, the player's language) always come first, and the model reads this as advice from
   * the server's plugin, not as a way to change them.
   */
  default String promptExpansion() {
    return null;
  }
}
