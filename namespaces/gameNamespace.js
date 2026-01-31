const { setSession, setPresence } = require('../utils/redisUtils');
const gameEvents = require('../events/gameEvents');
const { handleDicebotCommand } = require('../events/dicebotEvents');
const { handleLowcardCommand } = require('../events/lowcardEvents');
const { handleLegendCommand } = require('../events/legendEvents');
const gameStateManager = require('../services/gameStateManager');
const logger = require('../utils/logger');

const setupGameNamespace = (io) => {
  const gameNamespace = io.of('/game');

  gameNamespace.on('connection', (socket) => {
    const username = socket.handshake.auth?.username || 'Anonymous';
    const userId = socket.handshake.auth?.userId || 'Unknown';
    
    if (username === 'Anonymous' || userId === 'Unknown') {
      console.warn(`[Game] Rejected anonymous connection: ${socket.id}`);
      socket.emit('error', { 
        message: 'Authentication required',
        code: 'AUTH_REQUIRED'
      });
      socket.disconnect(true);
      return;
    }
    
    logger.info('GAME_CLIENT_CONNECTED', { socketId: socket.id, username, userId });

    socket.join(`user:${userId}`);

    setSession(`game:${username}`, socket.id).catch(err => {
      console.warn(`[Game] Could not set session for ${username}:`, err.message);
    });

    gameEvents(gameNamespace, socket);

    socket.on('game:room:join', async (data) => {
      const { roomId } = data;
      if (roomId) {
        socket.join(`game:room:${roomId}`);
        logger.info('GAME_USER_JOINED_ROOM', { username, roomId });
      }
    });

    socket.on('game:room:leave', async (data) => {
      const { roomId } = data;
      if (roomId) {
        socket.leave(`game:room:${roomId}`);
        logger.info('GAME_USER_LEFT_ROOM', { username, roomId });
      }
    });

    const handleGameCommandInternal = async (data) => {
      const { roomId, userId, username, message, command } = data;
      const cmd = message || command;
      
      if (!roomId || !cmd) {
        socket.emit('error', { message: 'Missing roomId or command' });
        return;
      }

      const lowerMessage = cmd.toLowerCase().trim();
      
      const isBotAdminCommand = lowerMessage.startsWith('/bot ') || lowerMessage.startsWith('/add bot ');
      
      if (isBotAdminCommand) {
        if (lowerMessage.includes('dice')) {
          const handled = await handleDicebotCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (handled) return;
        }
        if (lowerMessage.includes('lowcard')) {
          const handled = await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (handled) return;
        }
        if (lowerMessage.includes('flagh')) {
          const handled = await handleLegendCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (handled) return;
        }
        if (lowerMessage.includes('stop')) {
          const lowcardHandled = await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (lowcardHandled) return;
          const legendHandled = await handleLegendCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (legendHandled) return;
        }
      }
      
      const activeGameType = await gameStateManager.getActiveGameType(roomId);
      
      if (lowerMessage === '!d') {
        if (activeGameType === gameStateManager.GAME_TYPES.LOWCARD) {
          await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
        }
        return;
      }
      
      if (lowerMessage === '!r' || lowerMessage === '!roll') {
        if (activeGameType === gameStateManager.GAME_TYPES.DICE) {
          await handleDicebotCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
        }
        return;
      }
      
      if (lowerMessage === '!fg' || lowerMessage.startsWith('!b ') || lowerMessage === '!lock') {
        if (activeGameType === gameStateManager.GAME_TYPES.FLAGBOT) {
          await handleLegendCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
        } else {
          const legendService = require('../services/legendService');
          const flagbotActive = await legendService.isBotActive(roomId);
          if (flagbotActive) {
            await handleLegendCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          }
        }
        return;
      }
      
      if (lowerMessage.startsWith('!start') || lowerMessage === '!j' || lowerMessage === '!join' || lowerMessage === '!cancel') {
        if (activeGameType === gameStateManager.GAME_TYPES.DICE) {
          const handled = await handleDicebotCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (handled) return;
        } else if (activeGameType === gameStateManager.GAME_TYPES.LOWCARD) {
          const handled = await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
          if (handled) return;
        } else {
          const { getRedisClient } = require('../redis');
          const redis = getRedisClient();
          
          const dicebotActive = await redis.exists(`dicebot:bot:${roomId}`);
          if (dicebotActive) {
            const handled = await handleDicebotCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
            if (handled) return;
          }
          
          const lowcardActive = await redis.exists(`lowcard:bot:${roomId}`);
          if (lowcardActive) {
            const handled = await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
            if (handled) return;
          }
        }
      }
      
      const dicebotHandled = await handleDicebotCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
      if (dicebotHandled) return;
      const lowcardHandled = await handleLowcardCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
      if (lowcardHandled) return;
      const legendHandled = await handleLegendCommand(gameNamespace, socket, { roomId, userId, username, message: cmd });
      if (legendHandled) return;
    };

    socket.on('game:command', handleGameCommandInternal);
    socket.on('game:command:received', handleGameCommandInternal);

    socket.on('ping', () => {
      socket.emit('pong', { timestamp: Date.now() });
    });

    socket.on('error', (error) => {
      logger.error('GAME_SOCKET_ERROR', { socketId: socket.id, error: error.message });
    });
    
    socket.on('disconnect', (reason) => {
      logger.info('GAME_CLIENT_DISCONNECTED', { socketId: socket.id, username, reason });
    });
  });

  return gameNamespace;
};

module.exports = { setupGameNamespace };
