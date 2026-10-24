package chat.allay.expansion;

/**
 * The version of the expansion API. An expansion returns {@link #VERSION} from {@link AllayExpansion#requiresApi()}:
 * the compiler copies the constant into the expansion's own class, so Allay learns which version it was built against.
 *
 * <p>Within a major version the API only grows: new methods on interfaces come with defaults, so an expansion built
 * against 1.0 runs on every Allay with API 1.x. Allay refuses an expansion that needs a newer minor or another major,
 * and says so in its log.
 */
public final class AllayApi {
  /** This API's version, {@code major.minor.patch}. */
  public static final String VERSION = "1.0.0";

  private AllayApi() {}

  /**
   * Whether an API of version {@code available} can run an expansion that asks for {@code required}: the same major,
   * and a minor at least as high. Patches never change what an expansion may call.
   */
  public static boolean satisfies(String available, String required) {
    int[] have = parse(available);
    int[] need = parse(required);
    if (have == null || need == null) return false;
    return have[0] == need[0] && have[1] >= need[1];
  }

  static int[] parse(String version) {
    if (version == null) return null;
    String[] parts = version.trim().split("\\.");
    if (parts.length < 2 || parts.length > 3) return null;
    int[] numbers = new int[3];
    try {
      for (int i = 0; i < parts.length; i++) {
        numbers[i] = Integer.parseInt(parts[i]);
        if (numbers[i] < 0) return null;
      }
    } catch (NumberFormatException e) {
      return null;
    }
    return numbers;
  }
}
