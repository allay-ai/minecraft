package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Texts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items as GameItems
import net.minecraft.world.item.MapItem
import net.minecraft.world.item.component.FireworkExplosion
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.level.Level

/**
 * The game's item as the core's [ItemData]; null for nothing, air, or an empty stack. It reads what Bukkit's item meta
 * reads, so a tool's result is the same on both: the name, lore, food, and modifiers a stack carries on top of its
 * type's. [full] adds what only a close look at one item needs ([Items.detail]); [level] lets a map tell where it is.
 * Must run on the server thread.
 */
fun ItemStack?.data(full: Boolean = false, level: Level? = null): ItemData? {
  if (this == null || isEmpty || count <= 0) return null
  val potion = get(DataComponents.POTION_CONTENTS)
  val stew = get(DataComponents.SUSPICIOUS_STEW_EFFECTS)
  return ItemData(
    id = Worlds.key(BuiltInRegistries.ITEM.getKey(item)),
    count = count,
    name = own(DataComponents.CUSTOM_NAME)?.let(Texts::legacyOf),
    enchantments = enchantments(get(DataComponents.ENCHANTMENTS)),
    storedEnchantments = enchantments(get(DataComponents.STORED_ENCHANTMENTS)),
    maxDamage = item.components().get(DataComponents.MAX_DAMAGE) ?: 0,
    damage = get(DataComponents.DAMAGE) ?: 0,
    potion = when {
      potion != null && item in POTIONS ->
        ItemData.Potion(potion.potion().map { Worlds.key(it) }.orElse(null), potion.potion().map { it.value().effects.map(::effect) }.orElse(emptyList()), potion.customEffects().map(::effect))
      stew != null && item == GameItems.SUSPICIOUS_STEW -> ItemData.Potion(null, emptyList(), stew.effects().map { effect(it.createEffectInstance()) })
      else -> null
    },
    trim = get(DataComponents.TRIM)?.let { ItemData.Trim(Worlds.key(it.pattern()), Worlds.key(it.material())) },
    contents = contents(this)?.map { it.data() },
    bundle = has(DataComponents.BUNDLE_CONTENTS),
    food = own(DataComponents.FOOD)?.let { ItemData.Food(it.nutrition(), it.saturation().toDouble()) },
    details = if (full) details(this, level) else null,
  )
}

/** A component the stack carries on top of its type's, as Bukkit's item meta sees it; null when it has the type's. */
fun <T : Any> ItemStack.own(type: DataComponentType<T>): T? = componentsPatch.split().added().get(type)

/** The item as tools list it, or null for an empty slot. */
fun ItemStack?.compact(slot: Int? = null): JsonObject? = data()?.compact(slot)

fun Iterable<ItemStack?>.data(): List<ItemData?> = map { it.data() }

/**
 * Everything about one item, for `inspect_item`; the map in it knows whether [player] stands on it. Without a player
 * (the console), a map is read from the [server]'s overworld, where the game keeps every map, as Bukkit's `MapMeta` is.
 */
fun detail(item: ItemStack, player: ServerPlayer?, server: MinecraftServer? = player?.level()?.server): JsonObject =
  Items.detail(item.data(full = true, level = player?.level() ?: server?.overworld())!!, player?.let { Spot(Worlds.name(it.level()), it.blockX, it.blockZ) })

/** What a shulker box or bundle holds, in slot order with empty slots as null; null for any other item. */
fun contents(item: ItemStack): List<ItemStack?>? = when {
  item.has(DataComponents.BUNDLE_CONTENTS) -> item.get(DataComponents.BUNDLE_CONTENTS)!!.items().map { it.create() }
  item.`is`(ItemTags.SHULKER_BOXES) -> item.get(DataComponents.CONTAINER)?.itemCopies()?.toList()?.map { stack -> stack.takeUnless { it.isEmpty } }.orEmpty()
  else -> null
}

/** What Bukkit's `PotionMeta` covers. */
private val POTIONS = setOf(GameItems.POTION, GameItems.SPLASH_POTION, GameItems.LINGERING_POTION, GameItems.TIPPED_ARROW)

private fun enchantments(enchantments: net.minecraft.world.item.enchantment.ItemEnchantments?): Map<String, Int> =
  enchantments?.entrySet()?.associate { Worlds.key(it.key) to it.intValue }.orEmpty()

private fun effect(effect: MobEffectInstance) =
  ItemData.Effect(Worlds.key(effect.effect), effect.amplifier, effect.duration, effect.isInfiniteDuration, effect.effect.value().isInstantaneous)

private fun modifiers(entries: List<ItemAttributeModifiers.Entry>) = entries.map { entry ->
  Mechanics.Modifier(Worlds.key(entry.attribute()), entry.modifier().amount(), operation(entry.modifier().operation()), slot(entry.slot()))
}

private fun operation(operation: AttributeModifier.Operation) = operation.serializedName

/** The slot group as Bukkit names it: `mainhand`, `armor`, `any`. */
private fun slot(group: EquipmentSlotGroup) = group.serializedName

/** The shape as Bukkit names it, which [ItemData.Explosion] expects. */
private fun shape(shape: FireworkExplosion.Shape) = when (shape) {
  FireworkExplosion.Shape.SMALL_BALL -> "BALL"
  FireworkExplosion.Shape.LARGE_BALL -> "BALL_LARGE"
  else -> shape.name
}

private fun explosion(effect: FireworkExplosion) = ItemData.Explosion(
  type = shape(effect.shape()),
  colors = effect.colors().toList(),
  fade = effect.fadeColors().toList(),
  trail = effect.hasTrail(),
  flicker = effect.hasTwinkle(),
)

private fun details(item: ItemStack, level: Level?): ItemData.Details {
  val book = item.get(DataComponents.WRITTEN_BOOK_CONTENT)
  return ItemData.Details(
    lore = item.own(DataComponents.LORE)?.lines()?.map(Texts::legacyOf).orEmpty(),
    unbreakable = item.has(DataComponents.UNBREAKABLE),
    modifiers = modifiers(item.own(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers().orEmpty()),
    defaultModifiers = modifiers(item.item.components().get(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers().orEmpty().distinctBy { it.attribute() to it.modifier().id() }),
    dyeColor = item.get(DataComponents.DYED_COLOR)?.rgb(),
    repairCost = item.get(DataComponents.REPAIR_COST)?.takeIf { it > 0 },
    book = when {
      item.item == GameItems.WRITTEN_BOOK && book != null -> ItemData.Book.Written(
        title = book.title().raw().takeIf { it.isNotEmpty() },
        author = book.author().takeIf { it.isNotEmpty() },
        generation = GENERATIONS.getOrElse(book.generation()) { "tattered" },
        pages = book.pages().size,
      )
      item.item == GameItems.WRITTEN_BOOK -> ItemData.Book.Written(null, null, "original", 0)
      item.item == GameItems.WRITABLE_BOOK -> ItemData.Book.Writable(item.get(DataComponents.WRITABLE_BOOK_CONTENT)?.pages()?.size ?: 0)
      else -> null
    },
    firework = when {
      // Bukkit's FireworkMeta reads only the rocket's own components: a bare rocket flies for 0.
      item.item == GameItems.FIREWORK_ROCKET -> item.own(DataComponents.FIREWORKS).let { ItemData.Firework(it?.flightDuration() ?: 0, it?.explosions().orEmpty().map(::explosion)) }
      item.has(DataComponents.FIREWORK_EXPLOSION) && item.item == GameItems.FIREWORK_STAR -> ItemData.Firework(null, listOf(explosion(item.get(DataComponents.FIREWORK_EXPLOSION)!!)))
      else -> null
    },
    map = if (item.item != GameItems.FILLED_MAP) null else level?.let { MapItem.getSavedData(item, it) }?.let { map ->
      ItemData.MapInfo(
        empty = false,
        scale = map.scale.toInt(),
        centerX = map.centerX,
        centerZ = map.centerZ,
        world = Worlds.name(map.dimension),
        dimension = Dimensions.id(map.dimension),
        locked = map.locked,
      )
    } ?: ItemData.MapInfo(empty = true),
  )
}

/** Bukkit's names for a written book's generation, by the game's number. */
private val GENERATIONS = listOf("original", "copy_of_original", "copy_of_copy", "tattered")

/** [Facts.place] from where the player stands. */
fun JsonObjectBuilder.place(x: Int, y: Int?, z: Int, from: ServerPlayer) = with(Facts) { place(x, y, z, from.x, from.z) }

/** An id as the model reads it: `diamond_sword` for the game's own, `mod:thing` for a mod's. */
fun Worlds.key(holder: Holder<*>): String = holder.unwrapKey().map { key(it.identifier()) }.orElse("unknown")
