package net.miggers.botgames.messages

import net.miggers.botgames.common.Bot
import net.miggers.data.model.User
import net.miggers.events.ServerEvent
import net.miggers.helpers.PublishHelper
import net.miggers.packet.MessageRoomPacket
import net.miggers.packet.MessageType
import org.koin.core.KoinComponent
import org.koin.core.inject

class ResponseMessage(private val target: String?, private val room: String, private val message: String) : Message(), KoinComponent {

    val publishHelper : PublishHelper by inject()

    override fun dispatch(bot: Bot) {
        if(target == null){
            sendMessage(bot.getBotName(),room,message)
        }else{
            sendPvtMessage(bot.getBotName(),target,room,message)
        }

    }

    private fun sendMessage(botName: String, room: String, message: String){
        val data = MessageRoomPacket().apply {
            this.type = MessageType.TYPE_BOT
            this.message = message
            this.level = User.BOT
            this.room = room
            this.from = botName
        }
        val publishData = publishHelper.createPublishData(null, data)
        publishHelper.publish(ServerEvent.CHAT_BOT_MSG,publishData.toJson())
    }

    private fun sendPvtMessage(botName: String, target: String, room: String, message: String){
        val data = MessageRoomPacket().apply {
            this.to = target
            this.type = MessageType.TYPE_BOT
            this.message = message
            this.level = User.BOT
            this.room = room
            this.from = botName
        }

        val publishData = publishHelper.createPublishData(null, data)
        publishHelper.publish(ServerEvent.CHAT_BOT_MSG,publishData.toJson())
    }

}
