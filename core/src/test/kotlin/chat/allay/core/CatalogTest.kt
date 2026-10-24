package chat.allay.core

import chat.allay.core.tools.Catalog
import chat.allay.core.tools.CatalogCache
import chat.allay.core.tools.asked
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import chat.allay.core.tools.Catalog.Entry
import chat.allay.core.tools.Catalog.Kind
import chat.allay.core.tools.GameNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogTest {
  private val forms = listOf("potion", "splash_potion", "lingering_potion")

  /** The game's names as a server and a download give them: English on hand, Russian fetched. */
  private val names = GameNames(Files.createTempDirectory("names").toFile().also { it.resolve("cache/lang/26.3").mkdirs(); it.resolve("cache/lang/26.3/ru_ru.json").writeText("{}") }, "26.3", Logger.getAnonymousLogger()) { locale ->
    when (locale) {
      "en_us" -> mapOf(
        "item.minecraft.potion.effect.infested" to "Potion of Infestation",
        "item.minecraft.splash_potion.effect.infested" to "Splash Potion of Infestation",
        "item.minecraft.potion.effect.swiftness" to "Potion of Swiftness",
        "item.minecraft.stone" to "Stone",
        "item.minecraft.diamond_sword" to "Diamond Sword",
        "item.redbull.original" to "Red Bull Original",
        "item.mymod.copper_wrench" to "Copper Wrench",
      )
      "ru_ru" -> mapOf(
        "item.minecraft.potion.effect.infested" to "Зелье заражения",
        "item.minecraft.potion.effect.swiftness" to "Зелье стремительности",
        "item.minecraft.stone" to "Камень",
        "item.minecraft.diamond_sword" to "Алмазный меч",
      )
      else -> emptyMap()
    }
  }

  private val catalog = Catalog(
    listOf(
      Entry("stone", Kind.ITEM, listOf("item.minecraft.stone"), made = setOf("smelting")),
      Entry("diamond_sword", Kind.ITEM, listOf("item.minecraft.diamond_sword"), made = setOf("crafting")),
      Entry("infested", Kind.POTION, forms.map { "item.minecraft.$it.effect.infested" }, made = setOf("brewing")),
      Entry("swiftness", Kind.POTION, forms.map { "item.minecraft.$it.effect.swiftness" }, made = setOf("brewing")),
      Entry("long_swiftness", Kind.POTION, forms.map { "item.minecraft.$it.effect.swiftness" }, made = setOf("brewing")),
      Entry("mymod:copper_wrench", Kind.ITEM, listOf("item.mymod.copper_wrench"), source = "My Mod"),
      Entry("redbull:original", Kind.CUSTOM, listOf("item.redbull.original"), source = "RedBull", base = "potion", made = setOf("crafting")),
      Entry("mypack:ruby", Kind.CUSTOM, names = mapOf("en_us" to "Ruby"), source = "data pack mypack", base = "emerald"),
    ),
    names,
  )

  @Test fun `the potion of infestation is found by its name, not its id`() {
    assertEquals("infested", catalog.resolve("Potion of Infestation", "en_us")?.id)
    assertEquals("infested", catalog.resolve("infestation", "en_us")?.id)
    assertEquals("infested", catalog.resolve("зелье заражения", "ru_ru")?.id)
    assertEquals("infested", catalog.resolve("Зелье Заражения", null)?.id)
    assertEquals("infested", catalog.resolve("splash potion of infestation", "en_us")?.id)
  }

  @Test fun `names in the reader's language come first`() {
    val hit = catalog.search("алмазный меч", "ru_ru").first()
    assertEquals("diamond_sword", hit.entry.id)
    assertEquals("Алмазный меч", hit.name)
    assertEquals("Diamond Sword", hit.english)
    assertEquals("Diamond Sword", catalog.name(hit.entry, "de_de"))
  }

  @Test fun `mods', plugins', and data packs' things are found by name and id`() {
    assertEquals("mymod:copper_wrench", catalog.resolve("copper wrench", "en_us")?.id)
    assertEquals("mymod:copper_wrench", catalog.resolve("mymod:copper_wrench", null)?.id)
    assertEquals("redbull:original", catalog.resolve("Red Bull Original", "ru_ru")?.id)
    assertEquals("redbull:original", catalog.resolve("redbull original", null)?.id)
    assertEquals("mypack:ruby", catalog.resolve("ruby", "en_us")?.id)
  }

  @Test fun `a near spelling finds the thing, but resolve wants a clear winner`() {
    assertEquals("swiftness", catalog.search("swiftnes", "en_us").first().entry.id)
    // Swiftness and its long form share a name: only the id tells them apart.
    assertNull(catalog.resolve("Potion of Swiftness", "en_us"))
    assertEquals("long_swiftness", catalog.resolve("long_swiftness", null)?.id)
    assertNull(catalog.resolve("netherite chestplate", "en_us"))
  }

  @Test fun `the answer says where things come from and never that they don't exist`() {
    val answer = catalog.answer("red bull", "ru_ru", 5)
    val first = (answer["matches"] as JsonArray).first().jsonObject
    assertEquals("redbull:original", first["id"]!!.jsonPrimitive.content)
    assertEquals("RedBull", first["source"]!!.jsonPrimitive.content)
    assertEquals("potion", first["base"]!!.jsonPrimitive.content)
    val none = catalog.answer("unobtainium", "en_us", 5)
    assertTrue((none["matches"] as JsonArray).isEmpty())
    assertTrue("doesn't prove" in none["note"]!!.jsonPrimitive.content)
    assertTrue("close: Ruby (mypack:ruby)" in catalog.missing("rubi", "en_us"))
  }

  @Test fun `several names are answered at once, each in its place`() {
    val results = catalog.answers(listOf("алмазный меч", "infestation", "unobtainium"), "ru_ru", 3)["results"] as JsonArray
    assertEquals(listOf("алмазный меч", "infestation", "unobtainium"), results.map { it.jsonObject["query"]!!.jsonPrimitive.content })
    assertEquals("diamond_sword", (results[0].jsonObject["matches"] as JsonArray).first().jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals("infested", (results[1].jsonObject["matches"] as JsonArray).first().jsonObject["id"]!!.jsonPrimitive.content)
    assertTrue((results[2].jsonObject["matches"] as JsonArray).isEmpty())
  }

  @Test fun `a lookup takes a list or one name, and no more than it can`() {
    val list = kotlinx.serialization.json.buildJsonObject { put("queries", JsonArray(listOf(" stone ", "stone", "", "dirt").map(::JsonPrimitive))) }
    assertEquals(listOf("stone", "dirt"), list.asked("queries", "query", 8))
    assertEquals(listOf("stone"), kotlinx.serialization.json.buildJsonObject { put("query", "stone") }.asked("queries", "query", 8))
    assertNull(kotlinx.serialization.json.buildJsonObject {}.asked("queries", "query", 8))
    assertEquals("ask for at most 1 at once", kotlin.runCatching { list.asked("queries", "query", 1) }.exceptionOrNull()?.message)
  }

  @Test fun `a thousand entries are searched quickly`() {
    val many = Catalog((1..3000).map { Entry("thing_$it", Kind.ITEM, names = mapOf("en_us" to "Some Thing Number $it", "ru_ru" to "Штука номер $it")) }, names)
    many.search("warmup", "ru_ru")
    val started = System.nanoTime()
    repeat(20) { many.search("штука номер 1500", "ru_ru"); many.search("infestaton", "en_us") }
    val each = (System.nanoTime() - started) / 40 / 1_000_000.0
    assertTrue(each < 50, "a search took $each ms")
    assertEquals("thing_1500", many.resolve("штука номер 1500", "ru_ru")?.id)
  }

  @Test fun `the cache keeps one catalog while its key stays`() {
    var now = 0L
    var built = 0
    val cache = CatalogCache(maxAgeMillis = 1000) { now }
    val build = { built++; catalog }
    cache.get(listOf(1), build); cache.get(listOf(1), build)
    assertEquals(1, built)
    cache.get(listOf(2), build)
    assertEquals(2, built)
    now = 1000
    cache.get(listOf(2), build)
    assertEquals(3, built)
    cache.clear(); cache.get(listOf(2), build)
    assertEquals(4, built)
  }

  @Test fun `folding ignores case, color codes, ё, and the game's namespace`() {
    assertEquals("еж", Catalog.fold("§aЁЖ"))
    assertEquals("diamond sword", Catalog.fold("minecraft:Diamond_Sword"))
    assertEquals(Catalog.EXACT, Catalog.score("stone", "stone"))
    assertEquals(0, Catalog.score("stone", "dirt"))
  }
}

class CatalogMergeTest {
  @Test fun `an expansion's item joins the registry's entry of the same id`() {
    val merged = Catalog.merge(
      listOf(
        Entry("redbull:crude_taurine", Kind.ITEM, listOf("item.redbull.crude_taurine"), source = "Red Bull"),
        Entry("awkward", Kind.POTION, source = "minecraft"),
      ),
      listOf(
        Entry("redbull:crude_taurine", Kind.CUSTOM, names = mapOf("en_us" to "Crude Taurine"), source = "Red Bull", description = "Brewed from bile", made = setOf("brewing")),
        Entry("redbull:bull_bile", Kind.CUSTOM, names = mapOf("en_us" to "Bull Bile"), source = "Red Bull"),
        Entry("awkward", Kind.CUSTOM, names = mapOf("en_us" to "Not the potion"), source = "x"),
      ),
    )
    assertEquals(4, merged.size)
    val crude = merged.first { it.id == "redbull:crude_taurine" }
    assertEquals(Kind.ITEM, crude.kind)
    assertEquals(listOf("item.redbull.crude_taurine"), crude.keys)
    assertEquals("Brewed from bile", crude.description)
    assertEquals(setOf("brewing"), crude.made)
    // A potion's id is its own: an item that shares it stays apart.
    assertEquals(2, merged.count { it.id == "awkward" })
  }
}
