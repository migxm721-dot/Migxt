package net.miggers.botgames.cricket

import io.netty.util.internal.StringUtil
import net.miggers.botgames.common.Bot
import net.miggers.botgames.common.Pot
import net.miggers.botgames.data.BotData
import net.miggers.packet.MessageRoomPacket
import org.koin.core.KoinComponent
import org.slf4j.LoggerFactory
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class Cricket(
    val botData: BotData,
    val executor: ScheduledExecutorService,
    val room: String,
    val userStarted: String
) : Bot(botData,executor,room,userStarted) , KoinComponent {

    internal val log = LoggerFactory.getLogger("Cricket")

    // Parameter names
    val AMOUNT_JOIN_POT = "amountJoinPot"

    val MIN_PLAYERS = "minPlayers"
    val MAX_PLAYERS = "maxPlayers"
    val MIN_RANGE = "minRange"
    val MAX_RANGE = "maxRange"
    val FINAL_ROUND = "finalRound"

    val TIMER_JOIN_GAME = "timeToJoinGame"
    val TIMER_CANCEL_GAME = "timeToCancel"
    val TIMER_END_ROUND = "timeToEndRound"
    val TIMER_DECISION_INTERVAL = "decisionInterval"

    // Game parameters
    internal var minAmountJoinPot = 0.5
    internal var amountJoinPot = 0.5

    internal var timeToJoinGame: Long = 30
    internal var timeToCancel: Long = 5
    internal var timeToEndRound: Long = 20
    internal var decisionInterval: Long = 20000
    internal var waitBetweenRoundInterval: Long = 5000

    var minPlayers = 2
    var maxPlayers = 200

    var finalRound = 6
    var numRollsPerRound = 3

    // Game variables
    private var round = 0
    private var waitRound = false
    private var gameState = BotData.BotStateEnum.NO_GAME
    private val idleInterval: Long = 1800000
    private var timeLastGameFinished = System.currentTimeMillis()
    private var startPlayer = ""
    private val decimalFormat = DecimalFormat("0.00")

    private var playerScores: MutableMap<String, Int>? = null
    private var playerThirdUmpires: MutableMap<String, Int>? = null
    private var playerDecks: MutableMap<String, Deck>? = null
    private var playerDrawnCards: MutableMap<String, Deck.Card>? = null
    private var playerOuts: MutableList<String>? = null

    private val decisionTimer: ScheduledFuture<*>? = null
    private var roundTimer: ScheduledFuture<*>? = null
    private var waitingPlayersTimer: ScheduledFuture<*>? = null
    private var startingTimer: ScheduledFuture<*>? = null

    // Game commands
    private val COMMAND_BOWL = "!d"
    private val COMMAND_CANCEL = "!n"


    init {
        playerScores = HashMap(maxPlayers)
        playerThirdUmpires = HashMap(maxPlayers)
        playerDecks = HashMap(maxPlayers)
        playerDrawnCards = HashMap(maxPlayers)
        playerOuts = ArrayList(maxPlayers)

        log.info(botData.displayName + " [" + instanceID + "] added to channel [" + room + "]")

        // Send starting messages
//        sendChannelMessage(createMessage("BOT_ADDED", null))
//        sendChannelMessage(createMessage("GAME_STATE_DEFAULT_AMOUNT", null))
        sendMessage("Bot ${botData.displayName} added to room")
        sendMessage("!start to start a game of Cricket. Cost: USD $minAmountJoinPot. For custom entry, !start <entry_amount>")
    }


    @Synchronized
    override fun onMessage(messageRoomPacket: MessageRoomPacket) {
        val messageText = messageRoomPacket.message.toLowerCase().trim({ it <= ' ' })
        val username = messageRoomPacket.from
        if (COMMAND_START.equals(messageText) || messageText.startsWith(COMMAND_START) && messageText.split(" ".toRegex()).dropLastWhile(
                { it.isEmpty() }).toTypedArray().size == 2) {
            startNewGame(username, messageText)
        } else if (COMMAND_CANCEL == messageText) {
            if (startPlayer == username) {
                cancelGame(username)
            } else {
                sendMessage("Only $startPlayer can cancel the pot")
            }
        } else if (COMMAND_JOIN.equals(messageText)) {
            joinGame(username)
        } else if (COMMAND_BOWL == messageText) {
            bowl(username, false)
        } else {
            //sendMessage(messageText + " is not a valid command.")
        }
    }

    private fun startNewGame(username: String, messageText: String) {
        when (gameState) {
            BotData.BotStateEnum.NO_GAME -> {

                // Reset the amount to join pot
                amountJoinPot = minAmountJoinPot

                // Custom amount
                if (messageText.length > COMMAND_START.length) {
                    try {
                        val amount = java.lang.Double.parseDouble(messageText.substring(COMMAND_START.length + 1))

                        // More than the default amount
                        if (amount < minAmountJoinPot) {
                            sendPvtMessage(username, "$username: Invalid amount. Custom amount has to be USD 0.05 or more (e.g. !start 5) ")
                            return
                        } else {
                            amountJoinPot = amount
                        }
                    } catch (e: NumberFormatException) {
                        sendPvtMessage(username,"PLAYER: Invalid amount. Custom amount has to be in integer (e.g. !start 5) ")
                        return
                    }

                }

                if (!userCanAffordToEnterPot(username, room ,amountJoinPot)) return

                startPlayer = username
                sendPvtMessage(username,

                        "$username: added to game. Charges apply. USD $amountJoinPot. Create/enter pot. !n to cancel. " + timeToCancel + " seconds."

                )

                gameState = BotData.BotStateEnum.GAME_STARTING
                startingTimer = executor.schedule(
                    Runnable {
                        initGame()

                        playerScores?.put(username, 0)
                        playerThirdUmpires?.put(username, 0)

                        val deck = Deck()
                        deck.init()
                        playerDecks?.put(username, deck)

                        sendMessage("Cricket Game started by $startPlayer. !j to join.Cost USD $amountJoinPot . $timeToJoinGame seconds")
                        waitForMorePlayers()
                    },
                    timeToCancel,
                    TimeUnit.SECONDS
                )
            }
            BotData.BotStateEnum.GAME_STARTING -> sendMessage("$username: Cricket Game is starting soon.", username)
            BotData.BotStateEnum.GAME_JOINING -> sendMessage("Play Cricket. Enter !j to join the game. Cost USD $amountJoinPot.", username)
            BotData.BotStateEnum.PLAYING -> sendMessage("Cricket is on going now. Get ready for the next game.", username)
        }

    }

    private fun initGame() {
        round = 0
        pot = null
        playerScores?.clear()
        playerThirdUmpires?.clear()
        playerDecks?.clear()
        playerDrawnCards?.clear()

    }

    private fun waitForMorePlayers() {
        sendMessage("Waiting for more players. Enter !j to join the game")
        gameState = BotData.BotStateEnum.GAME_JOINING
        waitingPlayersTimer = executor.schedule(
            { chargeAndCountPlayers() },
            timeToJoinGame,
            TimeUnit.SECONDS
        )

    }

    private fun chargeAndCountPlayers() {
        if (gameState !== BotData.BotStateEnum.GAME_JOINING) {
            return
        }
        try {
            pot = Pot(this)

            val notAdded = LinkedList<String>()
            for (player in playerScores?.keys!!) {
                try {
                    pot?.enterPlayer(player, amountJoinPot, "USD")
                } catch (e: Exception) {
                    sendPvtMessage(player, "Unable to join you to the game " )
                    notAdded.add(player)
                }

            }

            for (notAddedPlayer in notAdded) {
                playerScores?.remove(notAddedPlayer)
            }

            if (playerScores?.size!! < minPlayers) {
                endGame(true)
                sendMessage("Joining ends. Not enough players. Need $minPlayers. Enter !start to start a new game.")
            } else if (playerScores?.size!! > maxPlayers) {
                endGame(true)
                sendMessage("Joining ends. Too many players. Max $maxPlayers. Enter !start to start a new game.")
            } else {
                // Log info
                //logGamesPlayed(playerScores.size, playerScores.keys, amountJoinPot)

                // LEADERBOARD: Increment Games Played counter
                //incrementGamesPlayed(Leaderboard.Type.CRICKET_GAMES_PLAYED, playerScores.keys)

                gameState = BotData.BotStateEnum.PLAYING
                sendMessage("Game begins - Score the most runs!")

                nextRound()
            }
        } catch (e: Exception) {
            log.error("Unexpected exception occured in chargeAndCountPlayers() ", e)
            endGame(true)
            sendMessage("Unable to start the game.")
        }


    }

    private fun nextRound() {
        // Reset
        playerDrawnCards?.clear()
        playerOuts?.clear()

        round++

        waitRound = true
        sendMessage("Round #$round is starting in 5 seconds")
        executor.schedule(
            {
                waitRound = false
                startRound()
            },
            5000,
            TimeUnit.MILLISECONDS
        )

    }

    private fun startRound() {
        sendMessage("Round $round: Players, Time to hit. !d to bat. $timeToEndRound seconds")
        roundTimer = executor.schedule(
            {
                sendMessage("TIME'S UP! Tallying...")
                roundEnded()
            },
            timeToEndRound,
            TimeUnit.SECONDS
        )

    }

    private fun sortByValue(map: Map<String, Int>): MutableMap<String, Int> {
        val list = LinkedList(map.entries)
        list.sortWith(Comparator { o1: Map.Entry<String, Int>, o2: Map.Entry<String, Int> ->
            o1.value.compareTo(o2.value)
        })
//        Collections.sort(list, object : Comparator {
//            override fun compare(o1: Any, o2: Any): Int {
//                return ((o1 as Map.Entry<*, *>).value as Comparable<*>)
//                    .compareTo((o2 as Map.Entry<*, *>).value)
//            }
//        })

        list.reverse()

        val result = LinkedHashMap<String,Int>()
        val it = list.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            result.put(entry.key, entry.value)
        }
        return result
    }
    private fun roundEnded() {
        playerScores = playerScores?.let { sortByValue(it) }

        // Bot roll
        for (player in playerScores?.keys!!) {
            if (!playerDrawnCards?.containsKey(player)!!) {
                bowl(player, true)
            }
        }
        // Display end of round results
        sendMessage("Round over! Results:")


        // Remove OUT player
        if (playerOuts?.size == playerScores?.size) {
            sendMessage("Nobody won, so we'll try again!")
        } else {
            for (i in playerOuts?.indices!!) {
                val player = playerOuts?.get(i)

                try {
//                    pot?.removePlayer(player!!)
                } catch (e: Exception) {
                    log.error("Unexpected exception occured in removing bottom player from the pot", e)
                }

                playerScores?.remove(player)
                playerDecks?.remove(player)
                playerThirdUmpires?.remove(player)
            }
        }

        var tallyMessages: MutableMap<String, Int> = java.util.HashMap()
        for (player in playerScores?.keys!!) {
            val totalScore = playerScores?.get(player)
            val card = playerDrawnCards?.get(player)

            var message = ""

            if (card?.type === "U") {
                message = "Umpire"
            } else if (card?.type === "O") {
                // nothing
            } else {
                val runs = Integer.parseInt(card?.type)
                message = "+" + runs + if (runs == 1) " Run" else " Runs"
            }
            val msg = "$player: $message ($totalScore)"
            tallyMessages[msg] = totalScore!!
        }

        // GAMES-151:
        // Sort the messages by the totals associated with them before sending
        // to the channel. - ali
        tallyMessages = sortByValue(tallyMessages)
        for (msg in tallyMessages.keys) {
            sendMessage(msg)
        }

        if (round < finalRound && playerScores?.size!! > 1) {
            nextRound()
        } else {
            // Final Round
            playerScores = sortByValue(playerScores!!)
            var highestScore: Int? = -1
            val playerRemoved = java.util.ArrayList<String>()
            for (player in playerScores?.keys!!) {

                val score = playerScores?.get(player)

                if (score!! < highestScore!!) {
                    playerRemoved.add(player)
                } else {
                    highestScore = score
                }
            }

            // Remove bottom players
            for (i in playerRemoved.indices) {
                val p = playerRemoved[i]

                playerScores?.remove(p)
                playerDecks?.remove(p)
                playerThirdUmpires?.remove(p)

                try {
//                    pot?.removePlayer(p)
                } catch (e: Exception) {
                    log.error("Unexpected exception occured in removing bottom player from the pot", e)
                }


            }



            if (playerScores?.size!! > 1) {
                sendMessage(
                    "There is a tie. " + playerScores?.size + " left in the game [" +
                            StringUtil.join(", ",playerScores?.keys) + "]"
                )
                nextRound()
            } else if (playerScores?.size == 1) {
                val winner = playerScores?.keys?.iterator()?.next()

                sendMessage("$winner is the last player in.")

                val payout = endGame(false)
                if (payout < 0) {
//                    sendChannelMessageAndPopUp(SystemProperty.get(SystemPropertyEntities.BOT.PAYOUT_FAILURE_MESSAGE))
                } else {
                    // Log info
//                    logMostWins(winner, payout)

                    // LEADERBOARD: Increment Most Wins counter
//                    incrementMostWins(Leaderboard.Type.CRICKET_MOST_WINS, winner)

//                    sendChannelMessageAndPopUp(
//                        createMessage("GAME_OVER", winner).replaceFirst(
//                            "%1".toRegex(),
//                            decimalFormat.format(payout)
//                        )
//                    )
                    sendMessage(
                        "Cricket Game over! $winner WINS USD %1! CONGRATS!".replaceFirst(
                            "%1".toRegex(),
                            decimalFormat.format(payout)
                        )
                    )

                    pot?.payout(winner!!, pot?.printTotalAmount()?.toDouble()!!)
                }
                sendMessage("Enter !start to start a game")

                executor.schedule(
                    { sendMessage("!start to start a game of Cricket. Cost: USD $minAmountJoinPot. For custom entry, !start <entry_amount>") },
                    5000,
                    TimeUnit.MILLISECONDS
                )
            } else if (playerScores?.size == 0) {
                sendMessage("No more players left in the game. Enter !start to start a new game")
                endGame(false)

                executor.schedule(
                    { sendMessage("!start to start a game of Cricket. Cost: USD $minAmountJoinPot. For custom entry, !start <entry_amount>") },
                    5000,
                    TimeUnit.MILLISECONDS
                )
            }
        }

    }

    private fun bowl(username: String, botDraw: Boolean) {

        when (gameState) {
            BotData.BotStateEnum.NO_GAME -> sendPvtMessage(username,"Enter !start to start a game")
            BotData.BotStateEnum.GAME_STARTING -> sendPvtMessage(username, "$username: Cricket Game is starting soon.")
            BotData.BotStateEnum.GAME_JOINING -> sendMessage("$username: Game haven't started. Enter !j to join the game", username)
            BotData.BotStateEnum.PLAYING -> {

                //
                if (!playerScores?.containsKey(username)!!) {
                    sendMessage("You are not in the game", username)
                    return
                }

                //
                if (waitRound) {
                    sendMessage("Round #$round starting. Please wait.", username)
                    return
                }

                if (playerDrawnCards?.containsKey(username)!!) {
                    sendMessage("You have already drawn ur card. Your turn ends.", username)
                } else if (playerScores?.containsKey(username)!! && !playerDrawnCards?.containsKey(username)!!) {
                    val deck = playerDecks?.get(username)
                    val card = deck?.draw()
                    playerDrawnCards?.put(username,card!!)

                    if (botDraw) {
                        sendMessage(

                                "Bot draws - $username: " + card?.emoticonKey + " " + card?.namex

                        )
                    } else {
                        sendMessage(
                                "$username: " + card?.emoticonKey + " " + card?.namex
                        )
                    }

                    if (card?.type === "O") {
                        var numUmpire: Int? = playerThirdUmpires?.get(username)

                        if (numUmpire!! <= 0) {

                            playerOuts?.add(username)

                            sendMessage("$username: OUT by " + card.namex)
                        } else {
                            numUmpire = numUmpire!! - 1
                            playerThirdUmpires?.put(username, numUmpire)

                            sendMessage(
                                    "$username: IMMUNE by " + Deck.Card.THIRD_UMPIRE.namex + ". Current turn ends."
                            )
                        }
                    } else if (card?.type === "U") {
                        var numUmpire: Int? = playerThirdUmpires?.get(username)
                        playerThirdUmpires?.put(username, numUmpire!! + 1)

                        sendMessage(
                                "$username: SAFE by " + Deck.Card.THIRD_UMPIRE.namex + "! Immune to next out."
                        )
                    } else { // number 1-6
                        val score = Integer.parseInt(card?.type)
                        val totalScore = playerScores?.get(username)
                        playerScores!![username] = totalScore!! + score
                    }

                    playerDecks?.put(username, deck!!)


                    // If all the players have drawn the timer cancelled
                    if (botDraw == false && playerDrawnCards?.size == playerScores?.size) {
                        sendMessage("Everyone drawn.")

                        if (roundTimer != null) {
                            roundTimer?.cancel(true)
                        }

                        sendMessage("TIME'S UP! Tallying...")
                        roundEnded()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun joinGame(username: String) {
        when (gameState) {
            BotData.BotStateEnum.NO_GAME -> sendMessage("Enter !start to start a game", username)
            BotData.BotStateEnum.GAME_STARTING -> sendMessage("$username: Cricket Game is starting soon.", username)
            BotData.BotStateEnum.GAME_JOINING -> if (playerScores?.containsKey(username)!!) {
                sendMessage("You have already joined the game. Please wait for the game to start", username)
            } else if (playerScores?.size!! + 1 > maxPlayers) {
                sendMessage(
                    "Too many players joined the game. Max $maxPlayers players. Please wait for the next game.",
                    username
                )
            } else if (!userCanAffordToEnterPot(username, room,amountJoinPot)) {
                //sendMessage("You do not have sufficient credit to join the game", username);
            } else if (playerScores!!.put(username, 0) == null) {
                log.info("$username joined the game")
                playerThirdUmpires?.put(username, 0)

                val deck = Deck()
                deck.init()
                playerDecks?.put(username, deck)

                sendMessage("$username joined the game")

                // Ends the timer
                if (playerScores?.size == maxPlayers && waitingPlayersTimer != null) {
                    waitingPlayersTimer?.cancel(true)
                    chargeAndCountPlayers()
                }
            }
            BotData.BotStateEnum.PLAYING -> sendMessage("A game is currently in progress. Please wait for next game", username)
        }

    }


    @Synchronized
    private fun cancelGame(username: String) {
        when (gameState) {
            BotData.BotStateEnum.GAME_STARTING -> {
                if (startingTimer != null) {
                    startingTimer?.cancel(true)
                }

                // player
                val player = java.util.ArrayList<String>()
                player.add(username)
                revertLimitInCache(player,amountJoinPot)

                gameState = BotData.BotStateEnum.NO_GAME
                amountJoinPot = minAmountJoinPot

                sendMessage("$username: You were not charged.", username)
            }
            else -> sendMessage("Invalid command.")
        }

    }

    override fun runner(botData: BotData) {

    }

    fun isIdle(): Boolean {
        return gameState === BotData.BotStateEnum.NO_GAME && System.currentTimeMillis() - timeLastGameFinished > idleInterval
    }

    override fun stopBot(from: String) {
        synchronized(this) {
            endGame(true)
            gameState = BotData.BotStateEnum.NO_GAME
        }
    }

    @Synchronized
    fun endGame(cancelPot: Boolean): Double {
        if (gameState === BotData.BotStateEnum.NO_GAME) {
            log.warn("endGame() called but game has already ended")
            return 0.0
        }

        var payout = 0.0

        if (cancelPot) {
            //revertLimitInCache(playerScores?.keys, amountJoinPot)
        }

        // cancel timer
        decisionTimer?.cancel(true)

        if (pot != null) {
            if (cancelPot) {
                try {
                    pot?.cancel()
                } catch (e: Exception) {
                    log.error("Unable to cancel pot ", e)
                }

            } else {
                try {
//                    val accountEJB = EJBHomeCache.getObject(AccountHome.JNDI_NAME, AccountHome::class.java) as Account
//                    payout = pot.payout(true)
//                    payout = accountEJB.convertCurrency(payout, BASE_CURRENCY, CURRENCY)
                    payout = pot?.printTotalAmount()?.toDouble()!!


                } catch (e: Exception) {
                    log.error("Unable to payout pot " , e)
                    payout = -1.0
                }

            }
        }

        timeLastGameFinished = System.currentTimeMillis()
        gameState = BotData.BotStateEnum.NO_GAME
        amountJoinPot = minAmountJoinPot

        return payout
    }


}
