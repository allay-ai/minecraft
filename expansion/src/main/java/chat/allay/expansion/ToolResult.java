package chat.allay.expansion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * What a tool hands back: data the model reads, and a short line the asker sees under the tool's name in chat.
 *
 * <p>The data is a map of strings, numbers, booleans, nulls, lists, and maps of them, and nothing else. Keep it small:
 * the model pays for every character on every step, and Allay cuts a result over 8000 characters. Use the game's ids
 * ({@code redbull:original}), absolute coordinates, and numbers rather than prose.
 *
 * <p>A tool that waits on the network or a database returns {@link #later} instead, so the server never waits for it.
 */
public final class ToolResult {
  private final Map<String, Object> data;
  private final String summary;
  private final CompletionStage<ToolResult> later;

  private ToolResult(Map<String, Object> data, String summary, CompletionStage<ToolResult> later) {
    this.data = data;
    this.summary = summary;
    this.later = later;
  }

  public static ToolResult of(Map<String, ?> data) {
    return new ToolResult(Collections.unmodifiableMap(new LinkedHashMap<>(data == null ? Map.of() : data)), null, null);
  }

  /**
   * A result that comes later, off the server thread: return it from {@link AllayTool#run} after reading on the server
   * thread what you need, and complete {@code result} from your own thread pool, a database driver, or an HTTP client.
   * Complete it exceptionally with a {@link ToolException} to refuse. Allay waits as long as for any tool (its
   * {@code http.tool_timeout}, 10 seconds by default), then cancels it and tells the model the server took too long.
   * The result it completes with can't be {@code later} again. Since API 1.1.
   */
  public static ToolResult later(CompletionStage<ToolResult> result) {
    if (result == null) throw new IllegalArgumentException("result is null");
    return new ToolResult(Map.of(), null, result);
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
    if (later != null) throw new IllegalStateException("give the summary to the result that comes later");
    return new ToolResult(data, summary, null);
  }

  public Map<String, Object> data() {
    return data;
  }

  public String summary() {
    return summary;
  }

  /** What brings the result when it comes {@link #later}; null for a result that's here. */
  public CompletionStage<ToolResult> pending() {
    return later;
  }
}
