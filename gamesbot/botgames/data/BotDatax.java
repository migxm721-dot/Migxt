package net.miggers.botgames.data;

import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;

public class BotDatax implements Serializable {

	private long id;
	private String game;				// Name of the game as it appears in listings in the app (Ex: Werewolf)
	private String displayName;			// Name of the bot as it appears in messages in the rooms (Ex: WerewolfBot)
	private String commandName;			// name that commands sent to a bot of this type will use. Usage: /bot <commandname> <command> [Example: /bot wolf join]
	private String description;
	private String executableFileName;
	private String libraryPaths;	// must be a semi-colon (;) separated string of library paths to look for classes and jars. Ends with a File.separator character if it is a directory, otherwise it is assumed to be a file.
	private int type;
	private boolean status;
	private String emoticonKeyList;	// this is the comma-separated list of special commands that would trigger a response from this bot. Ex: "!start, !quit"

	/**
	 * Commands to notify the bot when a user joins or leaves the chatroom. The bot will update its list of users based on these notifications.
	 *
	 * @author lgopalan
	 *
	 */
	public enum BotCommandEnum {

		JOIN(1), PART(2), QUIT(3);

		private int value;
		public static final int ID_JOIN = 1;
		public static final int ID_PART = 2;
		public static final int ID_QUIT = 3;

		BotCommandEnum(int value) { this.value = value; }
		public int value() { return value; }
		public static BotCommandEnum fromValue(int value) {
			for (BotCommandEnum e : BotCommandEnum.values()) {
				if (e.value() == value) {
					return e;
				}
			}
			return null;
		}
	};

	/**
	 * Types of rooms where a bot can be loaded in.
	 *
	 * @author lgopalan
	 *
	 */
	public enum BotChannelTypeEnum {

		CHAT_ROOM(1), GROUP_CHAT(2);

		private int value;
		public static final int ID_CHAT_ROOM = 1;
		public static final int ID_GROUP_CHAT = 2;

		BotChannelTypeEnum(int value) { this.value = value; }
		public int value() { return value; }
		public static BotChannelTypeEnum fromValue(int value) {
			for (BotChannelTypeEnum e : BotChannelTypeEnum.values()) {
				if (e.value() == value) {
					return e;
				}
			}
			return null;
		}
	};

	/**
	 * States that a running bot can possibly be in.
	 *
	 * @author lgopalan
	 *
	 */
	public enum BotStateEnum {

		NO_GAME(0), GAME_STARTING(1), GAME_STARTED(2), GAME_JOINING(3), GAME_JOIN_ENDED(4), PLAYING(5), GAME_ENDED(99);

		private int value;
		public static final int ID_NO_GAME = 0;
		public static final int ID_GAME_STARTING = 1;
		public static final int ID_GAME_STARTED = 2;
		public static final int ID_GAME_JOINING = 3;
		public static final int ID_GAME_JOIN_ENDED = 4;
		public static final int ID_PLAYING = 5;
		public static final int ID_GAME_ENDED = 99;

		BotStateEnum(int value) { this.value = value; }
		public int value() { return value; }
		public static BotStateEnum fromValue(int value) {
			for (BotStateEnum e : BotStateEnum.values()) {
				if (e.value() == value) {
					return e;
				}
			}
			return null;
		}
	};



	public BotDatax(ResultSet rs) throws SQLException {
		id = rs.getInt("ID");
		game = rs.getString("Game");
		displayName = rs.getString("DisplayName");
		commandName = rs.getString("CommandName");
		description = rs.getString("Description");
		executableFileName = rs.getString("ExecutableFileName");
		libraryPaths = rs.getString("LibraryPaths");
		type = rs.getInt("Type");
		status = rs.getBoolean("Status");
		emoticonKeyList = rs.getString("EmoticonKeyList");
	}

	public BotDatax() {
	}

	/**
	 * @return the id
	 */
	public long getId() {
		return id;
	}

	/**
	 * @param id the id to set
	 */
	public void setId(long id) {
		this.id = id;
	}

	/**
	 * @return the game
	 */
	public String getGame() {
		return game;
	}

	/**
	 * @param game the game to set
	 */
	public void setGame(String game) {
		this.game = game;
	}

	/**
	 * @return the name
	 */
	public String getDisplayName() {
		return displayName;
	}

	/**
	 * @param name the name to set
	 */
	public void setDisplayName(String name) {
		this.displayName = name;
	}

	/**
	 * @return the commandName
	 */
	public String getCommandName() {
		return commandName;
	}

	/**
	 * @param commandName the commandName to set
	 */
	public void setCommandName(String commandName) {
		this.commandName = commandName;
	}

	/**
	 * @return the description
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * @param description the description to set
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * @return the executableFileName
	 */
	public String getExecutableFileName() {
		return executableFileName;
	}

	/**
	 * @param executableFileName the executableFileName to set
	 */
	public void setExecutableFileName(String executableFileName) {
		this.executableFileName = executableFileName;
	}

	/**
	 * @return the libraryPaths
	 */
	public String getLibraryPaths() {
		return libraryPaths;
	}

	/**
	 * @param libraryPaths the libraryPaths to set
	 */
	public void setLibraryPaths(String libraryPaths) {
		this.libraryPaths = libraryPaths;
	}

	/**
	 * @return the type
	 */
	public int getType() {
		return type;
	}

	/**
	 * @param type the type to set
	 */
	public void setType(int type) {
		this.type = type;
	}

	/**
	 * @return the enabled
	 */
	public boolean isEnabled() {
		return status;
	}

	/**
	 * @param enabled the enabled to set
	 */
	public void setEnabled(boolean enabled) {
		this.status = enabled;
	}

	/**
	 * @return the emoticonKeyList
	 */
	public String getEmoticonKeyList() {
		return emoticonKeyList;
	}

	/**
	 * @param emoticonKeyList the emoticonKeyList to set
	 */
	public void setEmoticonKeyList(String emoticonKeyList) {
		this.emoticonKeyList = emoticonKeyList;
	}
}
