import { ServerEvent } from '../events/ServerEvent'

export default class BotChannel {

  constructor(room = null) {
    this.room = room
    this.botMessage = null
  }

  /**
   * Dipanggil oleh Redis subscriber
   */
  onMessage(channel, message) {
    if (channel !== ServerEvent.CHAT_ROOM) return

    try {
      const pubsubData = JSON.parse(message)
      const messageRoomPacket = pubsubData?.data

      if (messageRoomPacket && this.botMessage) {
        // Kalau mau filter room:
        // if (messageRoomPacket.room === this.room)
        this.botMessage.onMessage(messageRoomPacket)
      }

    } catch (err) {
      console.error("BotChannel parse error:", err)
    }
  }

  setBotMessageListener(listener) {
    this.botMessage = listener
  }
}