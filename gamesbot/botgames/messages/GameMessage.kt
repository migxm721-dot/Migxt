package net.miggers.botgames.messages

import net.miggers.botgames.common.Bot
import net.miggers.packet.MessageRoomPacket

class GameMessage(private val messageRoomPacket: MessageRoomPacket) : Message() {
    override fun dispatch(bot: Bot) {
        bot.onMessage(messageRoomPacket)
    }
}
