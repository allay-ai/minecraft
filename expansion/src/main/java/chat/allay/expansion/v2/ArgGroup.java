package chat.allay.expansion.v2;

import chat.allay.expansion.Schema;
import chat.allay.expansion.ToolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Arguments declared as fields. An {@link Expansion} is one, for its tools' arguments; a Kotlin {@code object} or a
 * class that extends it declares the fields of one object the model passes, for {@link #group} and {@link #groups}:
 *
 * <pre>{@code
 * object CanArgs : ArgGroup() {
 *     val can = string("can", "The can's id, like redbull:cherry").required()
 *     val count = integer("count", "How many, 1 to 64", 1, 64).orElse(1)
 * }
 * }</pre>
 */
public abstract class ArgGroup {
  private final List<Arg<?>> args = new ArrayList<>();
  private volatile boolean frozen;

  protected ArgGroup() {}

  /** Every argument it declared, in order. */
  public final List<Arg<?>> args() {
    return List.copyOf(args);
  }

  /** A string, trimmed; null when it's missing or blank. */
  protected final Arg<String> string(String name, String description) {
    return add(new Arg<>(this, name, description, Arg.Kind.STRING, String.class, 0, 0, 0, null));
  }

  /** A string of at most {@code maxLength} characters; a longer one is cut. */
  protected final Arg<String> string(String name, String description, int maxLength) {
    if (maxLength < 1) throw new IllegalArgumentException(name + " needs a maxLength of 1 or more");
    return add(new Arg<>(this, name, description, Arg.Kind.STRING, String.class, 0, 0, maxLength, null));
  }

  /** A whole number, kept between {@code min} and {@code max}. */
  protected final Arg<Integer> integer(String name, String description, int min, int max) {
    if (min > max) throw new IllegalArgumentException(name + " needs min ≤ max");
    return add(new Arg<>(this, name, description, Arg.Kind.INTEGER, Integer.class, min, max, 0, null));
  }

  /** A number, kept between {@code min} and {@code max}. */
  protected final Arg<Double> number(String name, String description, double min, double max) {
    if (min > max) throw new IllegalArgumentException(name + " needs min ≤ max");
    return add(new Arg<>(this, name, description, Arg.Kind.NUMBER, Double.class, min, max, 0, null));
  }

  /** True or false. */
  protected final Arg<Boolean> flag(String name, String description) {
    return add(new Arg<>(this, name, description, Arg.Kind.FLAG, Boolean.class, 0, 0, 0, null));
  }

  /** One of an enum's constants, which the model sees in lower case and may send in any case. */
  protected final <E extends Enum<E>> Arg<E> choice(String name, String description, Class<E> type) {
    if (type == null || type.getEnumConstants().length == 0) throw new IllegalArgumentException(name + " needs an enum with constants");
    return add(new Arg<>(this, name, description, Arg.Kind.CHOICE, type, 0, 0, 0, null));
  }

  /** A list of strings, at most {@code maxItems} of them; blank ones are left out. */
  protected final Arg<List<String>> strings(String name, String description, int maxItems) {
    if (maxItems < 1) throw new IllegalArgumentException(name + " needs maxItems of 1 or more");
    return add(new Arg<>(this, name, description, Arg.Kind.STRINGS, List.class, 0, 0, maxItems, null));
  }

  /** One object with the arguments {@code fields} declares. */
  protected final Arg<Values> group(String name, String description, ArgGroup fields) {
    return add(new Arg<>(this, name, description, Arg.Kind.GROUP, Values.class, 0, 0, 0, nested(name, fields)));
  }

  /** A list of objects, each with the arguments {@code fields} declares, at most {@code maxItems} of them. */
  protected final Arg<List<Values>> groups(String name, String description, int maxItems, ArgGroup fields) {
    if (maxItems < 1) throw new IllegalArgumentException(name + " needs maxItems of 1 or more");
    return add(new Arg<>(this, name, description, Arg.Kind.GROUPS, List.class, 0, 0, maxItems, nested(name, fields)));
  }

  private ArgGroup nested(String name, ArgGroup fields) {
    if (fields == null || fields == this || fields instanceof Expansion) throw new IllegalArgumentException(name + " needs an ArgGroup of its own fields");
    if (fields.args.isEmpty()) throw new IllegalArgumentException(name + "'s fields declare no argument");
    return fields;
  }

  private <T> Arg<T> add(Arg<T> arg) {
    checkOpen();
    if (args.stream().anyMatch(it -> it.name().equals(arg.name()))) throw new IllegalArgumentException("the argument " + arg.name() + " is declared twice");
    args.add(arg);
    return arg;
  }

  /** The arguments as a JSON Schema object. */
  Schema schema() {
    return schema(args);
  }

  static Schema schema(List<Arg<?>> args) {
    @SuppressWarnings("deprecation")
    Schema schema = Schema.arguments();
    args.forEach(arg -> arg.addTo(schema));
    return schema;
  }

  /** Reads every argument of this group from what the model sent, so a bad one refuses the call before it runs. */
  Values read(Map<?, ?> sent, String prefix) throws ToolException {
    return read(args, sent, prefix);
  }

  static Values read(List<Arg<?>> args, Map<?, ?> sent, String prefix) throws ToolException {
    Map<Arg<?>, Object> values = new HashMap<>();
    for (Arg<?> arg : args) {
      try {
        values.put(arg, arg.read(sent.get(arg.name())));
      } catch (ToolException error) {
        throw new ToolException(prefix + error.getMessage());
      }
    }
    return new Read(values);
  }

  void freeze() {
    frozen = true;
    args.stream().map(Arg::fields).filter(it -> it != null && it != this).forEach(ArgGroup::freeze);
  }

  void checkOpen() {
    if (frozen) throw new IllegalStateException("the expansion is registered: declare everything while it's built");
  }

  /** The values of one read. */
  private record Read(Map<Arg<?>, Object> values) implements Values {
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Arg<T> arg) {
      if (!values.containsKey(arg)) throw new IllegalStateException("the tool doesn't declare the argument " + (arg == null ? "null" : arg.name()));
      return (T) values.get(arg);
    }
  }
}
