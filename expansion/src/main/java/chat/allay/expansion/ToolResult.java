package chat.allay.expansion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tool hands back: data the model reads, and a short line the asker sees under the tool's name in chat.
 *
 * <p>The data is a map of strings, numbers, booleans, nulls, lists, and maps of them, and nothing else. Keep it small:
 * the model pays for every character on every step, and Allay cuts a result over 8000 characters. Use the game's ids
 * ({@code redbull:original}), absolute coordinates, and numbers rather than prose.
 */
public final class ToolResult {
  private final Map<String, Object> data;
  private final String summary;

  private ToolResult(Map<String, Object> data, String summary) {
    this.data = data;
    this.summary = summary;
  }

  public static ToolResult of(Map<String, ?> data) {
    return new ToolResult(Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data)), null);
  }

  /** A result of one text, for a tool whose answer is a sentence. */
  public static ToolResult text(String text) {
    return of(Map.of("text", text == null ? "" : text));
  }

  /**
   * The line under the tool in the asker's chat, in their language ({@link Asker#locale()}), like “caffeine 64”;
   * at most 60 characters. Without one the line stays empty.
   */
  public ToolResult summary(String summary) {
    return new ToolResult(data, summary);
  }

  public Map<String, Object> data() {
    return data;
  }

  public String summary() {
    return summary;
  }
}
