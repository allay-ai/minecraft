package chat.allay.expansion;

import java.util.ArrayList;
import java.util.List;

/**
 * Where expansions meet Allay. Call {@link #register} from your plugin's {@code onEnable} or your mod's server start,
 * only when Allay is installed: the classes of this API come with Allay, so without it they aren't there.
 *
 * <pre>{@code
 * if (getServer().getPluginManager().getPlugin("Allay") != null) AllayExpansions.register(new RedBullExpansion());
 * }</pre>
 *
 * Instead, name your class in {@code META-INF/services/chat.allay.expansion.AllayExpansion}: Allay finds it in every
 * plugin and mod by itself, and nothing in your code needs Allay to be there.
 */
public final class AllayExpansions {
  /** What Allay plugs in when it starts. Not for expansions: they call the static methods. */
  public interface Host {
    void register(AllayExpansion expansion);

    void unregister(String id);

    /** The version of the API this Allay implements. */
    String apiVersion();
  }

  private static final List<AllayExpansion> registered = new ArrayList<>();
  private static Host host;

  private AllayExpansions() {}

  /**
   * Adds an expansion, or replaces the one with its id. It stays registered until {@link #unregister}: an Allay that
   * starts later, like the one under each singleplayer world, gets it too. A broken expansion (a bad id or name, a
   * newer API than this Allay's) is refused with a line in the server's log.
   */
  public static synchronized void register(AllayExpansion expansion) {
    if (expansion == null) throw new IllegalArgumentException("expansion is null");
    registered.removeIf(it -> it.id().equals(expansion.id()));
    registered.add(expansion);
    if (host != null) host.register(expansion);
  }

  /** Takes an expansion out, for a plugin that's being disabled. */
  public static synchronized void unregister(String id) {
    registered.removeIf(it -> it.id().equals(id));
    if (host != null) host.unregister(id);
  }

  /** The API version of the Allay running now; {@link AllayApi#VERSION} before Allay has started. */
  public static synchronized String apiVersion() {
    return host != null ? host.apiVersion() : AllayApi.VERSION;
  }

  /** Whether Allay runs and takes expansions now. */
  public static synchronized boolean available() {
    return host != null;
  }

  /**
   * Allay starts taking expansions, every one registered so far first; null when it stops. Allay calls this; an
   * expansion never does.
   */
  public static synchronized void bind(Host allay) {
    host = allay;
    if (allay != null) List.copyOf(registered).forEach(allay::register);
  }
}
