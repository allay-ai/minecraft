package chat.allay.expansion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool's arguments as a JSON Schema object, built step by step or given as JSON. The model fills it in, so describe
 * each argument: “The can's id, like redbull:original”.
 *
 * <pre>{@code
 * Schema.arguments()
 *   .string("can", "The can's id, like redbull:original", true)
 *   .integer("count", "How many, 1 to 64", false, 1, 64)
 *   .choice("form", "Where to look", false, "hand", "inventory")
 * }</pre>
 */
public final class Schema {
  private final Map<String, Object> properties = new LinkedHashMap<>();
  private final List<String> required = new ArrayList<>();
  private final String raw;

  private Schema(String raw) {
    this.raw = raw;
  }

  /** Arguments to add one by one; a name that isn't a Java or Kotlin keyword, so both call it plainly. */
  public static Schema arguments() {
    return new Schema(null);
  }

  /** No arguments at all. */
  public static Schema none() {
    return new Schema(null);
  }

  /**
   * A schema written as JSON: an object with {@code "type": "object"}, its {@code properties}, and {@code required}.
   * Allay checks it when the expansion registers; at most 4000 characters.
   */
  public static Schema json(String json) {
    if (json == null || json.isBlank()) throw new IllegalArgumentException("the schema is empty");
    return new Schema(json);
  }

  public Schema string(String name, String description, boolean required) {
    return property(name, describe("string", description), required);
  }

  public Schema integer(String name, String description, boolean required) {
    return property(name, describe("integer", description), required);
  }

  public Schema integer(String name, String description, boolean required, long min, long max) {
    Map<String, Object> property = describe("integer", description);
    property.put("minimum", min);
    property.put("maximum", max);
    return property(name, property, required);
  }

  public Schema number(String name, String description, boolean required) {
    return property(name, describe("number", description), required);
  }

  public Schema bool(String name, String description, boolean required) {
    return property(name, describe("boolean", description), required);
  }

  /** A string that is one of {@code values}. */
  public Schema choice(String name, String description, boolean required, String... values) {
    Map<String, Object> property = describe("string", description);
    property.put("enum", List.of(values));
    return property(name, property, required);
  }

  /** A list of strings, at most {@code maxItems} of them. */
  public Schema strings(String name, String description, boolean required, int maxItems) {
    Map<String, Object> property = describe("array", description);
    property.put("items", Map.of("type", "string"));
    property.put("maxItems", maxItems);
    return property(name, property, required);
  }

  private static Map<String, Object> describe(String type, String description) {
    Map<String, Object> property = new LinkedHashMap<>();
    property.put("type", type);
    if (description != null && !description.isBlank()) property.put("description", description);
    return property;
  }

  private Schema property(String name, Map<String, Object> property, boolean isRequired) {
    if (raw != null) throw new IllegalStateException("a schema given as JSON takes no more properties");
    if (name == null || !name.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("an argument's name is lower-case letters, digits, and underscores: " + name);
    properties.put(name, property);
    if (isRequired && !required.contains(name)) required.add(name);
    return this;
  }

  /** The schema as JSON. */
  public String toJson() {
    if (raw != null) return raw;
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) schema.put("required", required);
    return Json.write(schema);
  }

  @Override
  public String toString() {
    return toJson();
  }
}
