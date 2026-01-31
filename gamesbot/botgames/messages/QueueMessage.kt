package net.miggers.botgames.messages

import net.miggers.botgames.common.Bot
import java.util.*
import java.util.concurrent.ScheduledExecutorService

class QueueMessage(private val bot: Bot, private val executor: ScheduledExecutorService) : Runnable {

    private var messages: Queue<Message> = LinkedList()

    override fun run() {
        var message: Message?
        synchronized(messages){
            message = messages.peek()
            if(message == null){
                return
            }
        }

        try {
            message?.dispatch(bot)
        }catch (e:Exception){
            e.printStackTrace()
        }

        synchronized(messages){
            messages.poll()
            if(messages.size > 0){
                executor.execute(this)
            }
        }
    }

    fun queue(message: Message){
        synchronized(messages){
            this.messages.add(message)
            if(messages.size == 1){
                executor.execute(this)
            }
        }
    }
}
