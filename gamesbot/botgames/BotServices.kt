import fs from 'fs'
import { createClient } from 'redis'
import Bot from './common/Bot'
import DiceBot from './dice/DiceBot'
import LowCard from './lowcard/LowCard'
import Cricket from './cricket/Cricket'
import One from './one/One'
import BotData from './data/BotData'
import Config from '../Config'
import ServerEvent from '../events/ServerEvent'
import ChatRoomCache from '../data/cache/ChatRoomCache'
import UsersCache from '../data/cache/UsersCache'
import Room from '../data/model/Room'
import User from '../data/model/User'

export default class BotServices {

  constructor() {
    this.config = new Config()
    this.roomCache = new ChatRoomCache()
    this.usersCache = new UsersCache()

    this.redis = createClient()
    this.hashBotRoom = new Map()
  }

  async start(configFile) {
    if (configFile && fs.existsSync(configFile)) {
      this.config.load(configFile)
      console.log(`Loaded config: ${configFile}`)
    }

    await this.redis.connect()
    this.manageBot()
  }

  manageBot() {
    this.redis.subscribe(ServerEvent.CHAT_ROOM, async (message) => {
      const pubsubData = JSON.parse(message)
      const packet = pubsubData.data

      if (!packet) return

      const { room, from, message: msg } = packet

      if (msg === "/bot dice add" && this.getUsersLevel(room, from) >= User.ADMIN) {
        if (!this.hashBotRoom.has(room)) {
          const bot = this.addBotDice(room, from)
          this.hashBotRoom.set(room, bot)
        }
      }

      else if (msg === "/bot lowcard add" && this.getUsersLevel(room, from) >= User.ADMIN) {
        if (!this.hashBotRoom.has(room)) {
          const bot = this.addBotLowcard(room, from)
          this.hashBotRoom.set(room, bot)
        }
      }

      else if (msg === "/bot cricket add" && this.getUsersLevel(room, from) >= User.ADMIN) {
        if (!this.hashBotRoom.has(room)) {
          const bot = this.addBotCricket(room, from)
          this.hashBotRoom.set(room, bot)
        }
      }

      else if (msg === "/bot one add" && this.getUsersLevel(room, from) >= User.ADMIN) {
        if (!this.hashBotRoom.has(room)) {
          const bot = this.addBotOne(room, from)
          this.hashBotRoom.set(room, bot)
        }
      }

      else if (msg === "/bot stop" && this.getUsersLevel(room, from) >= User.ADMIN) {
        const bot = this.hashBotRoom.get(room)
        if (bot) {
          bot.stopBot(from)
          this.hashBotRoom.delete(room)
        }
      }

      else if (msg === "/bot kill" && this.getUsersLevel(room, from) === User.OWNER) {
        this.hashBotRoom.forEach(bot => bot.stopBot(from))
        this.hashBotRoom.clear()
      }

      if (this.hashBotRoom.has(room)) {
        this.hashBotRoom.get(room).queueIncomingMessage(packet)
      }
    })
  }

  /* ================= BOT FACTORY ================= */

  addBotDice(room, from) {
    const botData = new BotData({
      commandName: "/bot dice add",
      description: "Bot Dice",
      displayName: "DiceBot",
      emoticonKeyList: "!start, !j, !r"
    })
    return new DiceBot(botData, room, from)
  }

  addBotLowcard(room, from) {
    const botData = new BotData({
      commandName: "/bot lowcard add",
      description: "Bot LowCard",
      displayName: "LowCardBot",
      emoticonKeyList: "!start, !j, !d, !n"
    })
    return new LowCard(botData, room, from)
  }

  addBotCricket(room, from) {
    const botData = new BotData({
      commandName: "/bot cricket add",
      description: "Bot Cricket",
      displayName: "CricketBot",
      emoticonKeyList: "!start, !j, !d, !n"
    })
    return new Cricket(botData, room, from)
  }

  addBotOne(room, from) {
    const botData = new BotData({
      commandName: "/bot one add",
      description: "Bot One",
      displayName: "OneBot",
      emoticonKeyList: "!start, !j, !d, !n"
    })
    return new One(botData, room, from)
  }

  /* ================= PERMISSION ================= */

  getUsersLevel(room, username) {
    const user = this.usersCache.getUserbyUsername(username)
    if (!user) return 0

    const roomInfo = this.roomCache.getRoomInfo(room)
    if (!roomInfo) return user.users_level_id

    if (
      (roomInfo.type === Room.TYPE_MANAGED ||
       roomInfo.type === Room.TYPE_GAMES)
    ) {
      const moderators = this.roomCache.getModerators(room)

      if (roomInfo.managed === user.username && user.users_level_id < User.ADMIN)
        return User.MODERATOR

      if (moderators.includes(user.username) && user.users_level_id < User.ADMIN)
        return User.MODERATOR
    }

    return user.users_level_id
  }
}