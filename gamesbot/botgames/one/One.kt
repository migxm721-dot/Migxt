package net.miggers.botgames.one

import net.miggers.botgames.common.Bot
import net.miggers.botgames.common.Pot
import net.miggers.botgames.data.BotData
import net.miggers.botgames.data.BotData.BotStateEnum
import net.miggers.packet.MessageRoomPacket
import net.miggers.packet.MessageType
import org.slf4j.LoggerFactory
import java.lang.StringBuilder
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class One(val botData: BotData, val botOneExecutor: ScheduledExecutorService, val room:String,val userStarted: String) : Bot(botData, botOneExecutor,room, userStarted) {

    internal val log = LoggerFactory.getLogger("OneBot")

    // Parameter names
    val TIMER_JOIN_GAME = "timerJoinGame" // Parameter name for time allowed to join a game (in seconds)

    val TIMER_IDLE =
        "timerIdle" // Parameter defining time allowed for this bot to be idle, before marking it to be killed (in minutes)

    val MIN_POT_ENTRY = "minPotEntry"

    // Default parameter values
    val IDLE_TIME_VALUE: Long = 5 // maximum number of minutes bot can be inactive, after which it is considered "idle"




    // Parameters
    var timeToJoinGame: Long = 30
    var timeAllowedToIdle: Long =
        30 // time allowed for this bot to be idle, before marking it to be killed (in minutes)


    // "Idle"ness
    var lastActivityTime // time when this bot was last active. Used to let the BotService know if the bot has been idle for too long, so we can kill it.
            : Date? = null

    companion object {

        val MIN_PLAYERS = 2 // minimum number of players

        val MAX_PLAYERS = 4 // maximum number of players


        val BLUE = 1
        val GREEN = 2
        val RED = 3
        val YELLOW = 4

        val EMOTICON_MARKER = "|"
        val EMOTICON_HOTKEY_BLUE = "(g-uno_blue)"
        val EMOTICON_HOTKEY_GREEN = "(g-uno_green)"
        val EMOTICON_HOTKEY_RED = "(g-uno_red)"
        val EMOTICON_HOTKEY_YELLOW = "(g-uno_yellow)"

        val WILD = 10 // used for wild colour and value


        val DRAW_2 = 20
        val REVERSE = 21
        val SKIP = 22
        val WILD_DRAW_4 = 23
        val ANY = 99

        val STR_ANY = "*"
        val STR_WILD = "w"
        val STR_DRAW_2 = "d2"
        val STR_REVERSE = "r"
        val STR_SKIP = "s"
        val STR_WILD_DRAW_4 = "wd4"

    }


    // Game commands
    var CMD_START = COMMAND_CHAR + "start"
    var CMD_JOIN = COMMAND_CHAR + "j"

    var CMD_DEAL = COMMAND_CHAR + "deal" // deal cards at the start of the game

    var CMD_PLAY_CARD = COMMAND_CHAR + "p" // play a card

    var CMD_DRAW = COMMAND_CHAR + "d" // draw a card

    var CMD_PASS = COMMAND_CHAR + "s" // pass the player's turn

    var CMD_HAND = COMMAND_CHAR + "h" // show the requestor's hand of cards

    var CMD_COUNT = COMMAND_CHAR + "c" // count of all players' cards

    var CMD_HELP = COMMAND_CHAR + "help" // TODO shows command help for play: play, draw, pass, hand


    private val CMD_RESET = COMMAND_CHAR + "reset"

    var playersNames: MutableMap<String, Boolean>? = null
    private var gameState: BotStateEnum = BotStateEnum.NO_GAME
    var inProgress = false
    var drawn = false

    //String gameChannel = "";
    var dealer = ""

    var nextPlayer: Player? = null

    var players: MutableList<Player>? = null
    var cards: MutableList<Card>? = null
    var cardInPlay: Card? = null
    var wildColour: Int = One.WILD
    var discardedCards: MutableList<Card>? = null
    private val minPotEntry = 0.5
    private var costToJoinGame // Will be zero if there is no pot for the current game
            = 0.00
    var df = DecimalFormat("0.00")
    private var waitForPlayersTimer: ScheduledFuture<*>? = null

    init {
        players = ArrayList()
        playersNames = HashMap()

        log.info("OneBot [" + instanceID.toString() + "] added to channel [" + room.toString() + "]")


//        sendChannelMessage(createMessage("BOT_ADDED"))
        sendMessage("Bot ${botData.displayName} added to room by $userStarted")
        val message: String = "Play One - the card game. $CMD_START to start. Cost USD $costToJoinGame"
        sendMessage(message)
        updateLastActivityTime()
    }

    @Synchronized
    private fun getGameState(): BotStateEnum? {
        return gameState
    }

    @Synchronized
    private fun setGameState(gameState: BotStateEnum) {
        this.gameState = gameState
    }

    @Synchronized
    override fun onMessage(messageRoomPacket: MessageRoomPacket) {
        // Start the game of One
        val username = messageRoomPacket.from
        var messageText = messageRoomPacket.message
        // Start the game of One
        if (messageText.startsWith(CMD_START)) {
            if (getGameState() === BotStateEnum.NO_GAME) {
                try {
                    startGame(username, messageText)
                } catch (e1: Exception) {
                    log.error("Error starting game", e1)
                }
            } else {
                sendGameCannotBeStartedMessage(username)
            }
            // Join a game of One
        } else if (messageText.equals(CMD_JOIN, ignoreCase = true)) {
            if (players!!.size < One.MAX_PLAYERS) {
                if (!playersNames!!.containsKey(username)) {
                    if (gameState === BotStateEnum.GAME_JOINING)
                        addPlayer(username)
                    else {
                        sendMessage("[PVT] $username: Sorry, a game has already started.", username)
                    }
                } else {
                    sendMessage("[PVT] $username: You are already added to game.", username)
                }
            } else {
                sendMessage("[PVT] $username: Sorry, only 4 players in a game.  Please wait for the next game.", username)
            }
            // Deal out a game of One
        } else if (messageText.equals(CMD_DEAL, ignoreCase = true)) {
            dealGame(username)
            // Reset a game of One
        } else if (messageText.equals(CMD_RESET, ignoreCase = true) && username == gameStarter) {
            reset(username)
            // Play a card of One
        } else if (messageText.toLowerCase().startsWith(CMD_PLAY_CARD) && inProgress()) {
            if (isPlayersTurn(username)) {
                playCard(username, messageText)
            } else {
                sendMessage("[PVT] $username: It is not your turn.", username)
            }
        } else if (messageText.equals(CMD_HAND, ignoreCase = true) && inProgress()) {
            sendHand(username)
        } else if (messageText.equals(CMD_COUNT, ignoreCase = true) && inProgress()) {
            count()
        } else if (messageText.toLowerCase().startsWith(CMD_DRAW) && inProgress() && isPlayersTurn(username)) {
            draw(username)
        } else if (messageText.toLowerCase().startsWith(CMD_PASS) && inProgress() && isPlayersTurn(username)) {
            pass(username)
        }else if(messageRoomPacket.type == MessageType.TYPE_ROOM){
            if(messageRoomPacket.message.contains("has entered")){
                val split = messageRoomPacket.message.split(" ")
                onUserJoinChannel(split[0])
            }else if(messageRoomPacket.message.contains("has left")){
                val split = messageRoomPacket.message.split(" ")
                onUserLeaveChannel(split[0])
            }
        }

    }

    @Synchronized
    private fun onUserLeaveChannel(username: String) {
        removePlayer(username)
    }

    private fun removePlayer(name: String) {
        val player = getPlayer(name)
            ?: // player not found
            return
        //return true;
        playersNames!!.remove(name)
        if (nextPlayer != null && nextPlayer!!.getName().equals(player.getName(),true)) {
            nextPlayer(1)
        }

//todo: remove user from pot if leave chat room on game started
//        if (pot != null) {
//            try {
//                pot!!.removePlayer(name)
//            } catch (e: Exception) {
//                log.error(
//                    "Unable to remove user " + name + " from pot " + pot.toString(),
//                    e
//                )
//            }
//        }

        discardedCards?.addAll(player.getCards()!!)
        players?.remove(player)
        if (nextPlayer != null) {
//            sendChannelMessage(createMessage("REMOVED_AND_NEXT", name, nextPlayer!!.getName()))
            sendMessage("$name has been removed from the game. ${nextPlayer?.getName()} it''s your turn now.")

        }
        // Now the player is playing alone and shouldn't be!
        // Now the player is playing alone and shouldn't be!
        if (players?.size == 1) {
            val winner =  players!![0].getName()
            val payout = endGame(false,winner)
            if (payout > 0) sendMessage(
                "No other players left so ${winner} wins USD POT_PAYOUT! \\o/  $CMD_START to start new One game".replace("POT_PAYOUT", df.format(payout))
            ) else sendMessage("No other players left so ${winner} wins! \\\\o/  $CMD_START to start new One game")
            //return false;
            // All the players disappeared for whatever reason, stop the game.
        } else if (players!!.size == 0) {
            sendMessage("All players left the room - no winner. $CMD_START to start new One game")
            endGame(true, null)
            //return false;
        }
    }

    @Synchronized
    private fun onUserJoinChannel(username: String) {
        println("user join channel = $username")
        var message: String? = null
        synchronized(gameState) {
            when (gameState.value()) {
                BotStateEnum.ID_GAME_STARTED, BotStateEnum.ID_PLAYING -> message =
                    "[PVT] $username: ''One'' game in progress... "
                else -> message = "Play One - the card game. $CMD_START to start. Cost USD $costToJoinGame"
            }
        }
        sendMessage(message!!, username)
    }

    private fun pass(sender: String) {
        if (drawn) {
            sendMessage("Counts of Cards: ")
            nextPlayer(1)
            showTopCard(room)
            drawn = false
        } else {
            sendMessage("[PVT] $sender: You have to draw first then pass", sender)
        }
    }

    private fun draw(sender: String) {
        if (drawCard(sender, 1)) {
            sendMessage("$sender took a card from the deck.")
        } else {
            noCardsLeft(room)
            drawCard(sender, 1)
            sendMessage("$sender took a card from the deck.")
        }
        drawn = true
    }

    private fun count() {
        this.countPlayersCards()
    }

    private fun countPlayersCards() {
        val res = StringBuffer("Counts of Cards: ")
        for (i in players!!.indices) {
            res.append(players!![i].getName().toString() + ": (" + players!![i].cardCount() + ") ")
        }
        sendMessage(res.toString())
    }

    private fun sendHand(player: String) {
        if (getPlayer(player) != null) sendMessage(getPlayer(player).toString(), player)
    }

    private fun playCard(sender: String, message: String) : Boolean {
        val valid = playCard(room, sender, message)
        if (valid) {
            if (getPlayer(sender)!!.hasWon()) {
                var totalScore = 0
                for (p in players!!) {
                    if (!p.getName().equals(sender,true)) {
                        totalScore += p.getPoints()
                        sendMessage(p.toString())
                    }
                }
                updateScores(sender, totalScore)
                sendMessage(
                    " $sender won SCORE points! $CMD_START to start new One game".replace(
                        "SCORE",
                        Integer.toString(totalScore)
                    )
                )
                val payout = endGame(false, sender)
                if (payout < 0) {
                    //sendChannelMessageAndPopUp(SystemProperty.get(SystemPropertyEntities.BOT.PAYOUT_FAILURE_MESSAGE))
                } else if (payout > 0) {
//                    sendChannelMessageAndPopUp(
//                        createMessage("WON_GAME_POT", sender).replace(
//                            "POT_PAYOUT",
//                            df.format(payout)
//                        )
//                    )

                    sendMessage(
                        "Game over! $sender WINS USD POT_PAYOUT. CONGRATS!".replace(
                            "POT_PAYOUT",
                            df.format(payout)
                        )
                    )
                } else {
//                    sendChannelMessageAndPopUp(createMessage("WON_GAME", sender))
                    sendMessage("$sender won the game! \\\\o/")
                }
                return true
            } else {
                if (getPlayer(sender)!!.hasUno()) sendMessage("$sender has *** ONE ***!\" w00t!")
                showTopCard(room)
                drawn = false
            }
        }
        return false

    }

    fun playCard(channel: String?, sender: String, message: String): Boolean {
        val player = getPlayer(sender)
        if (player != null) {
            //boolean endsWithUno = false;
            /*if(message.toLowerCase().endsWith("uno")) {
				endsWithUno = true;
				message = message.substring(0, message.length()-3);
			}*/
            val cardToPlay = message.toLowerCase().substring(CMD_PLAY_CARD.length).trim { it <= ' ' }
            var cardValue = -1
            var cardColour = -1
            var cardValueStr = ""
            var cardColourStr = ""
            try {
                cardValueStr = cardToPlay[2].toString() + ""
                cardColourStr = cardToPlay[0].toString() + ""
                if (cardToPlay.indexOf(One.STR_WILD_DRAW_4) != -1 && cardToPlay.length == 5) {
                    cardValue =One.WILD_DRAW_4
                    cardColourStr = cardToPlay[4].toString() + ""
                } else if (cardToPlay.indexOf(One.STR_DRAW_2) != -1 && cardToPlay.length == 4) {
                    cardValue = One.DRAW_2
                    cardColourStr = cardToPlay[0].toString() + ""
                } else if (cardToPlay[2] == 'r' && cardToPlay.indexOf(One.STR_WILD) == -1 && cardToPlay.length == 3) { //TODO Obviously not robust, fix so that it does substring instead.
                    cardValue = One.REVERSE
                    cardColourStr = cardToPlay[0].toString() + ""
                } else if (cardToPlay.indexOf(One.STR_SKIP) != -1 && cardToPlay.length == 3) {
                    cardValue = One.SKIP
                    cardColourStr = cardToPlay[0].toString() + ""
                } else if (cardToPlay.indexOf(One.STR_WILD) != -1 && cardToPlay.length == 3) {
                    cardValue = One.WILD
                    cardColourStr = cardToPlay[2].toString() + ""
                } else {
                    cardValue = cardValueStr.toInt()
                }
                if (!cardColourStr.equals("r", ignoreCase = true) &&
                    !cardColourStr.equals("y", ignoreCase = true) &&
                    !cardColourStr.equals("b", ignoreCase = true) &&
                    !cardColourStr.equals("g", ignoreCase = true)
                ) {
                    sendMessage("[PVT] $sender: Invalid Color Selection", sender)
                    return false
                }
                cardColour = getColour(cardColourStr)
            } catch (ex: Exception) {
                // anything goes wrong here with indexing or parsing, then it's bad input
                sendMessage("[PVT] $sender: You cannot play that card or you don''t have it.", sender)
                return false
            }
            // can the player use this card ...
            if (cardColour == cardInPlay!!.getColour() || cardColour == wildColour || cardValue == cardInPlay!!.getValue() || cardValue == One.WILD || cardValue == One.WILD_DRAW_4) {
                // range is ok, now let's see if the player actually has this card ...
                val card = player.getCard(cardValue, cardColour)
                if (card != null) {
                    discardedCards!!.add(card)
                    cardInPlay = card
                    player.removeCard(card)
                    var additionalInfo = "."
                    if (cardValue == One.WILD_DRAW_4) {
                        wildColour = getColour(cardColourStr)
                        nextPlayer(1)
                        if (drawCard(nextPlayer!!.getName(), 4)) {
                            val newCard =
                                Card(getColour(cardColourStr), One.ANY)
                            additionalInfo =
                                " and changes colour to " + newCard + " " + nextPlayer!!.getName() + " takes 4 extra cards and is skipped."
                            nextPlayer(1)
                        } else {
                            noCardsLeft(channel!!)
                            return true
                        }
                    } else if (cardValue == One.DRAW_2) {
                        nextPlayer(1)
                        if (drawCard(nextPlayer!!.getName(), 2)) {
                            additionalInfo = " " + nextPlayer!!.getName() + " takes 2 extra cards and is skipped."
                            nextPlayer(1)
                        } else {
                            noCardsLeft(channel!!)
                            return true
                        }
                    } else if (cardValue == One.REVERSE) {
                        if (players!!.size > 2) // 2+ means reverse back, otherwise, skip (aka do nothing)
                            nextPlayer(-1)
                        additionalInfo = ", turn goes back to " + nextPlayer!!.getName()
                        reversePlayerOrder()
                    } else if (cardValue == One.SKIP) {
                        nextPlayer(1)
                        additionalInfo = ", " + nextPlayer!!.getName() + " skipped."
                        nextPlayer(1)
                    } else if (cardValue == One.WILD) {
                        wildColour = getColour(cardColourStr)
                        val newCard =
                            Card(getColour(cardColourStr), One.ANY)
                        additionalInfo = " and changes colour to " + newCard
                        nextPlayer(1)
                    } else {
                        nextPlayer(1)
                    }
                    sendMessage(sender + " plays " + cardInPlay.toString() + additionalInfo)
                    if (cardValue != One.WILD && cardValue != One.WILD_DRAW_4) {
                        wildColour = One.WILD
                    }
                } else {
                    sendMessage("[PVT] $sender: You cannot play that card or you don''t have it.", sender)
                    return false
                }
            } else if (player.hasCardWithValue(cardValue) || player.hasCardWithColour(cardColour)) {
                sendMessage("[PVT] $sender: You have to play a card following on from the last played card, or a wild.", sender)
                return false
            } else {
                sendMessage("[PVT] $sender: You cannot play that card or you don''t have it.", sender)
                return false
            }
        }
        return true
    }

    fun getColour(cardColourStr: String): Int {
        if (cardColourStr == "b") {
            return One.BLUE
        } else if (cardColourStr == "g") {
            return One.GREEN
        } else if (cardColourStr == "r") {
            return One.RED
        }
        return One.YELLOW
    }

    private fun updateScores(nick: String, newScore: Int) {
        //updateScores(nick, newScore, 0, 0)
    }

    private fun reset(sender: String) {
        if (sender.equals(dealer, ignoreCase = true)) {
            endGame(true, null)
            sendMessage("$sender has reset the game. $CMD_START to start new One game")
        }
    }

    private fun endGame(cancelPot: Boolean, winner: String?) : Double {
        var payout = 0.0

        if (pot != null) {
            if (cancelPot) {
                try {
                    pot?.cancel()
                } catch (e: Exception) {
                    log.error(
                        "Unable to cancel pot " + pot.toString(),
                        e
                    )
                }
            } else {
                try {
                    payout = pot?.printTotalAmount()?.toDouble()!!
                    pot?.payout(winner!!, payout)
                } catch (e: Exception) {
                    log.error(
                        "Unable to payout pot " + pot.toString(),
                        e
                    )
                    payout = -1.0
                }
            }
            pot = null
        }

        resetGame()
        updateLastActivityTime()
        sendMessage("Play One - the card game. $CMD_START to start. Cost USD $costToJoinGame")
        return payout

    }

    @Throws(Exception::class)
    fun startGame(username: String, messageText: String) {
        updateLastActivityTime()
        if (gameState.equals(BotStateEnum.NO_GAME)) {
            // See if we should start a pot
            val params = messageText.split(" ").toTypedArray()
            var customPotEntry = 0.0
            if (params.size > 1) {
                try {
                    customPotEntry = params[1].toInt() / 100.0

                    // More than the default amount
                    if (customPotEntry < minPotEntry && customPotEntry != 0.0) {
                        sendMessage(
                            "[PVT] " + username + ": Invalid amount. Minimum amount is " + df.format(minPotEntry) + " " + "USD",
                            username
                        )
                        return
                    }
                } catch (e: NumberFormatException) {
                    sendMessage("[PVT] $username: Invalid amount", username)
                    return
                }
            }

            // Charge the user if they decided to create a pot
            if (customPotEntry > 0) {
                if (!userCanAffordToEnterPot(username, room, customPotEntry)) return

                // Create a pot for the game and charge the user
                try {
                    pot = Pot(this)
                    pot?.enterPlayer(username, customPotEntry)
                } catch (e: Exception) {
                    sendMessage("[PVT] Unable to start the game", username)
                    return
                }
                costToJoinGame = customPotEntry
            }
            setGameState(BotStateEnum.GAME_STARTING)
            gameStarter = username
            botOneExecutor.execute(StartGame(this))
        } else {    // game cannot be started right now
            sendGameCannotBeStartedMessage(username)
        }
    }

    private fun dealGame(sender: String) {
        if (!sender.equals(dealer, ignoreCase = true)) return
        if (!inProgress && players!!.size >= 2) {
            inProgress = true
            setNextPlayers(players!![1])
            deal()
            cardInPlay = drawCard()
            discardedCards?.add(cardInPlay!!)
            if (cardInPlay!!.getValue() == One.WILD || cardInPlay!!.getValue() == One.WILD_DRAW_4) {
                do {
                    cardInPlay = drawCard()
                    discardedCards?.add(cardInPlay!!)
                } while (cardInPlay!!.getValue() == One.WILD || cardInPlay!!.getValue() == One.WILD_DRAW_4)
            }
            if (cardInPlay!!.getValue() == One.REVERSE) {
                if (players!!.size > 2) // if there are 2 people, don't do anything, it's a skip
                    nextPlayer(-1)
                reversePlayerOrder()
            } else if (cardInPlay!!.getValue() == One.SKIP) {
                sendMessage("[PVT] ${nextPlayer?.getName()} has been skipped.", nextPlayer!!.getName()!!)
                nextPlayer(1)
            } else if (cardInPlay!!.getValue() == One.DRAW_2) {
                //nextPlayer(1);
                if (drawCard(nextPlayer!!.getName()!!, 1)) {
                    if (drawCard(nextPlayer!!.getName(), 1)) {
                    } else {
                        noCardsLeft(room)
                        drawCard(nextPlayer!!.getName(), 1) // just draw once
                    }
                } else {
                    noCardsLeft(room)
                    drawCard(nextPlayer!!.getName(), 2)
                    //	return true;
                } // skip
                sendMessage(nextPlayer!!.getName().toString() + " takes 2 extra cards and is skipped.")
                nextPlayer(1)
            }
            showTopCard(room)
        } else if (!inProgress) {
            sendMessage("$sender: You need at least 1 more player to start a game.")
        }

    }

    private fun showTopCard(room: String) {
        val additionalInfo = StringBuffer("")
        when (wildColour) {
            One.BLUE, One.GREEN, One.RED, One.YELLOW -> additionalInfo.append(
                " and colour is " + Card.colorEmoticonMappings[wildColour].toString() + One.STR_ANY
            )
            else -> additionalInfo.append("")
        }
        sendMessage(
            "${nextPlayer?.getName()}: it''s your turn <$CMD_PLAY_CARD to play, $CMD_PASS to pass, $CMD_DRAW to draw a card>. Top card is " + cardInPlay + additionalInfo.toString()
        )
        showPlayerHand(nextPlayer)
    }

    private fun showPlayerHand(player: Player?) {
        sendMessage("[PVT] Your cards: " + player?.getHand(), player?.getName()!!)
    }

    private fun noCardsLeft(room: String) {
        sendMessage("There are no cards left in the pack.  Shuffling..")
        redeal()
    }

    private fun redeal() {
        wildColour = One.WILD

        cards!!.add(Card(One.BLUE, 0))
        cards!!.add(Card(One.GREEN, 0))
        cards!!.add(Card(One.RED, 0))
        cards!!.add(Card(One.YELLOW, 0))
        for (y in 0..1) {
            for (x in 1..9) {
                cards!!.add(Card(One.BLUE, x))
                cards!!.add(Card(One.GREEN, x))
                cards!!.add(Card(One.RED, x))
                cards!!.add(Card(One.YELLOW, x))
            }
        }
        for (x in 1..2) {
            cards!!.add(
                Card(
                    One.BLUE,
                    One.DRAW_2
                )
            )
            cards!!.add(
                Card(
                    One.GREEN,
                    One.DRAW_2
                )
            )
            cards!!.add(
                Card(
                    One.RED,
                    One.DRAW_2
                )
            )
            cards!!.add(
                Card(
                    One.YELLOW,
                    One.DRAW_2
                )
            )
            cards!!.add(
                Card(
                    One.BLUE,
                    One.REVERSE
                )
            )
            cards!!.add(
                Card(
                    One.GREEN,
                    One.REVERSE
                )
            )
            cards!!.add(
                Card(
                    One.RED,
                    One.REVERSE
                )
            )
            cards!!.add(
                Card(
                    One.YELLOW,
                    One.REVERSE
                )
            )
            cards!!.add(
                Card(
                    One.BLUE,
                    One.SKIP
                )
            )
            cards!!.add(
                Card(
                    One.GREEN,
                    One.SKIP
                )
            )
            cards!!.add(
                Card(
                    One.RED,
                    One.SKIP
                )
            )
            cards!!.add(
                Card(
                    One.YELLOW,
                    One.SKIP
                )
            )
        }
        for (x in 1..4) {
            cards!!.add(
                Card(
                    One.WILD,
                    One.WILD
                )
            )
            cards!!.add(
                Card(
                    One.WILD,
                    One.WILD_DRAW_4
                )
            )
        }
        // remove cards players have in hand
        // remove cards players have in hand
        for (i in players!!.indices) {
            for (ii in 0 until players!![i].getCards()!!.size) {
                cards!!.remove(players!![i].getCards()!![ii] as Card?)
            }
        }

    }

    private fun reversePlayerOrder() {
        val tempPlayers: MutableList<Player> = ArrayList()
        for (x in players!!.indices.reversed()) {
            tempPlayers.add(players!![x])
        }
        players = tempPlayers
    }

    private fun drawCard(): Card? {
        if (cards!!.size > 0) {
            val random = Random()
            val rnd = random.nextInt(cards!!.size)
            val card = cards!![rnd] // pull a card from the pack
            cards!!.removeAt(rnd) // remove that card from the available cards
            return card
        }
        return null // no cards left

    }

    fun drawCard(sender: String?, numCards: Int): Boolean {
        val player = getPlayer(sender)
        var sendPlayerMsg = "You Drew: "
        if (player != null && null != cards && null != discardedCards) {
            if (cards!!.size == 0 && discardedCards!!.size >= numCards) {
                cards = discardedCards
            } else if (cards!!.size == 0 && discardedCards!!.size == 0) {
                return false
            }
            var tmpCard: Card? = Card(99, 99) // Added by Aradon -> Inform player
            for (x in 1..numCards) { // who is drawing cards which cards they are getting
                tmpCard = drawCard()
                if (tmpCard == null) {
                    return false
                }
                player.addCard(tmpCard)
                sendPlayerMsg += "$tmpCard "
            }
            sendMessage("[PVT] " +sendPlayerMsg, player.getName()!!)
        }
        return true
    }

    private fun deal() {
        val random = Random()
        var rnd = 0
        for (player in players!!) {
            if (null == player) {
                continue
            }
            for (x in 1..7) {  // deal 7 cards to each player
                rnd = random.nextInt(cards!!.size) // pick a random number from the available cards
                player.addCard(cards!![rnd]) // give the player that card
                cards?.removeAt(rnd) // remove that card from the available cards
            }
            sendMessage("[PVT] "+ player.toString(), player.getName()!!)
        }
    }

    private fun addPlayer(username: String) {
        if (getGameState() === BotStateEnum.GAME_STARTED || getGameState() === BotStateEnum.GAME_JOINING) {
            // If there is a pot, and the user is not the game starter (who has already been charged) then charge the user
            if (costToJoinGame > 0 && pot != null && !username.equals(gameStarter, ignoreCase = true)) {
                if (!userCanAffordToEnterPot(username, room, costToJoinGame)) return
                try {
                    pot?.enterPlayer(username, costToJoinGame, "USD")
                } catch (e: Exception) {
                    sendMessage("[PVT] You could not be added to the game.", username)
                    log.error("Unable to add player to game (" + username + "): " + e.message)
                    return  // if there's problem entering the player into the pot, just return
                }
            }
            players?.add(Player(username))
            if (dealer.equals("", ignoreCase = true)) {
                dealer = username
                playersNames?.put(username, true)
            } else {
                playersNames?.put(username, false)
            }
            val message = StringBuilder()
            message.append("$username: added to game. ")
            log.info("$username joined the game UnoBot")
            sendMessage("[PVT] $message", username)
            if (username != gameStarter) {
//                sendChannelMessage(createMessage("JOIN", username))
                sendMessage("$username joined the game.")
            }
        }

    }


    private fun sendGameCannotBeStartedMessage(username: String) {
        var message: String? = null
        when (gameState.value()) {
            BotStateEnum.ID_GAME_STARTED, BotStateEnum.ID_PLAYING -> message = "A game is currently on."
            BotStateEnum.ID_GAME_JOINING -> message = "A game is on. $CMD_JOIN to join. Charges may apply."
            else -> message = "Sorry, new game cannot be started now."
        }
        sendMessage("[PVT] $message", username)
    }

    private fun isPlayersTurn(sender: String): Boolean {
        val player: Player = getPlayer(sender)!!
        return null != player && player.getName().equals(nextPlayer!!.getName(),true)
    }

    fun nextPlayer(increment: Int) {
        var playerTurn = players!!.indexOf(nextPlayer)
        playerTurn += increment
        if (playerTurn > players!!.size - 1) {
            setNextPlayers(players!![0])
        } else if (playerTurn == -1) {
            setNextPlayers(players!![players!!.size - 1])
        } else {
            setNextPlayers(players!![playerTurn])
        }
    }

    fun setNextPlayers(player: Player) {
        nextPlayer = player
    }

    fun getPlayer(name: String?): Player? {
        for (player in players!!) {
            if (player.getName().equals(name,true)) {
                return player
            }
        }
        return null
    }

    fun inProgress(): Boolean {
        return inProgress
    }

    @Synchronized
     private fun updateLastActivityTime() {
         lastActivityTime = Date()
    }


    override fun stopBot(from: String) {
        if (log.isDebugEnabled)
            log.debug("Stopping bot instanceID[$instanceID]")

        if (waitForPlayersTimer != null && !waitForPlayersTimer!!.isDone() && !waitForPlayersTimer!!.isCancelled()) {
            waitForPlayersTimer!!.cancel(true)
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
//            if(players.keys.isNotEmpty())
                resetGame()
        }
        setGameState(BotData.BotStateEnum.NO_GAME)

        log.debug("Stopped bot instanceID[$instanceID]")
        sendMessage("bot ${botData.displayName} has been stopped by $from")
    }



    override fun runner(botData: BotData) {
        TODO("Not yet implemented")
    }

    inner class Score(
        /**
         * @return the player
         */
        var player: String,
        /**
         * @return the points
         */
        var score: Int
    ) : Comparable<Score> {

        /**
         * @param points the points to set
         */
        fun incrementScore(points: Int) {
            score += points
        }

        override fun equals(scoreObj: Any?): Boolean {
            if (scoreObj == null || scoreObj !is Score) {
                return false
            }
            val score = scoreObj
            return player == score.player && this.score == score.score
        }

        override operator fun compareTo(o: Score): Int {
            val score = o
            if (this.score > score.score) {
                return -1
            }
            return if (this.score > score.score) 0 else 1
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Timers and Scheduled Tasks
    inner class StartGame(val bot: One) : Runnable {
//        var bot: One
        override fun run() {
            synchronized(bot) {
                if (log.isDebugEnabled()) log.debug(
                    "botInstanceID[]: in StartGame() "
                )
                var gameState: BotStateEnum? = null
                gameState = getGameState()
                if (gameState === BotStateEnum.GAME_STARTING) {
                    setGameState(BotStateEnum.GAME_STARTED)
                    addPlayer(gameStarter!!)
                    sendMessage(if(costToJoinGame > 0.0) "$gameStarter started a game of One. $CMD_JOIN to join. Cost USD $costToJoinGame. $timeToJoinGame seconds" else "$gameStarter started a game of One. $CMD_JOIN to join. $timeToJoinGame seconds")
                    //sendChannelMessage(createMessage("JOIN", gameStarter));
                    if (timeToJoinGame > 0) {        // schedule the new game to start after a pre-determined delay
                        setGameState(BotStateEnum.GAME_JOINING)
                        if (log.isDebugEnabled()) log.debug(
                            "OneBot: starting timer for StartPlay()"
                        )
                       log.info("One game started by " + bot.gameStarter)
                        waitForPlayersTimer = botOneExecutor.schedule(
                            StartPlay(bot),
                            timeToJoinGame,
                            TimeUnit.SECONDS
                        )
                        if (log.isDebugEnabled()) log.debug(
                            "botInstanceID[]: scheduled to start play. Awaiting join.. "
                        )
                    }
                }
            }
        }

//        init {
//            this.bot = bot
//        }
    }

    inner class StartPlay(val bot:One) : Runnable {
        override fun run() {
            waitForPlayersTimer = null
            try {
                synchronized(bot) {
                    if (log.isDebugEnabled()) log.debug(
                        "OneBot: starting play in StartPlay()"
                    )
                    val gameState: BotStateEnum = getGameState()!!
                    if (gameState === BotStateEnum.GAME_JOINING) {
                        setGameState(BotStateEnum.GAME_JOIN_ENDED)

                        // If not enough players end the game.
                        if (players?.size!! < One.MIN_PLAYERS) {
                            sendMessage("Joining ends. Not enough players. Need $MIN_PLAYERS.")
                            resetGame()
                            if (log.isDebugEnabled())log.debug(
                                "botInstanceID[]: Join ended. Not enough players."
                            )
                        } else {
                            log.info("New game started in $room")
                            val message: String = "\"BotOne\" just started. $gameStarter,  $CMD_DEAL to deal cards."
                            sendMessage(message)
                            setGameState(BotStateEnum.PLAYING)
                            initGame(false)
                        }
                    }
                }
            } catch (e: Exception) {
                log.error(
                    "Unexpected exception caught in StartPlay.run()",
                    e
                )
                resetGame()
            }
        }

//        init {
//            this.bot = bot
//        }
    }

    private fun resetGame() {
        if (pot != null) {
            try {
                pot!!.cancel()
            } catch (e: Exception) {
                log.error(
                    "Unable to cancel pot " + pot.toString(),
                    e
                )
            }
            pot = null
        }

        gameStarter = null
        costToJoinGame = 0.0
        initGame(true)
        setGameState(BotStateEnum.NO_GAME)
    }

    private fun initGame(initializePlayers: Boolean) {
        if (initializePlayers) initializePlayerLists()

        cards = ArrayList<Card>()
        cardInPlay = null
        discardedCards = ArrayList()
        inProgress = false
        nextPlayer = null
        wildColour = One.WILD

        cards?.add(Card(One.BLUE, 0))
        cards?.add(Card(One.GREEN, 0))
        cards?.add(Card(One.RED, 0))
        cards?.add(Card(One.YELLOW, 0))
        for (y in 0..1) {
            for (x in 1..9) {
                cards?.add(Card(One.BLUE, x))
                cards?.add(Card(One.GREEN, x))
                cards?.add(Card(One.RED, x))
                cards?.add(Card(One.YELLOW, x))
            }
        }
        for (x in 1..2) {
            cards?.add(
                Card(
                    One.BLUE,
                    One.DRAW_2
                )
            )
            cards?.add(
                Card(
                    One.GREEN,
                    One.DRAW_2
                )
            )
            cards?.add(
                Card(
                    One.RED,
                    One.DRAW_2
                )
            )
            cards?.add(
                Card(
                    One.YELLOW,
                    One.DRAW_2
                )
            )
            cards?.add(
                Card(
                    One.BLUE,
                    One.REVERSE
                )
            )
            cards?.add(
                Card(
                    One.GREEN,
                    One.REVERSE
                )
            )
            cards?.add(
                Card(
                    One.RED,
                    One.REVERSE
                )
            )
            cards?.add(
                Card(
                    One.YELLOW,
                    One.REVERSE
                )
            )
            cards?.add(
                Card(
                    One.BLUE,
                    One.SKIP
                )
            )
            cards?.add(
                Card(
                    One.GREEN,
                    One.SKIP
                )
            )
            cards?.add(
                Card(
                    One.RED,
                    One.SKIP
                )
            )
            cards?.add(
                Card(
                    One.YELLOW,
                    One.SKIP
                )
            )
        }
        for (x in 1..4) {
            cards?.add(
                Card(
                    One.WILD,
                    One.WILD
                )
            )
            cards?.add(
                Card(
                    One.WILD,
                    One.WILD_DRAW_4
                )
            )
        }
    }

    private fun initializePlayerLists() {
        playersNames = HashMap()
        players = ArrayList()
        dealer = ""
    }


}


