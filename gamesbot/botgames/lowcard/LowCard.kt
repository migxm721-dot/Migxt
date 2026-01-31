package net.miggers.botgames.lowcard

import com.sun.org.apache.xalan.internal.res.XSLMessages.createMessage
import net.miggers.Constant
import net.miggers.botgames.common.Bot
import net.miggers.botgames.common.Card
import net.miggers.botgames.common.Pot
import net.miggers.botgames.data.BotData
import net.miggers.data.cache.CreditCache
import net.miggers.data.cache.HistoryCreditStore
import net.miggers.data.cache.LevelCache
import net.miggers.data.model.History
import net.miggers.packet.MessageRoomPacket
import net.miggers.packet.MessageType
import org.koin.core.inject
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LowCard(
    val botData: BotData,
    val executor: ScheduledExecutorService,
    val room: String,
    val userStarted: String
) : Bot(botData,executor,room,userStarted) {

    val log = LoggerFactory.getLogger(LowCard::class.java)
    private val creditCache: CreditCache by inject()
    private val levelCache: LevelCache by inject()

    val TIMER_JOIN_GAME = "timerJoinGame"            // Parameter name for time allowed to join a game (in seconds)
    val TIMER_CHARGE_CONF = "timerChargeConfirm"    // Parameter name for time allowed to confirm a charge (in seconds)
    val TIMER_DRAW = "timerDraw"                    // Parameter defining time given to all players to draw a card,
    val TIMER_NEW_ROUND_INTERVAL = "timerNewRound"    // Parameter defining time before new round begins (seconds)
    val TIMER_IDLE =
        "timerIdle"                    // Parameter defining time allowed for this bot to be idle, before marking it to be killed (in minutes)
    val AMOUNT_JOIN_POT =
        "amountJoinPot"            // Parameter defining amount charged to enter the pot (this is different from a join fee (amountJoinGame), which does not become part of a pot/winnings)

    // Default parameter values
    val TIMER_DRAW_VALUE: Long = 20
    val TIMER_NEW_ROUND_VALUE: Long = 3
    val AMOUNT_JOIN_POT_VALUE = 0.5
    val IDLE_TIME_VALUE: Long = 3 // maximum number of minutes bot can be inactive, after which it is considered "idle"

    // Parameters
    internal var timeToJoinGame: Long = 30
    internal var timeToConfirmCharge: Long = 5
    internal var timeToDraw: Long = 20 // time given to all players to draw a card, before an auto-draw kicks in (in seconds)
    internal var timeToNewRound: Long = 3 // time before new round begins (seconds)
    internal var amountJoinPot = 10
    internal var winnings = 0.0

    var minPlayers = 2
    internal var timeAllowedToIdle: Long = 30 // time allowed for this bot to be idle, before marking it to be killed (in minutes)

    // Variables to store the original default, for user-entered custom value
    internal var amountOriginalJoinPot = AMOUNT_JOIN_POT_VALUE

    // Commands
    val COMMAND_DRAW = "!d"

    // "Idle"ness
    lateinit var lastActivityTime: Date        // time when this bot was last active. Used to let the BotService know if the bot has been idle for too long, so we can kill it.

    // Game variables

    private val playerHands = HashMap<String, Hand>()                // list of players and their current hand
    private val tiebreakerHands =
        HashMap<String, Hand>()                // list of players and their current hand who are part of the current round, when they replay their turn
    private var gameState = BotData.BotStateEnum.NO_GAME
    private var isRoundStarted =
        false                // true if the current round has started and player can type the draw command
    private var isTiebreaker = false
    internal var deck =
        Deck()                                                                // deck of cards, refreshed each round
    var lowestHandAlreadyLeft: Hand? = null

    //boolean hasWinner = false;        // track in each round if there was a winner.
    internal var currentRoundNumber = 0    // current round of the game

    var nextDrawTimerTask: ScheduledFuture<*>? = null    // hold the reference to the scheduled timer task so it can be canceled if needed


    init {
        log.info("LowCardBot [$instanceID] added to channel [$room]")
        sendMessage("Bot ${botData.displayName} added to room by $userStarted")
        val message =
            if (amountJoinPot > 0.0) "Play now: !start to enter. Cost: Coins $amountJoinPot. For custom entry, !start <entry_amount> " else "Play LowCard: !start. Need $minPlayers players."
        sendMessage(message)
        updateLastActivityTime()
    }

    private fun updateLastActivityTime() {
        lastActivityTime = Date()
    }

    override fun onMessage(messageRoomPacket: MessageRoomPacket) {
        synchronized(this){
            val messageText = messageRoomPacket.message
            val username = messageRoomPacket.from
            if (messageText.equals("!n", ignoreCase = true)) {                        // said 'no' to start game
                processNoMessage(messageRoomPacket.from)
            } else if (messageText.toLowerCase().startsWith("!start")) {        // start game
                start(username, messageText)
            } else if (messageText.equals("!j", ignoreCase = true)) {                // join game
                join(username)
            } else if (messageText.toLowerCase().startsWith(COMMAND_DRAW)) {        // draw
                if (gameState === BotData.BotStateEnum.PLAYING && isRoundStarted) {
                    if (!playerHands.containsKey(username)) {
                        sendPvtMessage(username,"$username: you''re not in the game.")
                    } else {
                        if (isTiebreaker && !tiebreakerHands.containsKey(username)) {
                            sendPvtMessage(username, "$username: Only tied players can draw now. Please wait... ")
                            return
                        }

                        //if (messageText.length() > COMMAND_DRAW.length())
                        //      draw(username, messageText.substring(COMMAND_DRAW.length()), false);
                        //else
                        draw(username, "", false)
                    }
                } else {
                    sendPvtMessage(username, "$username: Invalid command.")
                }
            }else if(messageRoomPacket.type == MessageType.TYPE_ROOM){
                if(messageRoomPacket.message.contains("has entered")){
                    val split = messageRoomPacket.message.split(" ")
                    sendPvtMessage(botData.displayName,split[0], room, "[PVT] ${botData.displayName} is Running! !start to start a game. !j to join game. Amount = 0.05 usd")
                }
            }
        }
    }

    private fun join(username: String) {
        if (!playerHands.containsKey(username)) {
            if (gameState === BotData.BotStateEnum.GAME_JOINING)
                addPlayer(username)
            else if (gameState === BotData.BotStateEnum.PLAYING) {
                sendPvtMessage(username,"$username: Sorry, a game has already started.")
            } else {
                sendPvtMessage(username, "$username: Invalid command.")
            }
        } else {
            sendPvtMessage(username, "$username: You are already added to game.")
        }
    }

    private fun addPlayer(username: String) {
        if (getGameState() === BotData.BotStateEnum.GAME_STARTED || getGameState() === BotData.BotStateEnum.GAME_JOINING) {
            if (amountJoinPot > 0.0) {
                val hasFunds = userCanAffordToEnterPot(username, room,amountJoinPot /**, !gameStarter.equals(username)**/)
                if (!hasFunds) {
                    //sendMessage(createMessage("INSUFFICIENT_FUNDS_POT", username), username);
                    return
                }
            }

            synchronized(playerHands) {
                if (!playerHands.containsKey(username)) {
                    playerHands[username] = Hand(username)
                }
            }
            val message = StringBuilder()
            message.append("$username: added to game.")

            if (amountJoinPot > 0.0) {
                message.append("Charges apply. USD $amountJoinPot")
            }

            log.info("$username joined the game")

            sendPvtMessage(username, message.toString())
            if (username != gameStarter) {
                sendMessage("$username joined the game.")
                levelCache.addExp(username, Constant.exp_game)
            }
        }

    }

    private fun start(username: String, messageText: String) {
        if (getGameState() === BotData.BotStateEnum.NO_GAME) {
            if (messageText.trim { it <= ' ' }.length > "!start".length) {
                val parameter = messageText.trim { it <= ' ' }.substring("!start".length + 1)
                if (parameter.isNotEmpty()) {
                    if (checkJoinPotParameter(parameter, username)) {
                        try {
                            startGame(username)
                        } catch (e: Exception) {
                            log.error("Error starting game with custom amount. Command was : '$messageText'", e)
                        }

                    }
                }
            } else {
                try {
                    startGame(username)
                } catch (e: Exception) {
                    log.error("Error starting game with default amount: ", e)
                }

            }
        } else {
            // someone has already started a game
            sendGameCannotStartMessage(username)
        }

    }

    private fun sendGameCannotStartMessage(username: String) {
        var message: String? = null
        when (gameState.value()) {
            BotData.BotStateEnum.ID_GAME_STARTING, BotData.BotStateEnum.ID_GAME_STARTED, BotData.BotStateEnum.ID_PLAYING -> message =
                "$username: A game is currently on."
            BotData.BotStateEnum.ID_GAME_JOINING -> message = "$username: A game is on. !j to join. Charges may apply."
            else -> message = "Sorry, new game cannot be started now."
        }
        sendPvtMessage(username,message)
    }

    private fun startGame(username: String) {
        updateLastActivityTime()
        if (gameState.equals(BotData.BotStateEnum.NO_GAME)) {
            if (amountJoinPot > 0.0) {
                val hasFunds = userCanAffordToEnterPotStart(username,  room,amountJoinPot)
                if (hasFunds) {
                    val message = StringBuilder("$username: Charges apply. USD $amountJoinPot Create/enter pot.")
                    message.append(" !n to cancel. $timeToConfirmCharge seconds")
                    setGameState(BotData.BotStateEnum.GAME_STARTING)
                    sendPvtMessage(username, message.toString())
                    gameStarter = username

                    if (log.isDebugEnabled)
                        log.debug("LowCardBot: starting timer for StartGame()")
                    executor.schedule(StartGame(this), timeToConfirmCharge, TimeUnit.SECONDS)
                    if (log.isDebugEnabled)
                        log.debug("LowCardBot: started timer for StartGame()")
                } else {
                    resetGame(false)
                    //sendMessage(createMessage("INSUFFICIENT_FUNDS_POT", username), username);
                }
            } else {
                if (log.isDebugEnabled)
                    log.debug("botInstanceID[" + instanceID + "]: No charges. Game started by user[" + username + "]")
                setGameState(BotData.BotStateEnum.GAME_STARTING)
                gameStarter = username

                if (log.isDebugEnabled)
                    log.debug("LowCardBot: starting timer for StartGame()")
                executor.execute(StartGame(this))
                if (log.isDebugEnabled)
                    log.debug("LowCardBot: started timer for StartGame()")
            }
        } else {
            // should not get here if we already handle it in onMessage(). But just in case
            sendGameCannotStartMessage(username)
        }
    }

    private fun resetGame(cancel: Boolean) {
        if (cancel) {
            revertLimitInCache(playerHands.keys)
        }
        playerHands.clear()
        tiebreakerHands.clear()
        isTiebreaker = false
        lowestHandAlreadyLeft = null
        nextDrawTimerTask = null
        currentRoundNumber = 0
        isRoundStarted = false
        gameStarter = null
        pot = null
        amountJoinPot = amountOriginalJoinPot // reset to original config parameter
        setGameState(BotData.BotStateEnum.NO_GAME)
    }

    inner class StartGame(var bot: LowCard) : Runnable {

        override fun run() {
            synchronized(bot) {
                if (log.isDebugEnabled)
                    log.debug("botInstanceID[" + instanceID + "]: in StartGame() ")

                var gameState: BotData.BotStateEnum? = null

                gameState = getGameState()

                if (gameState === BotData.BotStateEnum.GAME_STARTING) {
                    setGameState(BotData.BotStateEnum.GAME_STARTED)
                    addPlayer(gameStarter!!)
                    if (timeToJoinGame > 0) {        // schedule the new game to start after a pre-determined delay
                        setGameState(BotData.BotStateEnum.GAME_JOINING)
                        sendMessage(if (amountJoinPot > 0.0) "LowCard started by $gameStarter. !j to join. Cost USD $amountJoinPot. $timeToJoinGame seconds" else "LowCard started by $gameStarter. !j to join. $timeToJoinGame seconds")

                        if (log.isDebugEnabled)
                            log.debug("LowCardBot: starting timer for StartPlay()")
                        executor.schedule(StartPlay(bot), timeToJoinGame, TimeUnit.SECONDS)
                        if (log.isDebugEnabled)
                            log.debug("botInstanceID[" + instanceID + "]: scheduled to start play. Awaiting join.. ")
                    }
                }
            }
        }
    }

    fun getPlayers(): Map<String, Hand> {
        return playerHands
    }

    inner class StartPlay(var bot: LowCard) : Runnable {

        override fun run() {
            synchronized(bot) {
                try {
                    if (log.isDebugEnabled)
                        log.debug("LowCardBot: starting play in StartPlay()")
                    var gameState = getGameState()

                    if (gameState === BotData.BotStateEnum.GAME_JOINING) {
                        setGameState(BotData.BotStateEnum.GAME_JOIN_ENDED)

                        // If not enough players end the game.
                        if (getPlayers().size < minPlayers) {
                            sendMessage("Not enough player join. Coins refunded.")

                            if (log.isDebugEnabled)
                                log.debug("botInstanceID[$instanceID]: Join ended. Not enough players.")
                            resetGame(true)
                        } else {

                            val copyOfPlayers = HashSet<String>()
                            copyOfPlayers.addAll(playerHands.keys)

                            // If the check was successful, time to charge the players for real
                            if (gameState !== BotData.BotStateEnum.NO_GAME) {
                                gameState = BotData.BotStateEnum.PLAYING
                                try {
                                    if (amountJoinPot > 0.0) {
                                        bot.pot = Pot(bot)
                                        log.debug("Pot id created for bot instanceID[$instanceID]")
                                        for (player in copyOfPlayers) {
                                            try {
                                                pot?.enterPlayer(player, amountJoinPot , "USD")
                                                if (log.isDebugEnabled)
                                                    log.debug("botInstanceID[" + instanceID + "]: Entered into pot " + player + " = " + " USD " + amountJoinPot)
                                            } catch (e: Exception) {
                                                playerHands.remove(player)
                                                log.warn(
                                                    "botInstanceID[" + instanceID + "]: Error charging player[" + player + "]",
                                                    e
                                                )
                                                sendPvtMessage(player, "$player: Sorry, insufficient funds to join pot.")
                                            }

                                        }
                                        // If, after charging, there are not enough players, cancel the game
                                        if (playerHands.size < minPlayers) {
                                            if (log.isDebugEnabled)
                                                log.debug("botInstanceID[$instanceID]: Not enough valid players.")
//                                            cancelPot()
                                            resetGame(false)
                                            return
                                        }
                                    }

                                    // LEADERBOARD: Increment Games Played counter
                                    //incrementGamesPlayed(Leaderboard.Type.LOW_CARD_GAMES_PLAYED, playerHands.keys)

                                    // Log info
                                    //logGamesPlayed(playerHands.size, playerHands.keys, amountJoinPot)

                                    // Officially starting play
//                                    sendChannelMessage(createMessage("GAME_STARTED_NOTE"))
                                    sendMessage("Game begins - Lowest card is OUT!")
                                    setGameState(BotData.BotStateEnum.PLAYING)
                                    newRound()

                                } catch (e: Exception) {
                                    log.error("Error creating pot for botInstanceID[" + instanceID + "].", e)
                                    setGameState(BotData.BotStateEnum.NO_GAME)
//                                    sendChannelMessage(createMessage("GAME_CANCELED"))
                                    sendMessage("Billing error. Game canceled. No charges")
                                }

                            } else {
//                                cancelPot()
                                resetGame(false)
                                if (log.isDebugEnabled)
                                    log.debug("botInstanceID[" + instanceID + "]: Billing error. Game canceled. No charges.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.error("Unexpected exception caught in startPlay.run()", e)
//                    cancelPot()
                    resetGame(false)
                }

            }
        }



        /**
         *
         */
        private fun cancelPot() {
            try {
                if (pot != null)
                    pot?.cancel()
            } catch (e: Exception) {
                log.error("Error canceling pot for botInstanceID[" + instanceID + "].", e)
            }

//            sendChannelMessage(createMessage("GAME_CANCELED"))
            sendMessage("Billing error. Game canceled. No charges")
        }

    }

    @Synchronized
    private fun newRound() {
        if (playerHands.size > 1) {
            isRoundStarted = true
            log.debug("Time is " + Date())
            currentRoundNumber++

            deck = Deck()
            resetHands(tiebreakerHands)
            resetHands(playerHands)

            lowestHandAlreadyLeft = null

//            sendChannelMessage(createMessage("PLAYERS_TURN"))
            sendMessage("ROUND #$currentRoundNumber: Players, $COMMAND_DRAW to DRAW. $timeToDraw seconds.")

            executor.schedule(TimedPickWinnerTask(this, currentRoundNumber), timeToDraw, TimeUnit.SECONDS)
        }
    }

    inner class TimedPickWinnerTask(var bot: LowCard, var roundNumber: Int) : Runnable {

        override fun run() {
            synchronized(bot) {
                if (bot.getGameState() === BotData.BotStateEnum.PLAYING && bot.currentRoundNumber == roundNumber) {
                    bot.sendMessage("TIME''S UP! Tallying cards...")
                    bot.tallyDraws()
                }
            }
        }
    }

    private fun tallyDraws() {
        // Make sure we are looking at the right map
        val currentHands = if (isTiebreaker) tiebreakerHands else playerHands

        // Find player(s) with lowest card. Draw a card for them if they didn't draw
        // A few edge cases:
        //              1. Skip this step if there is only 1 player left in the current round
        //              2. Skip this step if the player with the lowest card already left the game
        val lowestHands = ArrayList<Hand>()

        if (currentHands.size > 1) {
            for (hand in currentHands.values) {
                if (hand.card == null) {
                    draw(hand.player, "", true)
                }

                if (lowestHandAlreadyLeft == null || hand.compareTo(lowestHandAlreadyLeft!!) < 0) {
                    if (lowestHands.size == 0) {
                        lowestHands.add(hand)
                    } else if (hand.compareTo(lowestHands[0]) < 0) {
                        lowestHands.clear()
                        lowestHands.add(hand)
                    } else if (hand.compareTo(lowestHands[0]) == 0) {
                        lowestHands.add(hand)
                    }
                }
            }
        }

        if (lowestHands.size == 0) {
            // No loser
            tiebreakerHands.clear()
        } else if (lowestHands.size == 1) {
            // One player with lowest card
            val lowestHand = lowestHands[0]
            playerHands.remove(lowestHand.player)
            //removePlayerFromPot(lowestHand.player)
            sendMessage(

                    if (isTiebreaker)  "Tie broken! ${lowestHand.player}: OUT with the lowest card! ${lowestHand.card}" else "${lowestHand.player}: OUT with the lowest card! ${lowestHand.card}"


            )

            tiebreakerHands.clear()
        } else {
            // Multiple players with lowest card. Setup a tiebreaker
            tiebreakerHands.clear()

            for (hand in lowestHands) {
                tiebreakerHands[hand.player] = hand
            }
        }

        // Check how many players left
        if (playerHands.size < minPlayers) {
            pickWinner()
        } else {
            isRoundStarted = false
            isTiebreaker = tiebreakerHands.size > 0

            if (isTiebreaker) {
                sendMessage(
                    "Tied players" + "(" + tiebreakerHands.size + "): " + stringifyPlayerList(
                        tiebreakerHands
                    )
                )
                sendMessage("Tied players ONLY draw again. Next round in $timeToNewRound seconds!")
            } else {
                sendMessage(
                    "Players are " + "(" + playerHands.size + "): " + stringifyPlayerList(
                        playerHands
                    )
                )
                sendMessage("All players,  next round in $timeToNewRound seconds!")
            }

            nextDrawTimerTask =
                executor.schedule(TimedNewRoundTask(this, currentRoundNumber), timeToNewRound, TimeUnit.SECONDS)
        }

    }

    internal inner class TimedNewRoundTask(var bot: LowCard, var roundNumber: Int) : Runnable {

        override fun run() {
            synchronized(bot) {
                if (log.isDebugEnabled)
                    log.debug("TimedNewRoundTask: currentRoundNumber = " + bot.currentRoundNumber + ", task roundNumber = " + roundNumber)
                if (bot.getGameState() === BotData.BotStateEnum.PLAYING && bot.currentRoundNumber == roundNumber) {
                    newRound()
                }
            }
        }
    }

    private fun stringifyPlayerList(players: HashMap<String, LowCard.Hand>): String? {
        val playerList = StringBuilder()

        val iterator = players.keys.iterator()
        while (iterator.hasNext()) {
            playerList.append(iterator.next()).append(", ")
        }

        val playerListString = playerList.toString()
        return if (playerListString.endsWith(", ")) playerListString.substring(
            0,
            playerListString.length - 2
        ) else playerListString

    }

    private fun pickWinner() {
        if (log.isDebugEnabled)
            log.debug("botInstanceID[" + instanceID + "]: " + "Picking winner: ")
        val iterator = playerHands.keys.iterator()
        var winner: String? = null
        if (iterator.hasNext())
            winner = iterator.next()
        endGame(winner)

    }

    private fun endGame(winner: String?) {
        try {
            if (getGameState() !== BotData.BotStateEnum.PLAYING)
                return

            if (nextDrawTimerTask != null && !nextDrawTimerTask!!.isDone() && !nextDrawTimerTask!!.isCancelled()) {
                log.debug("botInstanceID[" + instanceID + "]: Pending timer task to cancel in endGame() ")
                nextDrawTimerTask!!.cancel(true)
            }

            val localPot = pot
            if (localPot != null) {
                try {
//                    winnings = localPot.payout(true)
//                    // Use the AccountBean to convert to base currency
//                    val accountEJB = EJBHomeCache.getObject(AccountHome.JNDI_NAME, AccountHome::class.java) as Account
//                    winnings = accountEJB.convertCurrency(winnings, BASE_CURRENCY, CURRENCY)
                    localPot.payout(winner!!, localPot.printTotalAmount().toDouble())
                    log.debug("Game over. Pot id payout completed.")
                } catch (e: Exception) {
                    log.error("Game over. Error in pot payout.", e)
//                    sendChannelMessageAndPopUp(SystemProperty.get(SystemPropertyEntities.BOT.PAYOUT_FAILURE_MESSAGE))
//                    sendMessage("")
                    return
                }

            }

            if (winner != null) {
                //sendmessage popup
                sendMessage(
                    if (amountJoinPot > 0.0) "Game over! $winner WINS USD ${localPot?.printTotalAmount()}!! CONGRATS!" else "Game over! $winner wins!! CONGRATS!"
                )

                //exp winner
                levelCache.addExp(winner, Constant.exp_win)

                // Log info
                //logMostWins(winner, winnings)

                // LEADERBOARD: Increment Most Wins counter
                //incrementMostWins(Leaderboard.Type.LOW_CARD_MOST_WINS, winner)
            }
        } catch (e: Exception) {
            log.error("botInstanceID[" + instanceID + "]: Error getting game winner. ", e)
        } finally {
            resetGame(false)
            updateLastActivityTime()
            sendMessage(if (amountJoinPot > 0.0) "Play now: !start to enter. Cost: USD $amountJoinPot. For custom entry, !start <entry_amount>" else "Play LowCard: !start. Need $minPlayers players.")
        }

    }

    @Synchronized
    private fun draw(username: String, cardToDraw: String, auto: Boolean) {
        val currentRoundHands = if (isTiebreaker) tiebreakerHands else playerHands
        val hand = currentRoundHands[username]
        if (hand != null) {
            if (hand.card == null) {
                val card = deck.dealCard(cardToDraw)
                hand.card = card
//                sendChannelMessage(createMessage(if (auto) "AUTO_DRAW" else "PLAYER_DRAWS", username, card))
                sendMessage(if(auto) "Bot draws - $username: $card" else "$username: $card")

                if (!auto) {
                    for (currentHand in currentRoundHands.values) {
                        if (currentHand.card == null) {
                            return
                        }
                    }
                    if (log.isDebugEnabled)
                        log.debug("Looks like everyone has drawn. Let's tally!")
                    if (nextDrawTimerTask != null && !nextDrawTimerTask!!.isDone() && !nextDrawTimerTask!!.isCancelled()) {
                        nextDrawTimerTask!!.cancel(true)
                    }
                    tallyDraws()
                }
            } else {
                if (!auto)
//                    sendMessage(createMessage("ALREADY_DRAWN", username), username)
                    sendPvtMessage(username, "$username: you already drew.")
                else
                    log.warn("Auto draw requested for player: $username. But they already seem to have drawn a card!")
            }
        }

    }

    private fun removePlayerFromPot(username: String) {
        if (log.isDebugEnabled)
            log.debug("Player lost: $username. Removing from pot.")

        if (pot != null) {
            try {
                pot?.removePlayer(username)
            } catch (e: Exception) {
                log.error("BotInstanceID: $instanceID]: Error removing player $username] from pot.", e)
            }

        }
    }

    private fun resetHands(currentRoundHands: HashMap<String, Hand>) {
        val iterator = currentRoundHands.keys.iterator()

        while (iterator.hasNext()) {
            val hand = currentRoundHands[iterator.next()]
            hand?.card = null
        }
    }


    private fun checkJoinPotParameter(parameter: String, username: String): Boolean {
        var isAmountValid = false
        try {
            val amount = java.lang.Double.parseDouble(parameter)
            if (amount >= amountJoinPot) {
                amountJoinPot = amount
                isAmountValid = true
            } else {
                val message = "PLAYER: $parameter invalid. Game not started."
                if (log.isDebugEnabled())
                    log.debug("Lower value specified for $AMOUNT_JOIN_POT: $parameter")
//                sendMessage(botData.displayName, room, message)
                sendPvtMessage(botData.displayName, username, room, message)
            }

            if (log.isDebugEnabled())
                log.debug("Parameter defined : $AMOUNT_JOIN_POT=$amountJoinPot")
        } catch (e: Exception) {
            val message = "PLAYER: $parameter invalid. Game not started."
            sendMessage(botData.displayName, room, message)

        }

        return isAmountValid
    }

    private fun processNoMessage(username: String) {
        var message: String? = null
        when (getGameState().value()) {
            BotData.BotStateEnum.ID_GAME_STARTING -> if (username == gameStarter && amountJoinPot > 0.0) {
//                revertLimitInCache(gameStarter)
                setGameState(BotData.BotStateEnum.NO_GAME)
                amountJoinPot = AMOUNT_JOIN_POT_VALUE // reset to default
                gameStarter = null
                message = "$username: You were not charged."
            } else
                message = "$username: Invalid command."

            else -> message = "$username: Invalid command."
        }
        sendPvtMessage(username, message)
    }

    @Synchronized
    private fun setGameState(gameState: BotData.BotStateEnum) {
        this.gameState = gameState
    }

    protected fun revertLimitInCache(players: Collection<String>?) {
        if (players != null) {
            for (username in players) {
                revertLimitInCache(username)
            }
        }
    }

    protected fun revertLimitInCache(username: String?) {
        if (username != null) {
//            val limit = MemCachedClientWrapper.get(CommonKeySpace.MERCHANT_GAME_LIMIT, username) as LimitTracker
//            if (limit != null && !limit!!.hasExpired(Calendar.getInstance().timeInMillis)) {
//                val amount = limit!!.revert(instanceID)
//                log.debug("Reverting $amount off limit for user: $username")
//                MemCachedClientWrapper.set(CommonKeySpace.MERCHANT_GAME_LIMIT, username, limit)
//            }
            //todo: revert credit
            revert(username, amountJoinPot)
        }
    }

    private val historyCreditStore: HistoryCreditStore by inject()

    override fun revert(username: String, amountJoinPot: Double) {
        try {
            creditCache.addRegularBalance(username, amountJoinPot)
            creditCache.addLogTransaction(username,"REVERT",amountJoinPot,"USD","Revert from ${botData.displayName}")
            historyCreditStore.addHistory(username,amountJoinPot.toString(), History.TYPE_GAME_REFUND,"Revert amount USD $amountJoinPot from games ${getBotName()}")
            logger.debug("revert credit $username = $amountJoinPot")
        }catch (e: Exception){
            logger.error("error reverting ", e)
        }
    }

    @Synchronized
    private fun getGameState(): BotData.BotStateEnum {
        return gameState
    }

    override fun runner(botData: BotData) {
    }

    override fun stopBot(from: String) {
        if (log.isDebugEnabled)
            log.debug("Stopping bot instanceID[$instanceID]")

        if (nextDrawTimerTask != null && !nextDrawTimerTask!!.isDone() && !nextDrawTimerTask!!.isCancelled()) {
            nextDrawTimerTask!!.cancel(true)
        }

        if (pot != null) {
            log.debug("Expiring pot  for bot instanceID[" + instanceID + "]")
            try {
                pot?.cancel()
                sendMessage("Sorry, the game has been canceled. Don't worry, your credit has been returned")
            } catch (e: Exception) {
                log.error("Error canceling pot, botInstanceID[" + instanceID + "]")
            }

        }else{
            if(playerHands.keys.isNotEmpty())
                resetGame(true)
        }
        setGameState(BotData.BotStateEnum.NO_GAME)

        log.debug("Stopped bot instanceID[$instanceID]")
        sendMessage("bot ${botData.displayName} has been stopped by $from")
    }


    inner class Hand(val player: String) : Comparable<Any> {

        /**
         * @return the card
         */
        /**
         * @param card the card to set
         */
        var card: Card? = null            // card recently drawn

        override operator fun compareTo(obj: Any): Int {
            val compareHand = obj as Hand
            return this.card!!.compareTo(compareHand.card!!)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj == null || obj !is Hand) {
                return false
            }
            val compareHand = obj as Hand?
            return this.player == compareHand!!.player && this.card!!.equals(compareHand.card)
        }
    }

    internal inner class Deck {
        private var deck: MutableList<Card>? = null   // A list of 52 Cards, representing the deck

        init {
            deck = Card.newShuffledDeck().toMutableList()
        }

        @JvmOverloads
        fun dealCard(cardToDeal: String = ""): Card {
            var cardToDeal = cardToDeal
            if (deck!!.isEmpty()) {
                deck = Card.newShuffledDeck().toMutableList()
                log.warn("Should not be happening! Deck ran out in the middle of a round. Resetting deck...")
            }

            // If specified a card to deal, return it if it is in the deck
            cardToDeal = cardToDeal.trim { it <= ' ' }

            if (cardToDeal.length == 2) {
                val rank = Card.Rank.fromChar(cardToDeal[0])
                val suit = Card.Suit.fromChar(cardToDeal.toUpperCase()[1])
                if (rank != null && suit != null) {
                    val i = deck!!.iterator()
                    while (i.hasNext()) {
                        val card = i.next()
                        if (card.rank() === rank && card.suit() === suit) {
                            i.remove()
                            return card
                        }
                    }
                }
            }

            // Return first card in the deck
            return deck!!.removeAt(0)
        }
    }

}
