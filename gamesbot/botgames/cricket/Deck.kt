package net.miggers.botgames.cricket

import java.util.*

class Deck {

    private val deck = LinkedList<Card>()
    private var combination: HashMap<Card, Int> = hashMapOf()

    enum class Card private constructor(val type: String, val namex: String, val emoticonKey: String) {
        ONE("1", "One", "(g-cr1)"),
        TWO("2", "Two", "(g-cr2)"),
        THREE("3", "Three", "(g-cr3)"),
        FOUR("4", "Four", "(g-cr4)"),
        SIX("6", "Six", "(g-cr6)"),
        BOWLED("O", "Bowled", "(g-crBowled)"),
        STUMPED("O", "Stumped", "(g-crStumped)"),
        CATCH("O", "Catch", "(g-crCatch)"),
        HIT_WICKET("O", "Hit Wicket", "(g-crHitWicket)"),
        LBW("O", "Leg Before Wicket", "(g-crLBW)"),
        RUN_OUT("O", "Run Out", "(g-crRunOut)"),
        THIRD_UMPIRE("U", "Third Umpire", "(g-crThirdUmpire)")
    }

    init {
        this.combination = HashMap<Card, Int>(12)
        this.combination[Card.ONE] = 45
        this.combination[Card.TWO] = 39
        this.combination[Card.THREE] = 3
        this.combination[Card.FOUR] = 21
        this.combination[Card.SIX] = 12
        this.combination[Card.BOWLED] = 4
        this.combination[Card.STUMPED] = 2
        this.combination[Card.CATCH] = 6
        this.combination[Card.HIT_WICKET] = 1
        this.combination[Card.LBW] = 3
        this.combination[Card.RUN_OUT] = 2
        this.combination[Card.THIRD_UMPIRE] = 3
    }


    /**
     * Load the card combination from the database
     */
    fun loadConfig() {

    }


    /**
     * Initialize the deck with the number of Cards
     */
    fun init() {
        val iterator = this.combination.entries.iterator()

        while (iterator.hasNext()) {
            val e = iterator.next()
            val card = e.key
            val qty = e.value

            for (i in 0 until qty) {
                this.deck.add(card)
            }
        }

        // Shuffle cards
        this.shuffle()
    }


    /**
     * Shuffle the deck
     */
    fun shuffle() {
        Collections.shuffle(this.deck)
    }

    /**
     * Draw a card from the deck
     *
     * @return a Card
     */
    fun draw(): Card? {
        return if (this.deck.size <= 0) {
            null
        } else this.deck.poll()

    }
}
