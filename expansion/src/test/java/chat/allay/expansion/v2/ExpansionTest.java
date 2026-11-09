package chat.allay.expansion.v2;

import static org.junit.jupiter.api.Assertions.*;

import chat.allay.expansion.AllayExpansion;
import chat.allay.expansion.AllayExpansions;
import chat.allay.expansion.AllayTool;
import chat.allay.expansion.Args;
import chat.allay.expansion.Asker;
import chat.allay.expansion.ToolCall;
import chat.allay.expansion.ToolException;
import chat.allay.expansion.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class ExpansionTest {
  static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  static final UUID GHOST = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  record Boss(String type, int level, PlayerRef killer, Form form) {}

  record Nearby(List<Boss> nearbyBosses, List<PlayerRef> hunters) {}

  enum Form { HAND, INVENTORY }

  static final class Can extends ArgGroup {
    final Arg<String> can = string("can", "The can's id").required();
    final Arg<Integer> count = integer("count", "How many", 1, 64).orElse(1);
  }

  static final class Bosses extends Expansion {
    static final Can CAN = new Can();
    final Arg<Integer> radius = integer("radius", "How far to look", 8, 128).orElse(64);
    final Arg<Form> form = choice("form", "Where to look", Form.class);
    final Arg<List<Values>> cans = groups("cans", "Several cans", 3, CAN);
    final Tool<Nearby> nearby = tool("nearby", "The bosses near the asker", this::nearby).args(radius, form).inPublic().summary(it -> it.nearbyBosses().size() + " bosses");
    final Tool<Map<String, Object>> give = tool("give", "Gives cans", this::give).args(cans).operator().permission("redbull.give");
    final Tool<Boss> hunt = toolLater("hunt", "The asker's hunt", call -> CompletableFuture.supplyAsync(() -> new Boss("zombie", 40, new PlayerRef(STEVE, "Steve"), null)));

    Bosses() {
      super("mobosses", "Mo’Bosses");
    }

    @Override
    public String prompt() {
      return "Bosses have levels.";
    }

    private Nearby nearby(Call call) {
      List<Boss> bosses = List.of(new Boss("zombie", call.get(radius), new PlayerRef(GHOST, "Ghost"), call.get(form)));
      return new Nearby(bosses, List.of(new PlayerRef(STEVE, "Steve"), new PlayerRef(GHOST, "Ghost")));
    }

    private Map<String, Object> give(Call call) {
      List<String> given = new ArrayList<>();
      for (Values can : call.get(cans)) given.add(can.get(CAN.can) + "×" + can.get(CAN.count));
      return Map.of("given", given);
    }
  }

  @AfterEach
  void unbind() {
    AllayExpansions.bind(null);
    new Bosses().unregister();
  }

  @Test
  void fieldsBecomeToolsWithSchemas() {
    List<AllayExpansion> seen = new ArrayList<>();
    AllayExpansions.bind(host(seen));
    new Bosses().register();
    AllayExpansion expansion = mobosses(seen);
    assertEquals("mobosses", expansion.id());
    assertEquals("Mo’Bosses", expansion.name());
    assertEquals("Bosses have levels.", expansion.promptExpansion());
    assertEquals(List.of("nearby", "give", "hunt"), expansion.tools().stream().map(AllayTool::name).toList());
    AllayTool nearby = expansion.tools().getFirst();
    assertTrue(nearby.inPublic());
    assertEquals(AllayTool.Access.READ, nearby.access());
    assertEquals(
      "{\"type\":\"object\",\"properties\":{\"radius\":{\"type\":\"integer\",\"description\":\"How far to look\",\"minimum\":8,\"maximum\":128},"
        + "\"form\":{\"type\":\"string\",\"description\":\"Where to look\",\"enum\":[\"hand\",\"inventory\"]}}}",
      nearby.parameters().toJson());
    AllayTool give = expansion.tools().get(1);
    assertEquals(AllayTool.Access.OPERATOR, give.access());
    assertEquals("redbull.give", give.permission());
    assertTrue(give.parameters().toJson().contains("\"required\":[\"can\"]"));
  }

  @Test
  void callsReadArgumentsAndAnswerInSnakeCaseWithoutHiddenPlayers() throws Exception {
    List<AllayExpansion> seen = new ArrayList<>();
    AllayExpansions.bind(host(seen));
    new Bosses().register();
    List<AllayTool> tools = mobosses(seen).tools();

    ToolResult result = tools.getFirst().run(call(Map.of("radius", 500L, "form", "Hand")));
    assertEquals("1 bosses", result.summary());
    assertEquals(Map.of("nearby_bosses", List.of(Map.of("type", "zombie", "level", 128, "form", "hand")), "hunters", List.of("Steve")), withoutNulls(result.data()));
    assertTrue(((Map<?, ?>) ((List<?>) result.data().get("nearby_bosses")).getFirst()).containsKey("killer"), "a hidden player stays as null in a record");

    ToolException wrong = assertThrows(ToolException.class, () -> tools.getFirst().run(call(Map.of("form", "pocket"))));
    assertEquals("form must be one of hand, inventory", wrong.getMessage());
    assertEquals(64, ((List<?>) tools.getFirst().run(call(Map.of())).data().get("nearby_bosses")).stream().map(it -> ((Map<?, ?>) it).get("level")).findFirst().orElseThrow());

    ToolResult given = tools.get(1).run(call(Map.of("cans", List.of(Map.of("can", "redbull:cherry", "count", 2L), Map.of("can", "redbull:zero"), Map.of("can", "a"), Map.of("can", "b")))));
    assertEquals(List.of("redbull:cherry×2", "redbull:zero×1", "a×1"), given.data().get("given"));
    assertEquals("cans[0].can is required", assertThrows(ToolException.class, () -> tools.get(1).run(call(Map.of("cans", List.of(Map.of("count", 1L)))))).getMessage());

    ToolResult later = tools.get(2).run(call(Map.of()));
    ToolResult done = later.pending().toCompletableFuture().get();
    assertEquals("Steve", done.data().get("killer"));
  }

  @Test
  void registeredExpansionsDontChangeAndBrokenOnesFailWhileBuilt() {
    Bosses bosses = new Bosses();
    bosses.register();
    assertThrows(IllegalStateException.class, () -> bosses.nearby.inPublic());
    assertThrows(IllegalStateException.class, () -> Bosses.CAN.count.required());
    assertThrows(IllegalArgumentException.class, () -> new Expansion("Bad Id") {});
    assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
      final Tool<String> a = tool("a", "", call -> "");
    });
    assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
      final Arg<String> q = string("q", "");
      final Arg<String> again = string("q", "");
    });
    Bosses other = new Bosses();
    assertThrows(IllegalArgumentException.class, () -> new Expansion("x") {
      final Tool<String> a = tool("a", "A tool", call -> "").args(other.radius);
    });
    assertEquals("next_boss_in_seconds", Adapter.Output.snake("nextBossInSeconds"));
  }

  /** Other tests' expansions stay registered, and a host gets them all when it binds. */
  private static AllayExpansion mobosses(List<AllayExpansion> seen) {
    return seen.stream().filter(it -> it.id().equals("mobosses")).reduce((first, second) -> second).orElseThrow(() -> new AssertionError(seen.stream().map(AllayExpansion::id).toList()));
  }

  private static Map<String, Object> withoutNulls(Map<String, Object> data) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    data.forEach((key, value) -> {
      if (value instanceof List<?> list) {
        out.put(key, list.stream().map(it -> it instanceof Map<?, ?> map ? withoutNulls(cast(map)) : it).toList());
      } else if (value != null) out.put(key, value);
    });
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static AllayExpansions.Host host(List<AllayExpansion> seen) {
    return new AllayExpansions.Host() {
      @Override
      public void register(AllayExpansion expansion) {
        seen.add(expansion);
      }

      @Override
      public void unregister(String id) {}

      @Override
      public String apiVersion() {
        return "2.0.0";
      }
    };
  }

  private static ToolCall call(Map<String, ?> args) {
    Set<UUID> hidden = Set.of(GHOST);
    return new ToolCall() {
      @Override
      public Asker asker() {
        return null;
      }

      @Override
      public Args args() {
        return new Args(args);
      }

      @Override
      public Mode mode() {
        return Mode.ASK;
      }

      @Override
      public boolean isPublic() {
        return false;
      }

      @Override
      public String nameOf(UUID player, String name) {
        return name;
      }

      @Override
      public boolean canSee(UUID player) {
        return !hidden.contains(player);
      }

      @Override
      public boolean hides(UUID player) {
        return hidden.contains(player);
      }
    };
  }
}
