package chat.allay.expansion.v2;

import chat.allay.expansion.Asker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One tool the model may call, declared as a field of an {@link Expansion}. The model sees it as
 * {@code x_<expansion id>_<name>}. It reads the game and changes nothing until {@link #own()} or {@link #operator()}.
 *
 * <p>Keep the call in a method of its own and hand it over by reference, so the declaration stays one line:
 *
 * <pre>{@code
 * val nearby = tool("nearby", "The bosses near the asker …", ::nearby).args(radius).summary { "${it.bosses.size}" }
 *
 * private fun nearby(call: Call): Nearby = …
 * }</pre>
 */
public final class Tool<T> {
  /**
   * Runs the call on the server thread and answers with a record or plain values, which Allay writes as JSON for the
   * model: records by their components in snake_case, maps, lists, strings, numbers, booleans, and null; enums in
   * lower case, {@link PlayerRef} as the name the model may use. Throw {@link chat.allay.expansion.ToolException} with
   * a short English reason the model can act on; any other exception reaches the model only as “failed”.
   */
  @FunctionalInterface
  public interface Handler<T> {
    T run(Call call) throws Exception;
  }

  /**
   * Reads the game on the server thread, then answers later from your own thread: a database's, the network's, or a
   * pool's. Allay waits off the server thread as long as for any tool, then cancels the stage. Completing it with a
   * {@link chat.allay.expansion.ToolException} refuses, as a thrown one does.
   */
  @FunctionalInterface
  public interface LaterHandler<T> {
    CompletionStage<T> run(Call call) throws Exception;
  }

  /** What a tool may do in the game. Allay offers it only where that's allowed. */
  public enum Access {
    /** Reads the game and changes nothing: offered to everyone, in {@code /askx} too. */
    READ,
    /** Changes only the asker's own things, like sorting their inventory: never in {@code /askx}. */
    OWN,
    /** Changes the game beyond the asker's things, or gives anything: operators only, checked on every call. */
    OPERATOR,
  }

  private final Expansion expansion;
  private final String name;
  private final String description;
  private final Handler<T> handler;
  private final LaterHandler<T> later;
  private final List<Arg<?>> args = new ArrayList<>();
  private final java.util.Set<Arg<?>> required = new java.util.HashSet<>();
  private final java.util.Map<Arg<?>, Object> fallbacks = new java.util.HashMap<>();
  private Access access = Access.READ;
  private String permission;
  private boolean inPublic;
  private Predicate<Asker> offeredTo;
  private BiFunction<? super T, Asker, String> summary;

  Tool(Expansion expansion, String name, String description, Handler<T> handler, LaterHandler<T> later) {
    if (name == null || !name.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("a tool's name is 1–32 lower-case letters, digits, or underscores: " + name);
    String text = description == null ? "" : description.trim();
    if (text.isEmpty() || text.length() > 1000) throw new IllegalArgumentException(name + " needs a description of 1–1000 characters");
    if (handler == null && later == null) throw new IllegalArgumentException(name + " needs a handler");
    this.expansion = expansion;
    this.name = name;
    this.description = text;
    this.handler = handler;
    this.later = later;
  }

  /** The arguments the model passes, declared on the expansion. */
  public Tool<T> args(Arg<?>... args) {
    expansion.checkOpen();
    for (Arg<?> arg : args) {
      if (arg == null || arg.owner() != expansion) throw new IllegalArgumentException("declare the arguments of " + name + " on " + expansion.id());
      if (this.args.contains(arg)) throw new IllegalArgumentException(name + " takes " + arg.name() + " twice");
      this.args.add(arg);
    }
    return this;
  }

  /**
   * The model must send these to this tool, though other tools may take them as optional:
   * {@code tool("spawn", "…", ::spawn).args(type, level).required(type)}.
   */
  public Tool<T> required(Arg<?>... args) {
    expansion.checkOpen();
    for (Arg<?> arg : args) {
      if (!this.args.contains(arg)) throw new IllegalArgumentException(name + " doesn't take " + (arg == null ? "null" : arg.name()) + ": list it in args first");
      required.add(arg);
    }
    return this;
  }

  /** What a missing value reads as in this tool, whatever the argument's own fallback: {@code .orElse(radius, 16)}. */
  public <A> Tool<T> orElse(Arg<A> arg, A fallback) {
    expansion.checkOpen();
    if (!args.contains(arg)) throw new IllegalArgumentException(name + " doesn't take " + (arg == null ? "null" : arg.name()) + ": list it in args first");
    fallbacks.put(arg, fallback);
    return this;
  }

  /** Whether the model must send {@code arg} to this tool. */
  public boolean requires(Arg<?> arg) {
    return arg.isRequired() || required.contains(arg);
  }

  /** What a missing {@code arg} reads as in this tool. */
  public Object fallbackOf(Arg<?> arg) {
    return fallbacks.containsKey(arg) ? fallbacks.get(arg) : arg.fallback();
  }

  /** It changes only the asker's own things: never offered in {@code /askx}. */
  public Tool<T> own() {
    expansion.checkOpen();
    access = Access.OWN;
    return this;
  }

  /** It changes the game beyond the asker's things, or gives anything: operators only. */
  public Tool<T> operator() {
    expansion.checkOpen();
    access = Access.OPERATOR;
    return this;
  }

  /** A permission the asker must also have, like {@code redbull.allay}. Where no permissions plugin or mod answers, everyone has it. */
  public Tool<T> permission(String permission) {
    expansion.checkOpen();
    this.permission = permission == null || permission.isBlank() ? null : permission.trim();
    return this;
  }

  /** It may run in a question everyone online reads ({@code /askall}): leave it off when the result is about the asker alone. */
  public Tool<T> inPublic() {
    expansion.checkOpen();
    inPublic = true;
    return this;
  }

  /**
   * Whether to offer it to this asker in this question, beyond its access and permission: only in your worlds, only
   * while they hold your item. Allay asks on the server thread when a question starts and before each call; an
   * exception counts as no.
   */
  public Tool<T> offeredTo(Predicate<Asker> offeredTo) {
    expansion.checkOpen();
    this.offeredTo = offeredTo;
    return this;
  }

  /** The line under the tool in the asker's chat, like “caffeine 64”, at most 60 characters. */
  public Tool<T> summary(Function<? super T, String> summary) {
    expansion.checkOpen();
    this.summary = summary == null ? null : (result, asker) -> summary.apply(result);
    return this;
  }

  /** The line under the tool in the asker's chat, in their language ({@link Asker#language()}). */
  public Tool<T> localSummary(BiFunction<? super T, Asker, String> summary) {
    expansion.checkOpen();
    this.summary = summary;
    return this;
  }

  public Expansion expansion() {
    return expansion;
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public List<Arg<?>> args() {
    return List.copyOf(args);
  }

  public Access access() {
    return access;
  }

  public String permission() {
    return permission;
  }

  public boolean isInPublic() {
    return inPublic;
  }

  Handler<T> handler() {
    return handler;
  }

  LaterHandler<T> later() {
    return later;
  }

  boolean offers(Asker asker) {
    return offeredTo == null || offeredTo.test(asker);
  }

  String summarize(Object result, Asker asker) {
    if (summary == null) return null;
    @SuppressWarnings("unchecked")
    T typed = (T) result;
    return summary.apply(typed, asker);
  }
}
