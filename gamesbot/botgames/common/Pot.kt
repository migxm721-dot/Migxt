import CreditCache from '../data/cache/CreditCache'
import HistoryCreditStore from '../data/cache/HistoryCreditStore'

export default class Pot {

  constructor(bot) {
    this.bot = bot
    this.logger = console

    this.stakes = new Map()
    this.creditCache = new CreditCache()
    this.historyCreditStore = new HistoryCreditStore()

    this.tax = 10.0 // percent
  }

  enterPlayer(player, amount, currency = "COINS") {
    const potStake = {
      amount,
      bot: this.bot,
      currency,
      dateCreated: new Date(),
      username: player
    }

    this.stakes.set(player, potStake)
  }

  removePlayer(username) {
    if (!this.stakes.has(username)) return
    this.stakes.delete(username)
  }

  getTotalAmountInBaseCurrency() {
    let total = 0

    for (const stake of this.stakes.values()) {
      total += stake.amount
    }

    // potong pajak
    const taxCut = (total * this.tax) / 100
    return total - taxCut
  }

  cancel() {
    for (const stake of this.stakes.values()) {
      this.creditCache.addRegularBalance(stake.username, stake.amount)

      this.creditCache.addLogTransaction(
        stake.username,
        CreditCache.TYPE_GAME_REFUND,
        stake.amount,
        "COINS",
        `Revert amount COINS ${stake.amount} from games ${this.bot.getBotName()}`
      )

      this.historyCreditStore.addHistory(
        stake.username,
        stake.amount.toString(),
        "GAME_REFUND",
        `Revert amount USD ${stake.amount} from games ${this.bot.getBotName()}`
      )
    }

    this.stakes.clear()
  }

  printTotalAmount() {
    return this.getTotalAmountInBaseCurrency().toFixed(2)
  }

  payout(username, amount) {
    try {
      this.creditCache.addRegularBalance(username, amount)

      this.creditCache.addLogTransaction(
        username,
        CreditCache.TYPE_GAME_WIN,
        amount,
        "USD",
        `Win ${amount} USD from games ${this.bot.getBotName()}`
      )

      this.historyCreditStore.addHistory(
        username,
        amount.toString(),
        "GAME_WIN",
        `Win ${amount} USD from games ${this.bot.getBotName()}`
      )
    } catch (e) {
      console.error("error payout", e)
    }
  }
}