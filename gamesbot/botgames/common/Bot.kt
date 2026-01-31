import { v4 as uuidv4 } from 'uuid'
import Config from '../Config'
import BotChannel from '../botgames/BotChannel'
import QueueMessage from '../botgames/messages/QueueMessage'
import ResponseMessage from '../botgames/messages/ResponseMessage'
import GameMessage from '../botgames/messages/GameMessage'
import CreditCache from '../data/cache/CreditCache'
import HistoryCreditStore from '../data/cache/HistoryCreditStore'
import { jedisInit } from '../di/jedisInit'
import ServerEvent from '../events/ServerEvent'

export default class Bot {

  constructor(botData, scheduledExecutor, room, userStart) {
    this.instanceID = uuidv4()

    this.botData = botData
    this.room = room
    this.userStart = userStart
    this.scheduledExecutor = scheduledExecutor

    this.gameStarter = null
    this.pot = null
    this.limitBalance = 0.25

    this.COMMAND_CHAR = "!"
    this.COMMAND_START = "!start"
    this.COMMAND_NO = "!n"
    this.COMMAND_JOIN = "!j"

    this.creditCache = new CreditCache()
    this.historyCreditStore = new HistoryCreditStore()

    this.outgoingQueueMessage = new QueueMessage(this, scheduledExecutor)
    this.incomingQueueMessage = new QueueMessage(this, scheduledExecutor)

    this.logger = console
  }

  /* ================= ABSTRACT ================= */
  stopBot(from) {
    throw new Error("stopBot() must be implemented")
  }

  onMessage(messageRoomPacket) {
    throw new Error("onMessage() must be implemented")
  }

  runner(botData) {
    throw new Error("runner() must be implemented")
  }

  /* ============================================ */

  subscribeChannelGame() {
    const jedis = jedisInit(new Config())
    const botChannel = new BotChannel(this.room)

    jedis.subscribe(botChannel, ServerEvent.CHAT_ROOM)
  }

  getBotName() {
    return this.botData.displayName
  }

  userCanAffordToEnterPot(username, room, amount) {
    try {
      if (!this.creditCache.validateAvailableRegularAmount(username, amount, this.limitBalance)) {
        this.sendPvtMessage(username, `${username}, You do not have sufficient credit to start a game`)
        return false
      }

      this.creditCache.useCredit(
        username,
        amount,
        CreditCache.TYPE_GAME_START,
        `playing ${this.botData.displayName}`
      )

      this.historyCreditStore.addHistory(
        username,
        amount.toString(),
        "GAME_START",
        `Playing ${this.botData.displayName}`
      )

      return true
    } catch (e) {
      this.logger.error(e)
      this.sendPvtMessage(username, `${username}, You do not have sufficient credit`)
      return false
    }
  }

  userCanAffordToEnterPotStart(username, room, amount) {
    try {
      return this.creditCache.validateAvailableRegularAmount(
        username,
        amount,
        this.limitBalance
      )
    } catch (e) {
      this.logger.error(e)
      return false
    }
  }

  sendMessage(message) {
    this.outgoingQueueMessage.queue(
      new ResponseMessage(null, this.room, message)
    )
  }

  sendMessageToUser(username, message) {
    this.outgoingQueueMessage.queue(
      new ResponseMessage(username, this.room, message)
    )
  }

  sendPvtMessage(username, message) {
    this.outgoingQueueMessage.queue(
      new ResponseMessage(username, this.room, message)
    )
  }

  queueIncomingMessage(packet) {
    this.logger.debug?.(`Incoming ${packet.from}: ${packet.message}`)
    this.incomingQueueMessage.queue(new GameMessage(packet))
  }

  revertLimitInCache(players, amount) {
    if (!players) return
    players.forEach(username => {
      if (username) this.revert(username, amount)
    })
  }

  revert(username, amount) {
    try {
      this.creditCache.addRegularBalance(username, amount)
      this.historyCreditStore.addHistory(
        username,
        amount.toString(),
        "GAME_REFUND",
        `Revert amount USD ${amount} from games ${this.getBotName()}`
      )
    } catch (e) {
      this.logger.error("revert error", e)
    }
  }
}