package chat.allay.spigot

import chat.allay.core.UsageBook.Totals
import com.mairwunnx.worldapi.expansion.v2.Expansion
import com.mairwunnx.worldapi.expansion.v2.Permission
import com.mairwunnx.worldapi.expansion.v2.PlayerRef
import com.mairwunnx.worldapi.expansion.v2.Request
import java.util.UUID

/** What a leaderboard ranks players by, as its id in the path. */
enum class Board(val title: String, val unit: String, val score: (Totals) -> Double) {
  QUESTIONS("Questions to Allay", "questions", { it.asked.toDouble() }),
  INPUT_TOKENS("Tokens Allay read", "tokens", { it.input_tokens.toDouble() }),
  OUTPUT_TOKENS("Tokens Allay wrote", "tokens", { it.output_tokens.toDouble() }),
  COST("Allay's cost", "USD", { it.cost }),
}

/** A leaderboard, shaped as WorldAPI's own. */
@JvmRecord data class Leaderboard(val id: String, val title: String, val unit: String, val total: Int, val entries: List<Ranked>)

@JvmRecord data class Ranked(val place: Int, val player: PlayerRef, val value: Double)

/**
 * The `allay` namespace in WorldAPI, through its expansion API 2 (`com.mairwunnx:worldapi-expansion`). WorldAPI 4.5.0
 * and newer bring the API's classes, so this object loads only when it runs: [WorldApiLink] is the side the rest of the
 * plugin calls. Everything is public but cost, which needs the permission WorldAPI gives players' private data.
 */
object UsageExpansion : Expansion(WorldApiLink.NAMESPACE, "Allay", "Allay, the AI helper: questions asked, tokens, cost, and answer times, for the server and each player") {
  @Volatile private var link: WorldApiLink? = null

  val limit = integer("limit", "How many players to return", 1, 500, 50)
  val player = player("player", "A player's name or UUID")
  val board = choice("board", "What players are ranked by. Cost only with players:private", Board::class.java, Board.QUESTIONS)

  val stats = resource("stats", "All-time questions by command, tokens read and written, answers and their average and median seconds, and players who asked. Cost in US dollars with players:private") { req -> link().numbers(link().book.total(), cost(req)) + mapOf("players" to link().book.players().size) }.publicly()
  val players = resource("players", "Players by questions asked, with their numbers. Cost in US dollars with players:private") { req -> players(req[limit], cost(req)) }.query(limit).publicly()
  val card = resource("players/{player}", "A player's numbers. Cost in US dollars with players:private") { req -> card(req[player].uuid(), cost(req)) }.publicly()
  val leaderboards = resource("leaderboards/{board}", "Players ranked by questions, input_tokens, output_tokens, or cost (with players:private), shaped as WorldAPI's own leaderboards") { req -> leaderboard(req[board], req[limit], cost(req)) }.query(limit).returns(Leaderboard::class.java).publicly()

  /** Shares Allay's numbers. Throws [LinkageError] when WorldAPI is older than 4.5.0. */
  fun register(link: WorldApiLink) {
    this.link = link
    register()
  }

  private fun link() = link ?: throw IllegalStateException("Allay is off")

  private fun players(limit: Int, cost: Boolean) = link().book.players().entries.sortedByDescending { it.value.asked }.take(limit).map { (id, totals) -> link().card(id, totals, cost) }

  private fun card(id: UUID, cost: Boolean) = link().card(id, link().book.player(id) ?: throw NoSuchElementException("This player hasn't asked Allay here"), cost)

  private fun leaderboard(board: Board, limit: Int, cost: Boolean): Leaderboard {
    if (board == Board.COST && !cost) throw NoSuchElementException("Boards: questions, input_tokens, output_tokens")
    val ranked = link().book.players().entries.map { it.key to board.score(it.value) }.filter { it.second > 0 }.sortedByDescending { it.second }
    val entries = ranked.take(limit).mapIndexed { i, (id, value) -> Ranked(i + 1, PlayerRef(id, link().name(id)), value) }
    return Leaderboard(board.name.lowercase(), board.title, board.unit, ranked.size, entries)
  }

  /** What a player spends is private-ish: only clients that may read players' private data see it. */
  private fun cost(req: Request) = !req.anonymous() && req.can(Permission.PLAYERS_PRIVATE)
}
