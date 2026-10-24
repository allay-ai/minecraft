package chat.allay.expansion;

import java.util.List;

/**
 * What goes into a recipe's slot: an item by its id, any of a few, an item tag, or a bottle of a potion. Ids carry their
 * namespace when it isn't the game's: {@code sugar}, {@code minecraft:sugar}, and {@code redbull:taurine} all work.
 */
public final class Ingredient {
  private final String text;

  private Ingredient(String text) {
    this.text = text;
  }

  public static Ingredient item(String id) {
    return new Ingredient(id(id));
  }

  /** Any one of these items. */
  public static Ingredient anyOf(String... ids) {
    if (ids.length == 0) throw new IllegalArgumentException("name at least one item");
    StringBuilder out = new StringBuilder();
    for (String id : ids) {
      if (out.length() > 0) out.append(" | ");
      out.append(id(id));
    }
    return new Ingredient(out.toString());
  }

  /** Any item of a tag, like {@code minecraft:planks}. */
  public static Ingredient tag(String tag) {
    return new Ingredient("#" + id(tag.startsWith("#") ? tag.substring(1) : tag));
  }

  /** A drinkable bottle of a potion, like {@code water} or {@code awkward}: what a brewing stand takes below. */
  public static Ingredient potion(String potion) {
    return new Ingredient("potion " + id(potion));
  }

  /** The ingredient as Allay's recipes write it: {@code sugar}, {@code redbull:taurine}, {@code #planks}, {@code potion water}. */
  public String text() {
    return text;
  }

  static List<String> texts(List<Ingredient> ingredients) {
    return ingredients.stream().map(Ingredient::text).toList();
  }

  private static String id(String id) {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("an ingredient needs an id");
    String trimmed = id.trim().toLowerCase();
    return trimmed.startsWith("minecraft:") ? trimmed.substring("minecraft:".length()) : trimmed;
  }

  @Override
  public String toString() {
    return text;
  }
}
