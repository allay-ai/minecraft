package chat.allay.spigot

import chat.allay.core.UsageBook.Totals
import com.mairwunnx.worldapi.expansion.ExpansionApi
import com.mairwunnx.worldapi.expansion.Request
import com.mairwunnx.worldapi.expansion.Resource
import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions

/**
 * The `allay` namespace in WorldAPI, through its expansion API (`com.mairwunnx:worldapi-expansion`). WorldAPI brings
 * the API's classes, so this class loads only when it runs; [WorldApiLink] is the side the rest of the plugin calls.
 * Everything is public but cost, which needs the permission WorldAPI gives players' private data.
 */
class UsageExpansion private constructor(private val link: WorldApiLink) : WorldApiExpansion {
  override fun id() = ID

  override fun requiresApi(): String = ExpansionApi.VERSION

  override fun name() = "Allay"

  override fun description() = "Allay, the AI helper: questions asked, tokens, cost, and answer times, for the server and each player"

  override fun resources(): List<Resource> = listOf(
    Resource.of("stats", "All-time questions by command, tokens read and written, answers and their average and median seconds, and players who asked; cost in US dollars with players:private") { request ->
      link.numbers(link.book.total(), cost(request)) + mapOf("players" to link.book.players().size)
    }.publicly(),
    Resource.of("players", "Players by questions asked, with their numbers; ?limit=1..500") { request ->
      val limit = request.query("limit", 50, 1, 500)
      val cost = cost(request)
      link.book.players().entries.sortedByDescending { it.value.asked }.take(limit).map { (id, totals) -> link.card(id, totals, cost) }
    }.publicly(),
    Resource.of("players/{player}", "A player's numbers, by UUID or name; cost in US dollars with players:private") { request ->
      val id = link.player(request.path("player"))
      link.card(id, link.book.player(id) ?: throw NoSuchElementException("This player hasn't asked Allay here"), cost(request))
    }.publicly(),
    Resource.of("leaderboards/{board}", "Players ranked by questions, input_tokens, or output_tokens (cost with players:private), shaped as WorldAPI's own leaderboards; ?limit=1..500") { request ->
      val board = request.path("board")
      val (title, unit, score) = when (board) {
        "questions" -> Triple("Questions to Allay", "questions") { t: Totals -> t.asked.toDouble() }
        "input_tokens" -> Triple("Tokens Allay read", "tokens") { t: Totals -> t.input_tokens.toDouble() }
        "output_tokens" -> Triple("Tokens Allay wrote", "tokens") { t: Totals -> t.output_tokens.toDouble() }
        "cost" -> if (cost(request)) Triple("Allay's cost", "USD") { t: Totals -> t.cost } else throw NoSuchElementException("Boards: questions, input_tokens, output_tokens")
        else -> throw NoSuchElementException("Boards: questions, input_tokens, output_tokens" + if (cost(request)) ", cost" else "")
      }
      val players = link.book.players()
      val ranked = players.entries.map { it.key to score(it.value) }.filter { it.second > 0 }.sortedByDescending { it.second }
      mapOf(
        "id" to board, "title" to title, "unit" to unit, "total" to ranked.size,
        "entries" to ranked.take(request.query("limit", 10, 1, 500)).mapIndexed { i, (id, value) ->
          mapOf("place" to i + 1, "player" to mapOf("uuid" to id.toString(), "name" to link.name(id)), "value" to value)
        },
      )
    }.publicly(),
  )

  /** What a player spends is private-ish: only clients that may read players' private data see it. */
  private fun cost(request: Request) = !request.anonymous() && request.can(PRIVATE)

  companion object {
    const val ID = "allay"
    const val PRIVATE = "players:private"

    fun register(link: WorldApiLink) = WorldApiExpansions.register(UsageExpansion(link))

    fun unregister() = WorldApiExpansions.unregister(ID)
  }
}
