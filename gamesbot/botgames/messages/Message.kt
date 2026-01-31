package net.miggers.botgames.messages

import net.miggers.botgames.common.Bot

abstract class Message {
    abstract fun dispatch(bot: Bot)
}
