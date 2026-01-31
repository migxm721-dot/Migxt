export default class Card {

  /* ================= STATIC ================= */

  static EMOTICONS = [
    "(2C)", "(3C)", "(4C)", "(5C)", "(6C)", "(7C)", "(8C)", "(9C)", "(TC)",
    "(JC)", "(QC)", "(KC)", "(AC)",
    "(2D)", "(3D)", "(4D)", "(5D)", "(6D)", "(7D)", "(8D)", "(9D)", "(TD)",
    "(JD)", "(QD)", "(KD)", "(AD)",
    "(2H)", "(3H)", "(4H)", "(5H)", "(6H)", "(7H)", "(8H)", "(9H)", "(TH)",
    "(JH)", "(QH)", "(KH)", "(AH)",
    "(2S)", "(3S)", "(4S)", "(5S)", "(6S)", "(7S)", "(8S)", "(9S)", "(TS)",
    "(JS)", "(QS)", "(KS)", "(AS)"
  ]

  static #newDeck = []

  static newShuffledDeck() {
    const deck = [...Card.#newDeck]
    for (let i = deck.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      ;[deck[i], deck[j]] = [deck[j], deck[i]]
    }
    return deck
  }

  /* ================= ENUM ================= */

  static Rank = {
    DEUCE: { order: 1, char: '2' },
    THREE: { order: 2, char: '3' },
    FOUR: { order: 3, char: '4' },
    FIVE: { order: 4, char: '5' },
    SIX: { order: 5, char: '6' },
    SEVEN: { order: 6, char: '7' },
    EIGHT: { order: 7, char: '8' },
    NINE: { order: 8, char: '9' },
    TEN: { order: 9, char: 'T' },
    JACK: { order: 10, char: 'J' },
    QUEEN: { order: 11, char: 'Q' },
    KING: { order: 12, char: 'K' },
    ACE: { order: 13, char: 'A' },

    fromChar(c) {
      return Object.values(this).find(v => v.char === c) || null
    }
  }

  static Suit = {
    CLUBS: { char: 'C' },
    DIAMONDS: { char: 'D' },
    HEARTS: { char: 'H' },
    SPADES: { char: 'S' },

    fromChar(c) {
      return Object.values(this).find(v => v.char === c) || null
    }
  }

  /* ================= INIT ================= */

  constructor(rank = null, suit = null) {
    this.rank = rank
    this.suit = suit
  }

  rankValue() {
    return this.rank
  }

  suitValue() {
    return this.suit
  }

  toString() {
    return `(lc_${this.rank.char}${this.suit.char})`
  }

  toEmoticonHotkey() {
    return `(${this.toString()})`
  }

  compareTo(card) {
    if (card.rank.order < this.rank.order) return 1
    if (card.rank.order > this.rank.order) return -1
    return 0
  }

  equals(obj) {
    if (!obj || !(obj instanceof Card)) return false
    return this.rank.order === obj.rank.order
  }

  /* ================= STATIC INIT ================= */
  static {
    Object.values(Card.Suit).forEach(suit => {
      if (!suit.char) return
      Object.values(Card.Rank).forEach(rank => {
        if (!rank.char) return
        Card.#newDeck.push(new Card(rank, suit))
      })
    })
  }
}