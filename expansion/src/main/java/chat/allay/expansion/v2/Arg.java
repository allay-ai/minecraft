package chat.allay.expansion.v2;

import chat.allay.expansion.Schema;
import chat.allay.expansion.ToolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One argument the model passes to a tool, declared once on an {@link ArgGroup} and read with {@link Values#get}. Its
 * kind, bounds, and description go into the tool's JSON Schema, so the model knows what to send. Optional until
 * {@link #required()}.
 */
public final class Arg<T> {
  /** What the model sends and how Allay reads it. */
  public enum Kind { STRING, INTEGER, NUMBER, FLAG, CHOICE, STRINGS, GROUP, GROUPS }

  private final ArgGroup owner;
  private final String name;
  private final String description;
  private final Kind kind;
  private final Class<?> type;
  private final double min;
  private final double max;
  private final int limit;
  private final ArgGroup fields;
  private boolean required;
  private T fallback;

  Arg(ArgGroup owner, String name, String description, Kind kind, Class<?> type, double min, double max, int limit, ArgGroup fields) {
    if (name == null || !name.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("an argument's name is lower-case letters, digits, and underscores: " + name);
    this.owner = owner;
    this.name = name;
    this.description = description == null ? "" : description.trim();
    this.kind = kind;
    this.type = type;
    this.min = min;
    this.max = max;
    this.limit = limit;
    this.fields = fields;
  }

  /** The model must send it to every tool that takes it; {@link Tool#required} asks it for one. */
  public Arg<T> required() {
    owner.checkOpen();
    required = true;
    return this;
  }

  /** What a missing value reads as, instead of null, for every tool that takes it; {@link Tool#orElse} sets it for one. */
  public Arg<T> orElse(T fallback) {
    owner.checkOpen();
    this.fallback = fallback;
    return this;
  }

  public ArgGroup owner() {
    return owner;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public Kind kind() {
    return kind;
  }

  public boolean isRequired() {
    return required;
  }

  public T fallback() {
    return fallback;
  }

  /** The arguments of each object, for {@link Kind#GROUP} and {@link Kind#GROUPS}; null for the others. */
  public ArgGroup fields() {
    return fields;
  }

  /** Reads what the model sent; a value of the wrong kind refuses the call with a reason the model can act on. */
  T read(Object raw) throws ToolException {
    return read(raw, required, fallback);
  }

  /** Reads what the model sent with a tool's own say on whether it's required and what a missing value reads as. */
  @SuppressWarnings("unchecked")
  T read(Object raw, boolean required, Object fallback) throws ToolException {
    if (raw instanceof String text && text.isBlank()) raw = null;
    if (raw == null) {
      if (required) throw new ToolException(name + " is required");
      return (T) fallback;
    }
    return (T) switch (kind) {
      case STRING -> {
        if (!(raw instanceof String text)) throw new ToolException(name + " must be a string");
        String trimmed = text.trim();
        yield limit > 0 && trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
      }
      case INTEGER -> {
        long value = number(raw, "a whole number").longValue();
        yield (int) Math.max(min, Math.min(max, value));
      }
      case NUMBER -> Math.max(min, Math.min(max, number(raw, "a number").doubleValue()));
      case FLAG -> {
        if (raw instanceof Boolean flag) yield flag;
        if (raw instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) yield Boolean.parseBoolean(text);
        throw new ToolException(name + " must be true or false");
      }
      case CHOICE -> {
        String text = String.valueOf(raw).trim();
        yield Arrays.stream(type.getEnumConstants())
          .filter(it -> ((Enum<?>) it).name().equalsIgnoreCase(text))
          .findFirst()
          .orElseThrow(() -> new ToolException(name + " must be one of " + String.join(", ", choices())));
      }
      case STRINGS -> {
        List<String> out = new ArrayList<>();
        for (Object item : list(raw)) if (item instanceof String text && !text.isBlank() && out.size() < limit) out.add(text.trim());
        yield List.copyOf(out);
      }
      case GROUP -> fields.read(map(raw), name + ".");
      case GROUPS -> {
        List<Values> out = new ArrayList<>();
        List<?> items = list(raw);
        for (int i = 0; i < items.size() && out.size() < limit; i++) out.add(fields.read(map(items.get(i)), name + "[" + i + "]."));
        yield List.copyOf(out);
      }
    };
  }

  /** The values a {@link Kind#CHOICE} takes, in lower case. */
  public List<String> choices() {
    if (kind != Kind.CHOICE) return List.of();
    return Arrays.stream(type.getEnumConstants()).map(it -> ((Enum<?>) it).name().toLowerCase(Locale.ROOT)).toList();
  }

  void addTo(Schema schema) {
    addTo(schema, required);
  }

  void addTo(Schema schema, boolean required) {
    switch (kind) {
      case STRING -> {
        if (limit > 0) schema.string(name, description, required, limit);
        else schema.string(name, description, required);
      }
      case INTEGER -> schema.integer(name, description, required, (long) min, (long) max);
      case NUMBER -> schema.number(name, description, required, min, max);
      case FLAG -> schema.bool(name, description, required);
      case CHOICE -> schema.choice(name, description, required, choices().toArray(String[]::new));
      case STRINGS -> schema.strings(name, description, required, limit);
      case GROUP -> schema.object(name, description, required, fields.schema());
      case GROUPS -> schema.objects(name, description, required, limit, fields.schema());
    }
  }

  private Number number(Object raw, String what) throws ToolException {
    if (raw instanceof Number number) return number;
    if (raw instanceof String text) {
      try {
        return Double.parseDouble(text.trim());
      } catch (NumberFormatException ignored) {
        // Falls through to the refusal below.
      }
    }
    throw new ToolException(name + " must be " + what);
  }

  private List<?> list(Object raw) throws ToolException {
    if (raw instanceof List<?> list) return list;
    throw new ToolException(name + " must be a list");
  }

  private Map<?, ?> map(Object raw) throws ToolException {
    if (raw instanceof Map<?, ?> map) return map;
    throw new ToolException(name + " must be an object");
  }
}
