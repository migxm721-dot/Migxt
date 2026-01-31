package net.miggers.botgames.data

import java.io.Serializable
import java.sql.ResultSet
import java.sql.SQLException

class BotData : Serializable {
    var id: Long = 0
    var game: String? =
        null                // Name of the game as it appears in listings in the app (Ex: Werewolf)
    var displayName: String = ""            // Name of the bot as it appears in messages in the rooms (Ex: WerewolfBot)
    var commandName: String? =
        null            // name that commands sent to a bot of this type will use. Usage: /bot <commandname> <command> [Example: /bot wolf join]
    var description: String? = null
    var executableFileName: String? = null
    var libraryPaths: String? =
        null    // must be a semi-colon (;) separated string of library paths to look for classes and jars. Ends with a File.separator character if it is a directory, otherwise it is assumed to be a file.
    var type: Int = 0
    var status: Boolean = false
    var emoticonKeyList: String? =
        null    // this is the comma-separated list of special commands that would trigger a response from this bot. Ex: "!start, !quit"

    /**
     * Commands to notify the bot when a user joins or leaves the chatroom. The bot will update its list of users based on these notifications.
     *
     * @author lgopalan
     */
    enum class BotCommandEnum private constructor(private val value: Int) {

        JOIN(1), PART(2), QUIT(3);

        fun value(): Int {
            return value
        }

        companion object {
            val ID_JOIN = 1
            val ID_PART = 2
            val ID_QUIT = 3
            fun fromValue(value: Int): BotCommandEnum? {
                for (e in BotCommandEnum.values()) {
                    if (e.value() == value) {
                        return e
                    }
                }
                return null
            }
        }
    };

    /**
     * Types of rooms where a bot can be loaded in.
     *
     * @author lgopalan
     */
    enum class BotChannelTypeEnum private constructor(private val value: Int) {

        CHAT_ROOM(1), GROUP_CHAT(2);

        fun value(): Int {
            return value
        }

        companion object {
            val ID_CHAT_ROOM = 1
            val ID_GROUP_CHAT = 2
            fun fromValue(value: Int): BotChannelTypeEnum? {
                for (e in BotChannelTypeEnum.values()) {
                    if (e.value() == value) {
                        return e
                    }
                }
                return null
            }
        }
    };

    /**
     * States that a running bot can possibly be in.
     *
     * @author lgopalan
     */
    enum class BotStateEnum private constructor(private val value: Int) {

        NO_GAME(0), GAME_STARTING(1), GAME_STARTED(2), GAME_JOINING(3), GAME_JOIN_ENDED(4), PLAYING(5), GAME_ENDED(99);

        fun value(): Int {
            return value
        }

        companion object {
            val ID_NO_GAME = 0
            val ID_GAME_STARTING = 1
            val ID_GAME_STARTED = 2
            val ID_GAME_JOINING = 3
            val ID_GAME_JOIN_ENDED = 4
            val ID_PLAYING = 5
            val ID_GAME_ENDED = 99
            fun fromValue(value: Int): BotStateEnum? {
                for (e in BotStateEnum.values()) {
                    if (e.value() == value) {
                        return e
                    }
                }
                return null
            }
        }
    }

}
