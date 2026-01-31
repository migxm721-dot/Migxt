export default class PotStakeData {
  constructor() {
    this.potId = 0
    this.username = ""
    this.dateCreated = new Date()
    this.amount = 0.0
    this.exchangeRate = 10000.0 // exchange rate COINS
    this.currency = "COINS"
    this.bot = null
    // this.tax = 10.0 // kalau mau dipakai lagi
  }
}