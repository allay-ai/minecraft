package chat.allay.expansion.v2;

/** Arguments as the model filled them in: a tool's own, or one object of an {@link ArgGroup}. */
public interface Values {
  /**
   * An argument, read as its declaration says: {@code call[radius]} in Kotlin, {@code call.get(radius)} in Java. A
   * missing optional argument reads as its fallback, or null without one.
   */
  <T> T get(Arg<T> arg);
}
