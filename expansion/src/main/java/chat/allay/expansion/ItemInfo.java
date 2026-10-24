package chat.allay.expansion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An item the way Allay finds and explains it: its id, its names in the game's languages, what it is, and how it's
 * made. Hand Allay the items its registries can't show: a Paper plugin's items built on the game's own (a potion with
 * your model), and recipes the brewing stand or a plugin handles in code.
 *
 * <pre>{@code
 * ItemInfo.of("redbull:original")
 *   .name("en_us", "Red Bull Original").name("ru_ru", "Red Bull Original")
 *   .description("An energy drink: Speed II and Haste I for 3 minutes, 32 caffeine")
 *   .base("potion")
 *   .recipe(Recipe.shapeless(List.of(Ingredient.item("redbull:empty_can"), Ingredient.item("redbull:taurine"), Ingredient.item("sugar")), 1))
 * }</pre>
 */
public final class ItemInfo {
  private final String id;
  private final Map<String, String> names = new LinkedHashMap<>();
  private final List<Recipe> recipes = new ArrayList<>();
  private String description;
  private String base;

  private ItemInfo(String id) {
    this.id = id;
  }

  /** An item by its namespaced id, like {@code redbull:original}: your namespace, never the game's. */
  public static ItemInfo of(String id) {
    if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("an item's id is namespace:path in lower case: " + id);
    if (id.startsWith("minecraft:")) throw new IllegalArgumentException("describe your own items, not the game's: " + id);
    return new ItemInfo(id);
  }

  /** Its name in a game language, like {@code en_us} or {@code ru_ru}. English is the one every player can fall back on. */
  public ItemInfo name(String locale, String name) {
    if (locale == null || name == null || name.isBlank()) throw new IllegalArgumentException("a name needs a locale and a text");
    names.put(locale.toLowerCase(), name.trim());
    return this;
  }

  /** What it is and does, in English, for the model; at most 300 characters. */
  public ItemInfo description(String description) {
    this.description = description;
    return this;
  }

  /** The game's item underneath, like {@code potion}, for a Paper plugin's item built on one. */
  public ItemInfo base(String base) {
    this.base = base;
    return this;
  }

  public ItemInfo recipe(Recipe recipe) {
    recipes.add(recipe);
    return this;
  }

  public String id() {
    return id;
  }

  public Map<String, String> names() {
    return Map.copyOf(names);
  }

  public String description() {
    return description;
  }

  public String base() {
    return base;
  }

  public List<Recipe> recipes() {
    return List.copyOf(recipes);
  }
}
