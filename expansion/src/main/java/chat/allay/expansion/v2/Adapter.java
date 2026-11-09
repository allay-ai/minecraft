package chat.allay.expansion.v2;

import chat.allay.expansion.AllayApi;
import chat.allay.expansion.AllayExpansion;
import chat.allay.expansion.AllayTool;
import chat.allay.expansion.Asker;
import chat.allay.expansion.ItemInfo;
import chat.allay.expansion.Schema;
import chat.allay.expansion.ToolCall;
import chat.allay.expansion.ToolException;
import chat.allay.expansion.ToolResult;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * An API 2 expansion as Allay's host reads every expansion: the declarations become {@link AllayTool}s, so the checks,
 * the limits, and the protocol stay the same for both APIs. This class runs from Allay's own copy of the API.
 */
@SuppressWarnings("deprecation")
final class Adapter implements AllayExpansion {
  private final Expansion expansion;
  private final List<AllayTool> tools;

  Adapter(Expansion expansion) {
    this.expansion = expansion;
    this.tools = expansion.tools().stream().<AllayTool>map(ToolAdapter::new).toList();
  }

  @Override
  public String id() {
    return expansion.id();
  }

  @Override
  public String requiresApi() {
    return AllayApi.VERSION;
  }

  @Override
  public String name() {
    return expansion.name();
  }

  @Override
  public String version() {
    return expansion.version();
  }

  @Override
  public List<AllayTool> tools() {
    return tools;
  }

  @Override
  public List<ItemInfo> items() {
    return expansion.items();
  }

  @Override
  public String promptExpansion() {
    return expansion.prompt();
  }

  private record ToolAdapter(Tool<?> tool) implements AllayTool {
    @Override
    public String name() {
      return tool.name();
    }

    @Override
    public String description() {
      return tool.description();
    }

    @Override
    public Schema parameters() {
      return ArgGroup.schema(tool.args());
    }

    @Override
    public Access access() {
      return Access.valueOf(tool.access().name());
    }

    @Override
    public String permission() {
      return tool.permission();
    }

    @Override
    public boolean inPublic() {
      return tool.isInPublic();
    }

    @Override
    public boolean offeredTo(Asker asker) {
      return tool.offers(asker);
    }

    @Override
    public ToolResult run(ToolCall raw) throws ToolException {
      try {
        Values values = ArgGroup.read(tool.args(), raw.args().asMap(), "");
        Session call = new Session(raw, values);
        if (tool.later() != null) {
          CompletionStage<?> stage = tool.later().run(call);
          if (stage == null) throw new ToolException(tool.name() + " returned nothing");
          return ToolResult.later(stage.thenApply(value -> result(value, call)));
        }
        return result(tool.handler().run(call), call);
      } catch (ToolException | RuntimeException error) {
        throw error;
      } catch (LinkageError error) {
        // The expansion reached for a class or method of a newer API than this Allay's.
        throw new ToolException("this server's Allay is too old for this tool; an admin should update Allay");
      } catch (Exception error) {
        throw new IllegalStateException(error.getMessage(), error);
      }
    }

    private ToolResult result(Object value, Session call) {
      Object out = Output.of(value, call.raw);
      ToolResult result;
      if (out instanceof Map<?, ?> map) {
        Map<String, Object> data = new LinkedHashMap<>();
        map.forEach((key, item) -> data.put(String.valueOf(key), item));
        result = ToolResult.of(data);
      } else if (out instanceof String text) {
        result = ToolResult.text(text);
      } else {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", out);
        result = ToolResult.of(data);
      }
      String summary = tool.summarize(value, call.asker());
      return summary == null ? result : result.summary(summary);
    }
  }

  /** What a tool sees of its call. */
  private static final class Session implements Call {
    private final ToolCall raw;
    private final Values values;

    Session(ToolCall raw, Values values) {
      this.raw = raw;
      this.values = values;
    }

    @Override
    public <T> T get(Arg<T> arg) {
      return values.get(arg);
    }

    @Override
    public Asker asker() {
      return raw.asker();
    }

    @Override
    public <P> P player(Class<P> type) throws ToolException {
      P player = raw.asker().unwrap(type);
      if (player == null) throw new ToolException("the asker isn't a player in the world");
      return player;
    }

    @Override
    public boolean isPublic() {
      return raw.isPublic();
    }

    @Override
    public boolean askx() {
      return raw.mode() == ToolCall.Mode.ASKX;
    }

    @Override
    public boolean canSee(UUID player) {
      return raw.canSee(player);
    }
  }

  /** A tool's answer as the plain values Allay writes for the model. */
  static final class Output {
    /** A player the asker mustn't learn about: out of lists, null elsewhere. */
    private static final Object HIDDEN = new Object();

    private Output() {}

    static Object of(Object value, ToolCall call) {
      Object out = convert(value, call, 0);
      return out == HIDDEN ? null : out;
    }

    private static Object convert(Object value, ToolCall call, int depth) {
      if (value == null || depth > 32) return null;
      if (value instanceof PlayerRef player) return call.hides(player.uuid()) ? HIDDEN : call.nameOf(player.uuid(), player.name());
      if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
      if (value instanceof Enum<?> constant) return constant.name().toLowerCase(Locale.ROOT);
      if (value instanceof UUID || value instanceof TemporalAccessor || value instanceof Character) return value.toString();
      if (value instanceof Record record) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var component : record.getClass().getRecordComponents()) {
          Object item;
          try {
            var accessor = component.getAccessor();
            accessor.setAccessible(true);
            item = accessor.invoke(record);
          } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("can't read " + component.getName() + " of " + record.getClass().getName(), error);
          }
          Object converted = convert(item, call, depth + 1);
          out.put(snake(component.getName()), converted == HIDDEN ? null : converted);
        }
        return out;
      }
      if (value instanceof Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, item) -> {
          Object converted = convert(item, call, depth + 1);
          out.put(String.valueOf(key), converted == HIDDEN ? null : converted);
        });
        return out;
      }
      if (value instanceof Iterable<?> items) {
        List<Object> out = new ArrayList<>();
        for (Object item : items) {
          Object converted = convert(item, call, depth + 1);
          if (converted != HIDDEN) out.add(converted);
        }
        return out;
      }
      if (value instanceof Object[] items) return convert(Arrays.asList(items), call, depth);
      return value.toString();
    }

    /** {@code nextBossInSeconds} as the model reads it: {@code next_boss_in_seconds}. */
    static String snake(String name) {
      StringBuilder out = new StringBuilder(name.length() + 4);
      for (int i = 0; i < name.length(); i++) {
        char c = name.charAt(i);
        if (Character.isUpperCase(c)) {
          if (i > 0) out.append('_');
          out.append(Character.toLowerCase(c));
        } else {
          out.append(c);
        }
      }
      return out.toString();
    }
  }
}
