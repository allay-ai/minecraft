package chat.allay.expansion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The arguments of a call, as the model sent them: strings, numbers ({@link Long} or {@link Double}), booleans, lists,
 * and maps. The model may leave out what the schema doesn't require, or send the wrong type: each getter answers null
 * then, so check what you need.
 */
public final class Args {
  private final Map<String, Object> values;

  public Args(Map<String, ?> values) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
  }

  public boolean has(String key) {
    return values.get(key) != null;
  }

  /** A string, trimmed; null when it's missing, blank, or not a string. */
  public String string(String key) {
    Object value = values.get(key);
    if (!(value instanceof String text)) return null;
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** A whole number; a number with a fraction is cut. Null when it's missing or not a number. */
  public Long integer(String key) {
    Object value = values.get(key);
    return value instanceof Number number ? number.longValue() : null;
  }

  public Double number(String key) {
    Object value = values.get(key);
    return value instanceof Number number ? number.doubleValue() : null;
  }

  public Boolean bool(String key) {
    Object value = values.get(key);
    return value instanceof Boolean flag ? flag : null;
  }

  @SuppressWarnings("unchecked")
  public List<Object> list(String key) {
    Object value = values.get(key);
    return value instanceof List<?> list ? (List<Object>) list : null;
  }

  /** A nested object as its own arguments. */
  public Args object(String key) {
    Object value = values.get(key);
    if (!(value instanceof Map<?, ?> map)) return null;
    Map<String, Object> nested = new LinkedHashMap<>();
    map.forEach((k, v) -> nested.put(String.valueOf(k), v));
    return new Args(nested);
  }

  /** A list of objects, each as its own arguments, as {@link Schema#objects} asks for; entries that aren't objects are left out. Since API 1.1. */
  public List<Args> objects(String key) {
    List<Object> list = list(key);
    if (list == null) return null;
    List<Args> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        Map<String, Object> nested = new LinkedHashMap<>();
        map.forEach((k, v) -> nested.put(String.valueOf(k), v));
        out.add(new Args(nested));
      }
    }
    return out;
  }

  /** Every argument as it came. */
  public Map<String, Object> asMap() {
    return values;
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
