package net.miggers.botgames.dice

import java.security.SecureRandom

class DiceRoll {
    private val random = SecureRandom()
    private var die1: Int = 0   // Number showing on the first die.
    private var die2: Int = 0   // Number showing on the second die.
    private var isWinner: Boolean =
        false  // Whether the current rolled value is a winner (based on specific parameters of the Dice game)


    /**
     * Rolls the dice by setting each of the dice to be a random number between 1 and 6.
     *
     */
    fun roll() {
        this.die1 = random.nextInt(6) + 1
        this.die2 = random.nextInt(6) + 1
    }

    /**
     * Rolls the dice by setting each of the dice. Then sets the win flag, based on this pair matched/exceeded the specific total.
     *
     */
    fun rollAndMatch(total: Int) {
        roll()
        isWinner = total() >= total
    }

    fun getDie1(): Int {
        return die1
    }

    fun getDie2(): Int {
        return die2
    }

    fun total(): Int {
        return die1 + die2
    }

    fun reset() {
        die1 = 0
        die2 = 0
    }

    fun isWinner(): Boolean {
        return isWinner
    }

    override fun toString(): String {
        return getDisplayString(die1) + " " + getDisplayString(die2)
    }

    /**
     * @param diceStr
     */
    private fun getDisplayString(die: Int): String {
        return "(" + DiceBot.EMOTICON_HOTKEY_DICE_PREFIX + die + ")"
//        return "(" + die + ")";
    }
}
