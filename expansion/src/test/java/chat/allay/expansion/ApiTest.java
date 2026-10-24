package chat.allay.expansion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiTest {
  @Test
  void versionsMatchByMajorAndMinor() {
    assertTrue(AllayApi.satisfies("1.0.0", "1.0.0"));
    assertTrue(AllayApi.satisfies("1.3.2", "1.1.0"));
    assertTrue(AllayApi.satisfies("1.0.5", "1.0.9"));
    assertFalse(AllayApi.satisfies("1.0.0", "1.1.0"));
    assertFalse(AllayApi.satisfies("2.0.0", "1.0.0"));
    assertFalse(AllayApi.satisfies("1.0.0", "garbage"));
    assertFalse(AllayApi.satisfies("1.0.0", null));
  }

  @Test
  void schemaWritesJson() {
    String json = Schema.arguments()
      .string("can", "The can's id, like \"redbull:original\"", true)
      .integer("count", "How many", false, 1, 64)
      .choice("form", null, false, "hand", "inventory")
      .toJson();
    assertEquals(
      "{\"type\":\"object\",\"properties\":{\"can\":{\"type\":\"string\",\"description\":\"The can's id, like \\\"redbull:original\\\"\"},"
        + "\"count\":{\"type\":\"integer\",\"description\":\"How many\",\"minimum\":1,\"maximum\":64},"
        + "\"form\":{\"type\":\"string\",\"enum\":[\"hand\",\"inventory\"]}},\"required\":[\"can\"]}",
      json);
    assertEquals("{\"type\":\"object\",\"properties\":{}}", Schema.none().toJson());
    assertThrows(IllegalArgumentException.class, () -> Schema.arguments().string("Bad Name", "", false));
  }

  @Test
  void schemaNestsObjectsAndBounds() {
    String json = Schema.arguments()
      .string("name", null, true, 32)
      .number("strength", null, false, 0.5, 2)
      .object("at", null, false, Schema.arguments().integer("x", null, true).integer("z", null, true))
      .objects("cans", "Several cans", false, 8, Schema.arguments().string("can", null, true))
      .toJson();
    assertEquals(
      "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"maxLength\":32},"
        + "\"strength\":{\"type\":\"number\",\"minimum\":0.5,\"maximum\":2.0},"
        + "\"at\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"}},\"required\":[\"x\",\"z\"]},"
        + "\"cans\":{\"type\":\"array\",\"description\":\"Several cans\",\"items\":{\"type\":\"object\",\"properties\":{\"can\":{\"type\":\"string\"}},\"required\":[\"can\"]},\"maxItems\":8}},"
        + "\"required\":[\"name\"]}",
      json);
    assertThrows(IllegalArgumentException.class, () -> Schema.arguments().object("at", null, false, Schema.json("{\"type\":\"object\"}")));
    assertThrows(IllegalArgumentException.class, () -> Schema.arguments().objects("cans", null, false, 4, Schema.none()));
    Args args = new Args(Map.of("cans", List.of(Map.of("can", "redbull:original"), "junk")));
    assertEquals(1, args.objects("cans").size());
    assertEquals("redbull:original", args.objects("cans").get(0).string("can"));
  }

  @Test
  void laterResultsCarryTheirStage() {
    java.util.concurrent.CompletableFuture<ToolResult> stage = new java.util.concurrent.CompletableFuture<>();
    ToolResult later = ToolResult.later(stage);
    assertSame(stage, later.pending());
    assertNull(ToolResult.text("now").pending());
    assertThrows(IllegalStateException.class, () -> later.summary("no"));
  }

  @Test
  void itemsCheckTheirIds() {
    assertThrows(IllegalArgumentException.class, () -> ItemInfo.of("original"));
    assertThrows(IllegalArgumentException.class, () -> ItemInfo.of("minecraft:sugar"));
    ItemInfo can = ItemInfo.of("redbull:original").name("EN_US", "Red Bull Original")
      .recipe(Recipe.shapeless(List.of(Ingredient.item("minecraft:Sugar"), Ingredient.item("redbull:taurine")), 1))
      .recipe(Recipe.brewing(Ingredient.potion("water"), Ingredient.tag("#minecraft:planks")));
    assertEquals(Map.of("en_us", "Red Bull Original"), can.names());
    assertEquals(List.of("sugar", "redbull:taurine"), Ingredient.texts(can.recipes().get(0).ingredients()));
    assertEquals(List.of("potion water", "#planks"), Ingredient.texts(can.recipes().get(1).ingredients()));
    assertThrows(IllegalArgumentException.class, () -> Recipe.shaped(List.of("ab"), Map.of('a', Ingredient.item("sugar")), 1));
  }

  @Test
  void argsGiveNullForWrongTypes() {
    Args args = new Args(Map.of("name", "  can ", "count", 3L, "half", 2.5, "on", true, "nested", Map.of("x", 1L)));
    assertEquals("can", args.string("name"));
    assertNull(args.string("count"));
    assertEquals(3L, args.integer("count"));
    assertEquals(2L, args.integer("half"));
    assertEquals(true, args.bool("on"));
    assertEquals(1L, args.object("nested").integer("x"));
    assertNull(args.list("name"));
  }

  @Test
  void expansionsWaitForAllay() {
    AllayExpansion one = expansion("one");
    AllayExpansions.register(one);
    AllayExpansions.register(expansion("two"));
    AllayExpansions.unregister("two");
    List<String> got = new ArrayList<>();
    AllayExpansions.bind(new AllayExpansions.Host() {
      @Override public void register(AllayExpansion expansion) { got.add(expansion.id()); }
      @Override public void unregister(String id) { got.remove(id); }
      @Override public String apiVersion() { return "1.4.0"; }
    });
    assertEquals(List.of("one"), got);
    assertEquals("1.4.0", AllayExpansions.apiVersion());
    AllayExpansions.register(expansion("three"));
    assertEquals(List.of("one", "three"), got);
    AllayExpansions.bind(null);
    assertFalse(AllayExpansions.available());
    // The next Allay, like a second singleplayer world's, gets every expansion still registered.
    got.clear();
    AllayExpansions.bind(new AllayExpansions.Host() {
      @Override public void register(AllayExpansion expansion) { got.add(expansion.id()); }
      @Override public void unregister(String id) {}
      @Override public String apiVersion() { return "1.0.0"; }
    });
    assertEquals(List.of("one", "three"), got);
    AllayExpansions.bind(null);
  }

  private static AllayExpansion expansion(String id) {
    return new AllayExpansion() {
      @Override public String id() { return id; }
      @Override public String requiresApi() { return AllayApi.VERSION; }
    };
  }
}
