package net.miggers.botgames.one

import java.util.*

class Player(private var name: String) {

//    private var name = ""
    private val cards: MutableList<Card> = ArrayList()
    private var calledUno = false

//    fun Player(name: String) {
//        this.name = name
//    }

    fun addCard(card: Card?) {
        if (null == card) {
            // trying to insert a null card....
            // should we throw an exception?
            return
        }
        cards.add(card)
    }

    fun getCards(): List<Card>? {
        return cards
    }

    fun setCalledUno(called: Boolean) {
        calledUno = called
    }

    fun hasCalledUno(): Boolean {
        return calledUno
    }

    override fun toString(): String {
        val playersCards = StringBuffer(name + ": (" + cards.size + " cards) ")
        for (card in cards) {
            playersCards.append(" ")
            playersCards.append(card.toString())
        }
        return playersCards.toString()
    }

    fun getHand(): List<*>? {
        // TODO: secondary sorting must be easier than this
        val blue: MutableList<Card> = ArrayList()
        val green: MutableList<Card> = ArrayList()
        val red: MutableList<Card> = ArrayList()
        val yellow: MutableList<Card> = ArrayList()
        val wild: MutableList<Card> = ArrayList()
        for (card in cards) {
            when (card.getColour()) {
                One.BLUE -> blue.add(card)
                One.GREEN -> green.add(card)
                One.RED -> red.add(card)
                One.YELLOW -> yellow.add(card)
                One.WILD -> wild.add(card)
            }
        }
        Collections.sort(blue)
        Collections.sort(green)
        Collections.sort(red)
        Collections.sort(yellow)
        Collections.sort(wild)
        blue.addAll(green)
        blue.addAll(red)
        blue.addAll(yellow)
        blue.addAll(wild)
        return blue
    }

    fun getName(): String? {
        return name
    }

    fun setName(newName: String) {
        this.name = newName
    }

    fun getCard(cardValue: Int, cardColour: Int): Card? {
        for (card in cards) {
            if (card.getColour() == cardColour && card.getValue() == cardValue ||
                card.getValue() == One.WILD && cardValue == One.WILD ||
                card.getValue() == One.WILD_DRAW_4 && cardValue == One.WILD_DRAW_4
            ) {
                return card
            }
        }
        return null
    }

    fun hasCardWithValue(cardValue: Int): Boolean {
        for (card in cards) {
            if (card.getValue() == cardValue || card.getValue() == One.WILD || card.getValue() == One.WILD_DRAW_4) {
                return true
            }
        }
        return false
    }

    fun hasCardWithColour(cardColour: Int): Boolean {
        for (card in cards) {
            if (card.getColour() == cardColour) {
                return true
            }
        }
        return false
    }

    fun getPoints(): Int {
        // From Wiki: Number cards are face value, colored special cards worth twenty, and wilds worth fifty.
        var `val` = -1
        var scr = 0
        for (card in cards) {
            `val` = card.getValue()
            scr += if (`val` == One.REVERSE || `val` == One.SKIP || `val` == One.DRAW_2) 20 else if (`val` == One.WILD || `val` == One.WILD_DRAW_4) 50 else `val`
        }
        return scr
    }

    fun removeCard(card: Card?) {
        if (cards.contains(card)) {
            cards.remove(card)
        }
    }

    fun isLastCard(): Boolean {
        return cards.size == 1
    }

    fun hasUno(): Boolean {
        return cards.size == 1
    }

    fun hasWon(): Boolean {
        return cards.size == 0
    }

    fun cardCount(): Int {
        return cards.size
    }
}