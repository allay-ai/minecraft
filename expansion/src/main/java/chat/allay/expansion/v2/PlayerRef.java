package chat.allay.expansion.v2;

import java.util.UUID;

/**
 * Another player in a tool's result. Allay writes it as the name the model may use: the player's own once they agreed
 * to Allay's notice on this server, else this run's “Player N”. A player the asker mustn't learn about, like a vanished
 * one, is left out of lists and becomes null elsewhere. Name other players only through it.
 */
public record PlayerRef(UUID uuid, String name) {
  public PlayerRef {
    if (uuid == null) throw new IllegalArgumentException("A player needs a UUID");
    if (name == null) name = "";
  }
}
