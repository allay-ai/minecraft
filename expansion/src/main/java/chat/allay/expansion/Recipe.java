package chat.allay.expansion;

import java.util.List;
import java.util.Map;

/**
 * One way to make an {@link ItemInfo}: at a crafting table, in a furnace, on a stonecutter, or in a brewing stand. The
 * item it belongs to is what it makes.
 */
public final class Recipe {
  /** The kinds, named as Allay's recipes name them. */
  public enum Type {
    CRAFTING_SHAPED("crafting_shaped"),
    CRAFTING_SHAPELESS("crafting_shapeless"),
    FURNACE("furnace"),
    BLASTING("blasting"),
    SMOKING("smoking"),
    CAMPFIRE("campfire"),
    STONECUTTING("stonecutting"),
    BREWING("brewing");

    private final String wire;

    Type(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  private final Type type;
  private final int count;
  private final List<String> shape;
  private final Map<Character, Ingredient> key;
  private final List<Ingredient> ingredients;
  private final double seconds;
  private final double experience;

  private Recipe(Type type, int count, List<String> shape, Map<Character, Ingredient> key, List<Ingredient> ingredients, double seconds, double experience) {
    if (count < 1 || count > 64) throw new IllegalArgumentException("a recipe makes 1 to 64");
    this.type = type;
    this.count = count;
    this.shape = shape;
    this.key = key;
    this.ingredients = ingredients;
    this.seconds = seconds;
    this.experience = experience;
  }

  /**
   * A grid recipe: up to three rows of up to three characters, each standing for an ingredient in {@code key}, a space
   * for an empty slot.
   */
  public static Recipe shaped(List<String> rows, Map<Character, Ingredient> key, int count) {
    if (rows.isEmpty() || rows.size() > 3 || rows.stream().anyMatch(row -> row.isEmpty() || row.length() > 3)) {
      throw new IllegalArgumentException("a shaped recipe has 1 to 3 rows of 1 to 3 slots");
    }
    for (String row : rows) {
      for (char c : row.toCharArray()) {
        if (c != ' ' && !key.containsKey(c)) throw new IllegalArgumentException("the shape uses '" + c + "', which the key doesn't name");
      }
    }
    return new Recipe(Type.CRAFTING_SHAPED, count, List.copyOf(rows), Map.copyOf(key), List.of(), 0, 0);
  }

  public static Recipe shapeless(List<Ingredient> ingredients, int count) {
    if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException("a shapeless recipe takes 1 to 9 ingredients");
    return new Recipe(Type.CRAFTING_SHAPELESS, count, List.of(), Map.of(), List.copyOf(ingredients), 0, 0);
  }

  /** A furnace, blast furnace, smoker, or campfire recipe. */
  public static Recipe cooking(Type type, Ingredient input, double seconds, double experience) {
    if (type != Type.FURNACE && type != Type.BLASTING && type != Type.SMOKING && type != Type.CAMPFIRE) {
      throw new IllegalArgumentException("cooking is furnace, blasting, smoking, or campfire");
    }
    return new Recipe(type, 1, List.of(), Map.of(), List.of(input), seconds, experience);
  }

  public static Recipe stonecutting(Ingredient input, int count) {
    return new Recipe(Type.STONECUTTING, count, List.of(), Map.of(), List.of(input), 0, 0);
  }

  /**
   * A brewing stand's step: {@code bottle} below, like {@link Ingredient#potion(String) potion("water")} or another
   * item's bottle, and {@code reagent} on top. Blaze powder fuels it, as always.
   */
  public static Recipe brewing(Ingredient bottle, Ingredient reagent) {
    return new Recipe(Type.BREWING, 1, List.of(), Map.of(), List.of(bottle, reagent), 0, 0);
  }

  public Type type() {
    return type;
  }

  public int count() {
    return count;
  }

  /** The rows of a shaped recipe; empty for the others. */
  public List<String> shape() {
    return shape;
  }

  public Map<Character, Ingredient> key() {
    return key;
  }

  /**
   * A shapeless recipe's ingredients; a cooking or stonecutting recipe's one input; a brew's bottle and then its reagent.
   */
  public List<Ingredient> ingredients() {
    return ingredients;
  }

  public double seconds() {
    return seconds;
  }

  public double experience() {
    return experience;
  }
}
