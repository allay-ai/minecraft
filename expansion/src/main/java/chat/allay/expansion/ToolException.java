package chat.allay.expansion;

/** A reason the tool can't do what the model asked, in short English; the model reads it and tells the player. */
public class ToolException extends Exception {
  public ToolException(String message) {
    super(message);
  }
}
