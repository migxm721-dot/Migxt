package net.miggers.botgames.one

import java.util.HashMap

class Card(private val colour: Int, private val value: Int) : Comparable<Any> {
    companion object {
        var colorEmoticonMappings: MutableMap<Int, String> =
            HashMap() // color mapped to the corresponding emoticon displaying a card of that color
//        static
//        {
//            colorEmoticonMappings[One.BLUE] = One.EMOTICON_HOTKEY_BLUE
//            colorEmoticonMappings[One.GREEN] = One.EMOTICON_HOTKEY_GREEN
//            colorEmoticonMappings[One.RED] = One.EMOTICON_HOTKEY_RED
//            colorEmoticonMappings[One.YELLOW] = One.EMOTICON_HOTKEY_YELLOW
//        }

    }

    init {
        colorEmoticonMappings[One.BLUE] = One.EMOTICON_HOTKEY_BLUE
        colorEmoticonMappings[One.GREEN] = One.EMOTICON_HOTKEY_GREEN
        colorEmoticonMappings[One.RED] = One.EMOTICON_HOTKEY_RED
        colorEmoticonMappings[One.YELLOW] = One.EMOTICON_HOTKEY_YELLOW
    }


//    private var colour = -1
//    private var value = -1
//
//
//
//    fun Card(colour: Int, value: Int) {
//        this.colour = colour
//        this.value = value
//    }

    fun getColour(): Int {
        return colour
    }

    fun getValue(): Int {
        return value
    }

    override fun toString(): String {
        val cardStr = StringBuffer()
        when (value) {
            One.WILD -> cardStr.append(One.STR_WILD)
            One.WILD_DRAW_4 -> cardStr.append(One.STR_WILD_DRAW_4)
            One.REVERSE -> cardStr.append(One.STR_REVERSE)
            One.DRAW_2 -> cardStr.append(One.STR_DRAW_2)
            One.SKIP -> cardStr.append(One.STR_SKIP)
            One.ANY -> cardStr.append(One.STR_ANY)
            else -> cardStr.append(value)
        }
        when (colour) {
            One.BLUE, One.GREEN, One.RED, One.YELLOW -> cardStr.append(colorEmoticonMappings[colour])
            One.WILD -> cardStr.append("")
            else -> cardStr.append("")
        }
        return cardStr.toString()
    }


    override fun equals(cardObj: Any?): Boolean {
        if (cardObj == null || cardObj !is Card) {
            return false
        }
        val card: Card =
            cardObj as Card

        return value == card.value && colour == card.colour
    }

    override fun compareTo(other: Any): Int {
        val card: Card =
            other as Card
        if (value < card.getValue()) {
            return -1
        }
        return if (value <= card.getValue()) 0 else 1
    }

    override fun hashCode(): Int {
        var result = colour
        result = 31 * result + value
        return result
    }
}