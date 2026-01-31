package net.miggers.botgames.dice

import net.miggers.Constant
import net.miggers.botgames.common.Bot
import net.miggers.botgames.common.Pot
import net.miggers.botgames.data.BotData
import net.miggers.data.cache.CreditCache
import net.miggers.data.cache.HistoryCreditStore
import net.miggers.data.cache.LevelCache
import net.miggers.data.model.History
import net.miggers.packet.MessageRoomPacket
import net.miggers.packet.MessageType
import org.koin.core.KoinComponent
import org.koin.core.inject
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DiceBot(val diceExecutor: ScheduledExecutorService, val botData: BotData, val room: String, val userStarted: String) :
    Bot(botData,diceExecutor,room,userStarted), KoinComponent {

    internal val log = LoggerFactory.getLogger("Dice")
    private val creditCache:CreditCache by inject()
    private val levelCache: LevelCache by inject()

    companion object{
        val EMOTICON_HOTKEY_DICE_PREFIX = "d"
    }

    // Parameter names
    val TIMER_JOIN_GAME = "timerJoinGame"            // Parameter name for time allowed to join a game (in seconds)
    val TIMER_CHARGE_CONF = "timerChargeConfirm"    // Parameter name for time allowed to confirm a charge (in seconds)
    val TIMER_ROLL = "timerRoll"                    // Parameter defining time given to all players to roll the dice,
    val TIMER_NEW_ROUND_INTERVAL = "timerNewRound"    // Parameter defining time before new round begins (seconds)
    val TIMER_IDLE =
        "timerIdle"                    // Parameter defining time allowed for this bot to be idle, before marking it to be killed (in minutes)
    val AMOUNT_JOIN_POT =
        "amountJoinPot"            // Parameter defining amount charged to enter the pot (this is different from a join fee (amountJoinGame), which does not become part of a pot/winnings)

    // Default parameter values
    val TIMER_ROLL_VALUE: Long = 10
    val TIMER_NEW_ROUND_VALUE: Long = 3
    val AMOUNT_JOIN_POT_VALUE = 0.5
    val IDLE_TIME_VALUE: Long =
        3    // maximum number of minutes bot can be inactive, after which it is considered "idle"

    // Parameters
    internal var timeToJoinGame: Long = 30
    internal var timeToConfirmCharge: Long = 5
    internal var timeToRoll: Long = 20                                                // time given to all players to roll the dice, before an auto-roll kicks in (in seconds)
    internal var timeToNewRound: Long =  3                                            // time before new round begins (seconds)
    internal var amountJoinPot = 0.5
    internal var winnings = 0.0
    var minPlayers = 2
    internal var timeAllowedToIdle: Long =
        30                                        // time allowed for this bot to be idle, before marking it to be killed (in minutes)

    // Variables to store the original default, for user-entered custom value
    internal var amountOriginalJoinPot = AMOUNT_JOIN_POT_VALUE

    // Commands
    val COMMAND_ROLL = "!r"

    // "Idle"ness
    var lastActivityTime: Date? = null        // time when this bot was last active. Used to let the BotService know if the bot has been idle for too long, so we can kill it.

    // Game variables

    private val playerDiceRolls = HashMap<String, DiceRoll>()    // list of players and their pair of dice
    private val safePlayers =
        HashMap<Int, MutableSet<String>>()    //  round number mapped to list of players who have immunity in a given round

    private var gameState = BotData.BotStateEnum.NO_GAME

    internal var botDice: DiceRoll? = DiceRoll()
    internal var hasWinner =
        false        // track in each round if there was anyone who matched or beat the bot's roll of dice.
    internal var currentRoundNumber = 0        // current round of the game
    internal var numPlayed = 0                // number of players who have taken their turn to roll in the round
    internal var isRoundStarted = false

    var nextRollTimerTask: ScheduledFuture<*>? = null    // hold the reference to the scheduled timer task so it can be canceled if needed


    init {
        sendMessage(getBotName(),room,"Bot ${getBotName()} added to room by $userStarted.")
        val message =
            if (amountJoinPot > 0.0) "Play now: !start to enter. Cost: USD $amountJoinPot. For custom entry, !start <entry_amount>" else "Play Dice: !start. Need $minPlayers players."
        sendMessage(botData.displayName, room, message)
        updateLastActivityTime()

    }

    @Synchronized
    private fun updateLastActivityTime() {
        lastActivityTime = Date()
    }


    override fun runner(botData: BotData) {
        println("run dice bot service")

    }

    @Synchronized
    override fun onMessage(messageRoomPacket: MessageRoomPacket) {

        if(!messageRoomPacket.message.isNotEmpty()){
            return
        }

        synchronized(this){
//            println("read from dice bot " + messageRoomPacket.toJson())
            val message = messageRoomPacket.message

            if(messageRoomPacket.type == MessageType.TYPE_ROOM){
                if(messageRoomPacket.message.contains("has entered")){
                    val split = messageRoomPacket.message.split(" ")
                    sendPvtMessage(botData.displayName,split[0], room, "[PVT] ${botData.displayName} is Running! !start to start a game. !j to join game. Amount = 0.05 usd")
                }
            }

            if(message.startsWith("!start")){
                start(messageRoomPacket.from, message)
            }
            if(messageRoomPacket.message == "!j"){
                join(messageRoomPacket.from)
            }
            if(messageRoomPacket.message == "!r"){
                if (gameState === BotData.BotStateEnum.PLAYING && isRoundStarted) {
                    if (!playerDiceRolls.containsKey(messageRoomPacket.from)) {
//                        sendMessage(createMessage("NOT_IN_GAME", username), username)
//                        sendMessage(botData.displayName, room, "${packet.from}: you're not in the game.")
                        sendPvtMessage(botData.displayName, messageRoomPacket.from , room, "${messageRoomPacket.from}: you're not in the game.")
                    } else {
                        if (log.isDebugEnabled)
                            log.debug("botInstanceID: ${messageRoomPacket.from} rolls")
                        roll(messageRoomPacket.from, false)
                    }
                } else {
//                    sendMessage(createMessage("INVALID_COMMAND", username), username)
//                    sendMessage(botData.displayName, room, "${packet.from}: Invalid command.")
                    sendPvtMessage(botData.displayName, messageRoomPacket.from, room, "${messageRoomPacket.from}: Invalid command.")
                }
            }

        //end syncronized
        }

    }

    private fun join(username: String) {
        if (!playerDiceRolls.containsKey(username)) {
            if (gameState === BotData.BotStateEnum.GAME_JOINING)
                addPlayer(username)
            else if (gameState === BotData.BotStateEnum.PLAYING) {
//                sendMessage(createMessage("JOIN_ENDED", username), username)
//                sendMessage(botData.displayName, room, "$username: Sorry, a game has already started.")
                sendPvtMessage(botData.displayName, username ,room, "$username: Sorry, a game has already started.")
            } else {
//                sendMessage(createMessage("INVALID_COMMAND", username), username)
//                sendMessage(botData.displayName, room, "$username: Invalid command.")
                sendPvtMessage(botData.displayName, username, room, "$username: Invalid command.")
            }
        } else {
//            sendMessage(createMessage("ALREADY_IN_GAME", username), username)
//            sendMessage(botData.displayName, room, "$username: You're already in the game.")
            sendPvtMessage(botData.displayName, username, room, "$username: You're already in the game.")
        }
    }

    fun start(username: String, messageText: String){
        if (getGameState() === BotData.BotStateEnum.NO_GAME) {
            if (messageText.trim { it <= ' ' }.length > "!start".length) {
                val parameter = messageText.trim { it <= ' ' }.substring("!start".length + 1)
                if (parameter.isNotEmpty()) {
                    if (checkJoinPotParameter(parameter, username)) {
                        try {
                            startGame(username)
                        } catch (e: Exception) {
                            log.error("Error starting game with custom amount. Command was : '$messageText'", e)
                            sendPvtMessage(botData.displayName,username, room, "Error starting game with custom amount.")
                        }

                    }
                }
            } else {
                try {
                    startGame(username)
                } catch (e: Exception) {
                    log.error("Error starting game with default amount: ", e)
                    sendPvtMessage(botData.displayName,username, room, "Error starting game with default amount:")
                }

            }
        } else {
            // someone has already started a game
            sendGameCannotStartMessage(username)
        }

    }

    @Throws(java.lang.Exception::class)
    private fun startGame(username: String) {
        updateLastActivityTime()
        if (gameState.equals(BotData.BotStateEnum.NO_GAME)) {
            if (amountJoinPot > 0.0) {
                val hasFunds = userCanAffordToEnterPotStart(username, room,amountJoinPot)
//                val hasFunds = true
                if (hasFunds) {
                    val message = StringBuilder("$username: Charges apply. USD $amountJoinPot Create/enter pot.")
                    message.append(" !n to cancel. $timeToConfirmCharge seconds")
                    setGameState(BotData.BotStateEnum.GAME_STARTING)
//                    sendMessage(botData.displayName, room, message.toString())
                    sendPvtMessage(botData.displayName, username, room, message.toString())
                    gameStarter = username

                    if (log.isDebugEnabled)
                        log.debug("DiceBot: starting timer for StartGame()")
                    diceExecutor.schedule(StartGame(this), timeToConfirmCharge, TimeUnit.SECONDS)
                    if (log.isDebugEnabled)
                        log.debug("DiceBot: started timer for StartGame()")
                } else {
                    //todo: cancel start game
                    resetGame(false)
                    //sendMessage(createMessage("INSUFFICIENT_FUNDS_POT", username), username);
                }
            } else {
                if (log.isDebugEnabled)
                    log.debug("botInstanceID: No charges. Game started by user[" + username + "]")
                setGameState(BotData.BotStateEnum.GAME_STARTING)
                gameStarter = username

                if (log.isDebugEnabled)
                    log.debug("DiceBot: starting timer for StartGame()")
                diceExecutor.execute(StartGame(this))
                if (log.isDebugEnabled)
                    log.debug("DiceBot: started timer for StartGame()")
            }
        } else {
            // should not get here if we already handle it in onMessage(). But just in case
            sendGameCannotStartMessage(username)
        }

    }

    internal inner class StartGame(var bot: DiceBot) : Runnable {

        override fun run() {
            if (log.isDebugEnabled)
                log.debug("botInstanceID: in StartGame() ")

            var gameState: BotData.BotStateEnum? = null

            gameState = getGameState()

            if (gameState === BotData.BotStateEnum.GAME_STARTING) {
                setGameState(BotData.BotStateEnum.GAME_STARTED)
                addPlayer(gameStarter!!)
                    if (timeToJoinGame > 0) {        // schedule the new game to start after a pre-determined delay
                        setGameState(BotData.BotStateEnum.GAME_JOINING)
//                    sendChannelMessage(if (amountJoinPot > 0.0) "Dice started. !j to join. Cost USD $amountJoinPot. $timeToJoinGame seconds" else "Dice started. !j to join. $timeToJoinGame seconds")
                        val str = (if (amountJoinPot > 0.0) "Dice started by $gameStarter. !j to join. Cost USD $amountJoinPot. $timeToJoinGame seconds" else "Dice started. !j to join. $timeToJoinGame seconds")
                        sendMessage(botData.displayName, room, str)

                        if (log.isDebugEnabled)
                            log.debug("DiceBot: starting timer for StartPlay()")
                        diceExecutor.schedule(StartPlay(bot), timeToJoinGame, TimeUnit.SECONDS)
                        if (log.isDebugEnabled)
                            log.debug("botInstanceID: scheduled to start play. Awaiting join.. ")
                    }


            }

        }
    }

    fun getPlayers(): Map<String, DiceRoll> {
        return playerDiceRolls
    }

    internal inner class StartPlay(var bot: DiceBot) : Runnable {

        override fun run() {
            try {
                if (log.isDebugEnabled)
                    log.debug("DiceBot: starting play in StartPlay()")
                var gameState = getGameState()

                if (gameState === BotData.BotStateEnum.GAME_JOINING) {
                    setGameState(BotData.BotStateEnum.GAME_JOIN_ENDED)

                    // If not enough players end the game.
                    if (getPlayers().size < minPlayers) {
//                        sendChannelMessage(createMessage("JOIN_NO_MIN"))
                        sendMessage(botData.displayName, room, "Joining ends. Not enough players. Need $minPlayers.")
                        if (log.isDebugEnabled)
                            log.debug("botInstanceID: Join ended. Not enough players.")
                        resetGame(true)
                    } else {

                        val copyOfPlayers = HashSet<String>()
                        copyOfPlayers.addAll(playerDiceRolls.keys)

                        // If the check was successful, time to charge the players for real
                        if (gameState !== BotData.BotStateEnum.NO_GAME) {
                            gameState = BotData.BotStateEnum.PLAYING
                            try {
                                if (amountJoinPot > 0.0) {
                                    bot.pot = Pot(bot)
                                    log.debug("Pot id[" + pot.hashCode() + "] created for bot instanceID")
                                    for (player in copyOfPlayers) {
                                        try {
                                            pot?.enterPlayer(player, amountJoinPot, "USD")
                                            if (log.isDebugEnabled)
                                                log.debug("botInstanceID: Entered into pot " + player + " = " + "USD" + " " + amountJoinPot)
                                        } catch (e: Exception) {
                                            playerDiceRolls.remove(player)
                                            log.warn(
                                                "botInstanceID]: Error charging player[" + player + "]",
                                                e
                                            )
//                                            sendMessage(createMessage("INSUFFICIENT_FUNDS_POT", player), player)
                                            sendPvtMessage(botData.displayName,player, room, "$player, You do not have sufficient credit to start a game")
                                        }

                                    }
                                    // If, after charging, there are not enough players, cancel the game
                                    if (playerDiceRolls.size < minPlayers) {
                                        if (log.isDebugEnabled)
                                            log.debug("botInstanceID: Not enough valid players.")
                                        //cancelPot()
                                        resetGame(false)
                                        return
                                    }
                                }

                                // LEADERBOARD: Increment Games Played counter
                                //incrementGamesPlayed(Leaderboard.Type.DICE_GAMES_PLAYED, playerDiceRolls.keys)

                                // Log info
                                //logGamesPlayed(playerDiceRolls.size, playerDiceRolls.keys, amountJoinPot)

                                // Officially starting play
//                                sendChannelMessage(createMessage("GAME_STARTED_NOTE"))
                                sendMessage(botData.displayName, room, "Game begins! Bot rolls first - match or beat total to stay IN")
                                setGameState(BotData.BotStateEnum.PLAYING)
                                newRound()

                            } catch (e: Exception) {
                                log.error("Error creating pot for botInstanceID[].", e)
                                setGameState(BotData.BotStateEnum.NO_GAME)
//                                sendChannelMessage(createMessage("GAME_CANCELED"))
                                sendMessage(botData.displayName, room, "Billing error. Game canceled. No charges")
                            }

                        } else {
                            //cancelPot()
                            resetGame(false)
                            if (log.isDebugEnabled)
                                log.debug("botInstanceID[]: Billing error. Game canceled. No charges.")
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Unexpected exception caught in startPlay.run()", e)
                //cancelPot()
                resetGame(false)
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
                log.error("Error canceling pot for botInstanceID.", e)
            }

//            sendChannelMessage(createMessage("GAME_CANCELED"))
            sendMessage(botData.displayName, room, "Billing error. Game canceled. No charges")
        }

    }

    private fun newRound() {
        isRoundStarted = true
        currentRoundNumber++
        hasWinner = false    // player list has atleast 1 person who matched/beat the bot's total
        resetDice()
        numPlayed = 0        // reset

        botDice?.roll()
//        sendChannelMessage(createMessage("BOT_ROLLED", null, botDice))
//        sendChannelMessage(createMessage("PLAYERS_TURN"))

        val list = mutableListOf<String>()
        val iterator = playerDiceRolls.keys.iterator()
        while (iterator.hasNext()){
            list.add(iterator.next())
        }
        val string = list.joinToString(", ")
        sendMessage(botData.displayName,room, "Players: $string")
        sendMessage(botData.displayName, room, "ROUND $currentRoundNumber: Bot rolled ${botDice.toString()}. Your TARGET: ${botDice?.total()}!")
        sendMessage(botData.displayName, room, "Players: !r to roll. $timeToRoll seconds. ")

        diceExecutor.schedule(TimedPickWinnerTask(this, currentRoundNumber), timeToRoll, TimeUnit.SECONDS)
    }

    internal inner class TimedPickWinnerTask(var bot: DiceBot, var roundNumber: Int) : Runnable {

        override fun run() {
            synchronized(bot) {
                if (bot.getGameState() === BotData.BotStateEnum.PLAYING && bot.currentRoundNumber == roundNumber) {
//                    bot.sendChannelMessage(bot.createMessage("TIME_UP"))
                    bot.sendMessage(botData.displayName,room,"TIME'S UP! Tallying rolls... ")
                    bot.tallyRolls()
                }
            }
        }
    }

    private fun tallyRolls() {
        // First go through and auto-roll the dice for the lazy players.
        // Also check if everyone lost, because then they all go one more round. If there is atleast one person who matched/beat the bot, they will be the winner.
        val losers = ArrayList<String>()
        val iterator = playerDiceRolls.keys.iterator()

        while (iterator.hasNext()) {
            val player = iterator.next()
            val dice = playerDiceRolls[player]
            if (dice?.total() == 0) {
                roll(player, true)
                if (log.isDebugEnabled)
                    log.debug("Bot rolls for :" + player + ": " + dice.toString())
            }

            // mark if there is atleast one winner
            if (dice?.isWinner()!! && !hasWinner)
                hasWinner = true

            // Add player to loser list, if in the end we need to remove them
            if (!dice.isWinner()) {
                if (!isSafePlayer(currentRoundNumber, player))
                    losers.add(player)
                else {
                    if (log.isDebugEnabled)
                        log.debug("Skipping removal of player " + player + "because they have immunity")
                }
            }
        }

        if (hasWinner) {
            if (log.isDebugEnabled) {
                log.debug("Safe players: ")
                showSafePlayers(currentRoundNumber)
                log.debug("Next round safe players: ")
                showSafePlayers(currentRoundNumber + 1)
            }

            // Now everyone has rolled, and one or more have won the round. It's time to remove the losers.

            // Remove the losers now
            for (player in losers) {
                playerDiceRolls.remove(player)
                //removePlayerFromPot(player)
//                sendMessage(createMessage("PLAYER_LOST", player), player)
//                sendMessage(botData.displayName, room, "$player: sorry you LOST!")
                sendPvtMessage(botData.displayName, player, room, "$player: sorry you LOST!")

                if (log.isDebugEnabled)
                    log.debug("botInstanceID: " + "Removed user: " + player)
            }


        }
        removeSafeList(currentRoundNumber)
        if (log.isDebugEnabled)
            log.debug("botInstanceID: " + "Players remaining: " + playerDiceRolls.size)


        // Start a new round
        if (playerDiceRolls.size > 1) {
            if (log.isDebugEnabled)
                log.debug("botInstanceID: " + "Players size > 1")

            if (!hasWinner) {
//                sendChannelMessage(createMessage("ALL_LOST_PLAY_AGAIN"))
                sendMessage(botData.displayName, room, "Nobody won, so we'll try again!")
            }
            isRoundStarted = false
//            sendChannelMessage(createMessage("NEXT_ROUND"))
            val nextRound = currentRoundNumber + 1
            sendMessage(botData.displayName, room, "Players, get ready for round $nextRound!")

            diceExecutor.schedule(Runnable { newRound() }, timeToNewRound, TimeUnit.SECONDS)

            if (log.isDebugEnabled)
                log.debug(" " + "Started timer for a new round")
        } else if (playerDiceRolls.size == 1) { // should not be 0
            if (log.isDebugEnabled)
                log.debug("botInstanceID: " + "Players size = 1")
            pickWinner()
        }
    }

    private fun pickWinner() {
        if (log.isDebugEnabled)
            log.debug("botInstanceID: " + "Picking winner: ")
        val iterator = playerDiceRolls.keys.iterator()
        endGame(iterator.next())
    }

    @Synchronized
    private fun endGame(winner: String) {

        try {
            if (getGameState() !== BotData.BotStateEnum.PLAYING)
                return

            if (nextRollTimerTask != null && !nextRollTimerTask!!.isDone() && !nextRollTimerTask!!.isCancelled()) {
                log.debug("botInstanceID: Pending timer task to cancel in endGame() ")
                nextRollTimerTask?.cancel(true)
            }

            val localPot = pot
            if (localPot != null) {
                try {
                    //winnings = localPot!!.payout(true)
                    // Use the AccountBean to convert to base currency
                    //val accountEJB = EJBHomeCache.getObject(AccountHome.JNDI_NAME, AccountHome::class.java) as Account
                    //winnings = accountEJB.convertCurrency(winnings, BASE_CURRENCY, CURRENCY)
                    localPot.payout(winner, localPot.printTotalAmount().toDouble())
                    log.debug("Game over. Pot payout completed to $winner = ${localPot.printTotalAmount()}.")
                    pot = null
                } catch (e: Exception) {
                    log.error("Game over. Error in pot [] payout.", e)
                    //sendChannelMessageAndPopUp(SystemProperty.get(SystemPropertyEntities.BOT.PAYOUT_FAILURE_MESSAGE))
                    return
                }
            }

            sendMessage(botData.displayName, room, if (amountJoinPot > 0.0) "Game over! $winner WINS USD ${localPot?.printTotalAmount()}!! CONGRATS!" else "Game over! $winner wins!! CONGRATS!")
            levelCache.addExp(winner,Constant.exp_win)
//            sendChannelMessageAndPopUp(
//                if (amountJoinPot > 0.0) createMessage(
//                    "GAME_OVER_PAID",
//                    winner
//                ) else createMessage("GAME_OVER_FREE", winner)
//            )
//
//            // Log info
//            logMostWins(winner, winnings)
//
//            // LEADERBOARD: Increment Most Wins counter
//            incrementMostWins(Leaderboard.Type.DICE_MOST_WINS, winner)
        } catch (e: Exception) {
            log.error("botInstanceID[]: Error getting game winner. ", e)
        } finally {
            resetGame(false)
            updateLastActivityTime()
//            sendChannelMessage(if (amountJoinPot > 0.0) createMessage("GAME_STATE_DEFAULT_AMOUNT") else createMessage("GAME_STATE_DEFAULT_NO_AMOUNT"))
            sendMessage(botData.displayName, room, if (amountJoinPot > 0.0) "Play now: !start to enter. Cost: USD $amountJoinPot. For custom entry, !start <entry_amount> " else "Play Dice: !start. Need $minPlayers players.")
        }
    }

    private fun roll(username: String, auto: Boolean) {
        val dice = playerDiceRolls[username]

        if (dice?.total() == 0) {

            dice.rollAndMatch(botDice?.total()!!)

            if (dice.total() == botDice?.total()) {        // MATCH
//                sendChannelMessage(createMessage(if (auto) "AUTO_ROLL_MATCH" else "PLAYER_ROLLS_MATCH", username, dice))
                sendMessage(botData.displayName, room,  (if (auto) "Bot rolls - $username: $dice IN!" else "$username: $dice IN!"))
                if (!hasWinner)
                // maybe a redundant check, but why set if it is already set?
                    hasWinner = true
            } else if (dice.total() > botDice?.total()!!) {    // HIGHER
//                sendChannelMessage(
//                    createMessage(
//                        if (auto) "AUTO_ROLL_HIGHER" else "PLAYER_ROLLS_HIGHER",
//                        username,
//                        dice
//                    )
//                )
                sendMessage(botData.displayName, room, if (auto) "Bot rolls - $username: $dice IN!" else "$username: $dice IN!" )
                if (!hasWinner)
                // maybe a redundant check, but why set if it is already set?
                    hasWinner = true
                if (dice.total() == 12) {
                    addSafePlayer(currentRoundNumber + 1, username)
//                    sendChannelMessage(createMessage("IMMUNITY", username, dice))
                    sendMessage(botData.displayName, room, "$username: $dice = immunity for the next round!")
                }
            } else {                                        // LOWER
                if (isSafePlayer(currentRoundNumber, username)) {
//                    sendChannelMessage(createMessage("SAFE_BY_IMMUNITY", username, dice))
                    sendMessage(botData.displayName, room, "$username: $dice OUT but SAFE by immunity!")
                } else {
//                    sendChannelMessage(createMessage(if (auto) "AUTO_ROLL_OUT" else "PLAYER_ROLLS_OUT", username, dice))
                    sendMessage(botData.displayName, room, if (auto) "Bot rolls - $username: $dice OUT! " else "$username: $dice OUT!")
                }
            }
            if (!auto) {
                if (++numPlayed >= playerDiceRolls.size) { // "greater than" comparison needed to account for anyone leaving in the middle of the round
                    if (log.isDebugEnabled)
                        log.debug("Looks like everyone has rolled. Let's tally!")
                    nextRollTimerTask?.let {
                        if (!it.isDone() && !it.isCancelled()) {
                            it.cancel(true)
                        }
                    }


                    tallyRolls()
                }
            }
        } else {
            if (!auto)
//                sendMessage(createMessage("ALREADY_ROLLED", username), username)
//                sendMessage(botData.displayName, room, "$username: you already rolled.")
                sendPvtMessage(botData.displayName, username, room, "$username: you already rolled.")
            else
                log.warn("Auto roll requested for player: $username. But they already seem to have rolled!")
        }

    }



    private fun addSafePlayer(roundNumber: Int, username: String) {
        var players: MutableSet<String>? = safePlayers[roundNumber]
        if (players == null) {
            players = HashSet()
            safePlayers[roundNumber] = players
        }
        players.add(username)
    }

    private fun isSafePlayer(roundNumber: Int, username: String): Boolean {
        var isSafe = false
        val players = safePlayers[roundNumber]
        if (players != null) {
            isSafe = players.contains(username)
        }
        return isSafe
    }

    private fun removeSafeList(roundNumber: Int) {
        safePlayers.remove(roundNumber)
    }

    private fun showSafePlayers(roundNumber: Int) {
        val players = safePlayers[roundNumber]
        if (players != null) {
            for (player in players) {
                log.debug("$player ")
            }
        }
    }


    private fun resetDice() {
        botDice?.reset()
        for (dice in playerDiceRolls.values) {
            dice.reset()
        }
    }

    fun addPlayer(username: String) : Boolean {
        if (getGameState() === BotData.BotStateEnum.GAME_STARTED || getGameState() === BotData.BotStateEnum.GAME_JOINING) {
            if (amountJoinPot > 0.0) {
                val hasFunds = userCanAffordToEnterPot(username, room, amountJoinPot)
                if (!hasFunds) {
//                    sendMessage(createMessage("INSUFFICIENT_FUNDS_POT", username), username);
//                    sendPvtMessage(botData.displayName,username,room, "$username, You do not have sufficient credit to start a game")
                    return false
                }
            }

            synchronized(playerDiceRolls) {
                if (!playerDiceRolls.containsKey(username)) {
                    playerDiceRolls[username] = DiceRoll()
                }
            }
            val message = StringBuilder()
            message.append("$username: added to game.")

            if (amountJoinPot > 0.0) {
                message.append("Charges apply. USD $amountJoinPot")
            }

            log.info("$username joined the game")

//            sendMessage(message.toString(), username)
            ///sendMessage(botData.displayName, room, message.toString())
            sendPvtMessage(botData.displayName,username,room,message.toString())
            if (username != gameStarter) {
//                sendChannelMessage("PLAYER joined the game.")
                sendMessage(botData.displayName, room, "$username joined the game.")
                //add exp level after joining game
                levelCache.addExp(username,Constant.exp_game)

            }

        }

        return true
    }

    fun revertLimitInCache(players: Collection<String>?) {
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
            historyCreditStore.addHistory(username,amountJoinPot.toString(), History.TYPE_GAME_REFUND,"Revert amount USD $amountJoinPot from games ${getBotName()}")
            logger.debug("revert credit $username = $amountJoinPot")
        }catch (e: Exception){
            logger.error("error reverting ", e)
        }
    }

    fun resetGame(cancel: Boolean) {
        if (cancel) {
            revertLimitInCache(playerDiceRolls.keys)
        }
        playerDiceRolls.clear()
        safePlayers.clear()
        nextRollTimerTask = null
        currentRoundNumber = 0
        isRoundStarted = false
        hasWinner = false
        numPlayed = 0
        gameStarter = null
        botDice?.reset()
        pot = null
        amountJoinPot = amountOriginalJoinPot // reset to original config parameter
        setGameState(BotData.BotStateEnum.NO_GAME)
    }

    private fun sendGameCannotStartMessage(username: String) {
        var message: String? = null
        when (gameState.value()) {
            BotData.BotStateEnum.ID_GAME_STARTING, BotData.BotStateEnum.ID_GAME_STARTED, BotData.BotStateEnum.ID_PLAYING -> message =
                "A game is currently on."
            BotData.BotStateEnum.ID_GAME_JOINING -> message = "A game is on. !j to join. Charges may apply."
            else -> message = "Sorry, new game cannot be started now."
        }
//        sendMessage(message, username)
//        sendMessage(botData.displayName, room, message)
        sendPvtMessage(botData.displayName, username, room, message)
    }

    @Synchronized
    private fun setGameState(gameState: BotData.BotStateEnum) {
        this.gameState = gameState
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

    @Synchronized
    private fun getGameState(): BotData.BotStateEnum {
        return gameState
    }

    private fun joinGame(from: String, room: String) {
        synchronized(this){
            if(this.room != room){
                sendPvtMessage("GameBot", from ,room,"bot is not currenty add in $room chat room.")
                return
            }

            sendMessage(getBotName(), room,"$from joined to game")
        }

    }

    override fun stopBot(from: String) {
        if (log.isDebugEnabled)
            log.debug("Stopping bot instanceID")

        nextRollTimerTask?.let {
            if (!it.isDone() && !it.isCancelled()) {
                it.cancel(true)
            }
        }


        if (pot != null) {
            log.debug("Expiring pot for bot instanceID")
            try {
                pot?.cancel()
                sendMessage("Sorry, the game has been canceled. Don't worry, your credit has been returned")
            } catch (e: Exception) {
                log.error("Error canceling pot, botInstanceID")
            }

        }
        setGameState(BotData.BotStateEnum.NO_GAME)

        log.debug("Stopped bot instanceID ${getBotName()}")
        sendMessage("bot ${botData.displayName} has been stopped by $from")
    }
}
