const { query } = require('../db/db');
const { getRedisClient } = require('../redis');
const logger = require('../utils/logger');
const merchantTagService = require('./merchantTagService');
const gameStateManager = require('./gameStateManager');

const CARD_SUITS = ['h', 'd', 'c', 's'];
const CARD_VALUES = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14];

const JOIN_TIMEOUT = 30000;
const DRAW_TIMEOUT = 20000;
const COUNTDOWN_DELAY = 3000;
const MIN_ENTRY = 1;              // Min bet for regular Lowcard room
const MAX_ENTRY = 999999999;      // No limit
const MIN_ENTRY_BIG_GAME = 50;    // Min bet for Big Game room
const STALE_GAME_TIMEOUT = 120000; // 2 minutes - auto-cleanup stuck games
const HOUSE_FEE_PERCENT = 10;

// Redis timer keys
const TIMER_KEY = (roomId) => `room:${roomId}:lowcard:timer`;

const getCardCode = (value) => {
  if (value === 11) return 'j';
  if (value === 12) return 'q';
  if (value === 13) return 'k';
  if (value === 14) return 'a';
  return value.toString();
};

const generateDeck = () => {
  const deck = [];
  for (const suit of CARD_SUITS) {
    for (const value of CARD_VALUES) {
      const code = `lc_${getCardCode(value)}${suit}`;
      deck.push({ value, suit, code, image: `${code}.png` });
    }
  }
  return shuffleDeck(deck);
};

const shuffleDeck = (deck) => {
  const shuffled = [...deck];
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled;
};

const getCardEmoji = (card) => {
  if (!card) return '(?)';
  return `[CARD:${card.code}]`;
};

const getUserCredits = async (userId) => {
  try {
    const redis = getRedisClient();
    const cached = await redis.get(`credits:${userId}`);
    if (cached !== null) {
      return parseInt(cached);
    }
    const result = await query('SELECT credits FROM users WHERE id = $1', [userId]);
    const balance = result.rows[0]?.credits || 0;
    await redis.set(`credits:${userId}`, balance, 'EX', 300);
    return parseInt(balance);
  } catch (error) {
    logger.error('LOWCARD_GET_CREDITS_ERROR', error);
    return 0;
  }
};

const logGameTransaction = async (userId, username, amount, transactionType, description) => {
  try {
    await query(
      `INSERT INTO credit_logs (from_user_id, from_username, amount, transaction_type, description, created_at)
       VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)`,
      [userId, username, amount, transactionType, description]
    );
  } catch (error) {
    logger.error('LOWCARD_LOG_TRANSACTION_ERROR', error);
  }
};

const deductCredits = async (userId, amount, username = null, reason = null, gameSessionId = null) => {
  try {
    const redis = getRedisClient();
    
    const taggedBalance = await merchantTagService.getTaggedBalance(userId);
    let usedTaggedCredits = 0;
    let remainingAmount = amount;
    
    if (taggedBalance > 0) {
      const consumeResult = await merchantTagService.consumeForGame(userId, 'lowcard', amount, gameSessionId);
      if (consumeResult.success) {
        usedTaggedCredits = consumeResult.usedTaggedCredits || 0;
        remainingAmount = consumeResult.remainingAmount;
        if (usedTaggedCredits > 0) {
          logger.info('LOWCARD_TAGGED_CREDITS_USED', { userId, usedTaggedCredits, remainingAmount });
        }
      }
    }
    
    if (remainingAmount <= 0) {
      const current = await getUserCredits(userId);
      if (username && reason) {
        await logGameTransaction(userId, username, -amount, 'game_bet', `${reason} (Tagged Credits)`);
      }
      return { success: true, balance: current, usedTaggedCredits };
    }
    
    const current = await getUserCredits(userId);
    if (current < remainingAmount) {
      return { success: false, balance: current };
    }
    
    const result = await query(
      'UPDATE users SET credits = credits - $1 WHERE id = $2 AND credits >= $1 RETURNING credits',
      [remainingAmount, userId]
    );
    
    if (result.rows.length === 0) {
      return { success: false, balance: current };
    }
    
    const newBalance = parseInt(result.rows[0].credits);
    await redis.set(`credits:${userId}`, newBalance, 'EX', 300);
    
    if (username && reason) {
      const desc = usedTaggedCredits > 0 ? `${reason} (${usedTaggedCredits} tagged + ${remainingAmount} regular)` : reason;
      await logGameTransaction(userId, username, -amount, 'game_bet', desc);
    }
    
    return { success: true, balance: newBalance, usedTaggedCredits };
  } catch (error) {
    logger.error('LOWCARD_DEDUCT_CREDITS_ERROR', error);
    return { success: false, balance: 0 };
  }
};

const addCredits = async (userId, amount, username = null, reason = null) => {
  try {
    const redis = getRedisClient();
    const result = await query(
      'UPDATE users SET credits = credits + $1 WHERE id = $2 RETURNING credits',
      [amount, userId]
    );
    
    if (result.rows.length > 0) {
      const newBalance = parseInt(result.rows[0].credits);
      await redis.set(`credits:${userId}`, newBalance, 'EX', 300);
      
      if (username && reason) {
        await logGameTransaction(userId, username, amount, reason.includes('Refund') ? 'game_refund' : 'game_win', reason);
      }
      
      return { success: true, balance: newBalance };
    }
    return { success: false, balance: 0 };
  } catch (error) {
    logger.error('LOWCARD_ADD_CREDITS_ERROR', error);
    return { success: false, balance: 0 };
  }
};

const isRoomManaged = async (roomId) => {
  try {
    const result = await query('SELECT owner_id FROM rooms WHERE id = $1', [roomId]);
    return result.rows.length > 0 && result.rows[0].owner_id !== null;
  } catch (error) {
    logger.error('LOWCARD_CHECK_ROOM_ERROR', error);
    return false;
  }
};

const isRoomAdmin = async (roomId, userId) => {
  try {
    const result = await query(
      `SELECT 1 FROM rooms WHERE id = $1 AND owner_id = $2
       UNION
       SELECT 1 FROM room_admins WHERE room_id = $1 AND user_id = $2`,
      [roomId, userId]
    );
    return result.rows.length > 0;
  } catch (error) {
    logger.error('LOWCARD_CHECK_ADMIN_ERROR', error);
    return false;
  }
};

const isSystemAdmin = async (userId) => {
  try {
    const result = await query(
      "SELECT 1 FROM users WHERE id = $1 AND role IN ('admin', 'super_admin')",
      [userId]
    );
    return result.rows.length > 0;
  } catch (error) {
    logger.error('LOWCARD_CHECK_SYSADMIN_ERROR', error);
    return false;
  }
};

const addBotToRoom = async (roomId) => {
  const redis = getRedisClient();
  const botKey = `lowcard:bot:${roomId}`;
  
  const exists = await redis.exists(botKey);
  if (exists) {
    return { success: false, message: 'LowCardBot is already active in this room.' };
  }
  
  const dicebotActive = await redis.exists(`dicebot:bot:${roomId}`);
  if (dicebotActive) {
    return { success: false, message: 'DiceBot is active. Remove it first with /bot dice remove' };
  }
  
  const legendActive = await redis.exists(`legend:bot:${roomId}`);
  if (legendActive) {
    return { success: false, message: 'FlagBot is active. Remove it first.' };
  }
  
  await redis.set(botKey, JSON.stringify({
    active: true,
    defaultAmount: 50,
    createdAt: new Date().toISOString()
  }), 'EX', 86400 * 7);
  
  await gameStateManager.setActiveGameType(roomId, gameStateManager.GAME_TYPES.LOWCARD);
  
  return { success: true, message: `[PVT] Bot is running. Min: ${MIN_ENTRY} COINS` };
};

const removeBotFromRoom = async (roomId) => {
  const redis = getRedisClient();
  const botKey = `lowcard:bot:${roomId}`;
  const gameKey = `lowcard:game:${roomId}`;
  
  const exists = await redis.exists(botKey);
  if (!exists) {
    return { success: false, message: 'No LowCard bot in this room.' };
  }
  
  const gameData = await redis.get(gameKey);
  if (gameData) {
    const game = JSON.parse(gameData);
    if (game.status === 'waiting') {
      for (const player of game.players) {
        await addCredits(player.userId, game.entryAmount, player.username, 'LowCard Refund - Bot removed');
      }
    }
  }
  
  await redis.del(botKey);
  await redis.del(gameKey);
  await clearDeck(roomId);
  
  await gameStateManager.clearActiveGameType(roomId);
  
  return { success: true, message: 'LowCardBot has left the room.' };
};

const isBotActive = async (roomId) => {
  const redis = getRedisClient();
  const botKey = `lowcard:bot:${roomId}`;
  return await redis.exists(botKey);
};

const getBotStatus = async (roomId) => {
  const redis = getRedisClient();
  const botKey = `lowcard:bot:${roomId}`;
  const data = await redis.get(botKey);
  return data ? JSON.parse(data) : null;
};

const startGame = async (roomId, userId, username, amount) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  const lockKey = `lowcard:lock:${roomId}`;
  
  const lockAcquired = await redis.set(lockKey, '1', 'EX', 5, 'NX');
  if (!lockAcquired) {
    return { success: false, message: 'Please wait, another action is in progress.' };
  }
  
  try {
    // Check for stale game first and cleanup if needed
    const staleCheck = await checkAndCleanupStaleGame(roomId);
    if (staleCheck?.cleaned) {
      logger.info(`[LowCard] Stale game cleaned up in room ${roomId}`);
      // Continue to start new game
    }
    
    const existingGame = await redis.get(gameKey);
    if (existingGame) {
      const game = JSON.parse(existingGame);
      if (game.status === 'waiting' || game.status === 'playing') {
        // Double-check if timer exists
        const timer = await getTimer(roomId);
        if (!timer && game.status === 'waiting' && (Date.now() - new Date(game.createdAt).getTime() > 40000)) {
          // Stuck game - auto cleanup
          for (const player of game.players) {
            await addCredits(player.userId, game.entryAmount, player.username, 'LowCard Refund - Stuck game cleanup');
          }
          await redis.del(gameKey);
          await clearDeck(roomId);
        } else {
          return { success: false, message: 'A game is already in progress. Use !j to join.' };
        }
      } else {
        // If game is 'finished' or other state, clear it
        await redis.del(gameKey);
      }
    }
    
    const roomResult = await query('SELECT name FROM rooms WHERE id = $1', [roomId]);
    const roomName = roomResult.rows[0]?.name || '';
    const isBigGame = roomName.toLowerCase().includes('big game');
    const minEntry = isBigGame ? MIN_ENTRY_BIG_GAME : MIN_ENTRY;
    
    const requestedAmount = parseInt(amount) || minEntry;
    
    if (requestedAmount < minEntry) {
      return { success: false, message: `Minimal ${minEntry.toLocaleString()} COINS to start game.`, isPvt: true };
    }
    
    if (!isBigGame && requestedAmount > MAX_ENTRY) {
      return { success: false, message: `Maximal ${MAX_ENTRY.toLocaleString()} COINS to start game.`, isPvt: true };
    }
    
    const entryAmount = requestedAmount;
    
    const deductResult = await deductCredits(userId, entryAmount, username, `LowCard Bet - Start game`);
    if (!deductResult.success) {
      return { success: false, message: `You not enough credite`, isPvt: true };
    }
    
    await merchantTagService.trackTaggedUserSpending(userId, 'lowcard', entryAmount);
    
    const gameId = Date.now();
    
    const game = {
      id: gameId,
      roomId,
      status: 'waiting',
      entryAmount,
      pot: entryAmount,
      currentRound: 0,
      players: [{
        userId: userId,
        username,
        isEliminated: false,
        hasDrawn: false,
        currentCard: null
      }],
      startedBy: userId,
      startedByUsername: username,
      createdAt: new Date().toISOString(),
      joinDeadline: Date.now() + JOIN_TIMEOUT
    };
    
    let dbGameId = gameId;
    try {
      const insertResult = await query(
        `INSERT INTO lowcard_games (room_id, status, entry_amount, pot_amount, started_by, started_by_username)
         VALUES ($1, 'waiting', $2, $3, $4, $5) RETURNING id`,
        [roomId, entryAmount, entryAmount, userId, username]
      );
      if (insertResult.rows && insertResult.rows[0]) {
        dbGameId = insertResult.rows[0].id;
        game.id = dbGameId;
        game.dbId = dbGameId;
      }
    } catch (err) {
      logger.error('LOWCARD_DB_INSERT_ERROR', err);
    }
    
    await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
    
    return {
      success: true,
      gameId: dbGameId,
      newBalance: deductResult.balance,
      message: `LowCard started by ${username}. Enter !j to join the game. Cost: ${entryAmount} COINS [30s]`
    };
  } finally {
    await redis.del(lockKey);
  }
};

const joinGame = async (roomId, userId, username) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) {
    return { success: false, message: 'No active game. Use !start [amount] to start one.' };
  }
  
  const game = JSON.parse(gameData);
  
  if (game.status !== 'waiting') {
    return { success: false, message: 'Game already in progress. Wait for the next round.' };
  }
  
  if (Date.now() > game.joinDeadline) {
    return { success: false, message: 'Join period has ended.' };
  }
  
  const alreadyJoined = game.players.find(p => p.userId == userId);
  if (alreadyJoined) {
    return { success: false, message: 'You have already joined this game.' };
  }
  
  const deductResult = await deductCredits(userId, game.entryAmount, username, `LowCard Bet - Join game`);
  if (!deductResult.success) {
    return { success: false, message: `You not enough credite`, isPvt: true };
  }
  
  await merchantTagService.trackTaggedUserSpending(userId, 'lowcard', game.entryAmount);
  
  game.players.push({
    userId,
    username,
    isEliminated: false,
    hasDrawn: false,
    currentCard: null
  });
  game.pot += game.entryAmount;
  
  await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
  
  return {
    success: true,
    message: `${username} joined the game.`,
    playerCount: game.players.length,
    pot: game.pot,
    newBalance: deductResult.balance
  };
};

const beginGame = async (roomId) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) return null;
  
  const game = JSON.parse(gameData);
  
  if (game.status !== 'waiting') {
    return null;
  }
  
    if (game.players.length < 2) {
      for (const player of game.players) {
        await addCredits(player.userId, game.entryAmount, player.username, 'LowCard Refund - Not enough players');
      }
      await redis.del(gameKey);
      await clearDeck(roomId);
      await redis.del(TIMER_KEY(roomId)); // Clear timer to prevent stuck state
      return { 
        cancelled: true, 
        message: 'Not enough player join. Coins refunded.',
        playAgain: `Play now: !start to enter. Cost: ${MIN_ENTRY} COINS.`
      };
    }
  
  game.status = 'playing';
  game.currentRound = 1;
  delete game.deck;
  
  await initializeDeck(roomId);
  
  for (const player of game.players) {
    player.hasDrawn = false;
    player.currentCard = null;
  }
  
  game.countdownEndsAt = Date.now() + COUNTDOWN_DELAY;
  game.roundDeadline = Date.now() + COUNTDOWN_DELAY + DRAW_TIMEOUT;
  
  await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
  
  const playerNames = game.players.map(p => p.username).join(', ');
  
  return {
    started: true,
    playerCount: game.players.length,
    playerNames,
    message: `Game begins - Lowest card is OUT! ROUND #1: Player, !d to DRAW. ${DRAW_TIMEOUT / 1000} seconds.`
  };
};

const drawCardFromDeck = async (roomId) => {
  const redis = getRedisClient();
  const deckKey = `lowcard:deck:${roomId}`;
  
  let deckData = await redis.get(deckKey);
  let deck = deckData ? JSON.parse(deckData) : null;
  
  if (!deck || deck.length === 0) {
    deck = generateDeck();
  }
  
  const card = deck.pop();
  await redis.set(deckKey, JSON.stringify(deck), 'EX', 3600);
  
  return card;
};

const initializeDeck = async (roomId) => {
  const redis = getRedisClient();
  const deckKey = `lowcard:deck:${roomId}`;
  const deck = generateDeck();
  await redis.set(deckKey, JSON.stringify(deck), 'EX', 3600);
  return deck;
};

const clearDeck = async (roomId) => {
  const redis = getRedisClient();
  const deckKey = `lowcard:deck:${roomId}`;
  await redis.del(deckKey);
};

const drawCardForPlayer = async (roomId, userId, username) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) {
    await redis.set(
      gameKey,
      JSON.stringify({
        status: 'playing',
        players: [],
        roomId,
        createdAt: new Date().toISOString()
      }),
      'EX',
      3600
    );
    return { success: false, message: 'No active game. Session restored, try again.' };
  }
  
  const game = JSON.parse(gameData);
  
  if (game.status !== 'playing') {
    return { success: false, message: 'Game is not in progress.' };
  }
  
  if (game.countdownEndsAt && Date.now() < game.countdownEndsAt) {
    return { success: false, message: 'Wait for countdown to finish.', silent: true };
  }
  
  const playerIndex = game.players.findIndex(p => p.userId == userId && !p.isEliminated);
  if (playerIndex === -1) {
    return { success: false, message: '[PVT] You are not in this game', silent: false, isPvt: true };
  }
  
  const player = game.players[playerIndex];
  
  // In tie-breaker mode, only tied players can draw
  if (game.isTieBreaker && !player.inTieBreaker) {
    return { success: false, message: `[PVT] ${username}: Only tied players can draw now. Please wait...`, silent: false, isPvt: true };
  }
  
  if (player.hasDrawn) {
    return { success: false, message: `[PVT] ${username}: you already drew.`, silent: false, isPvt: true };
  }
  
  const card = await drawCardFromDeck(roomId);
  game.players[playerIndex].currentCard = card;
  game.players[playerIndex].hasDrawn = true;
  
  delete game.deck;
  await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
  
  return {
    success: true,
    card,
    cardDisplay: getCardEmoji(card),
    message: `${username}: ${getCardEmoji(card)}`
  };
};

const autoDrawForTimeout = async (roomId) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) return [];
  
  const game = JSON.parse(gameData);
  const autoDrawn = [];
  
  for (let i = 0; i < game.players.length; i++) {
    const player = game.players[i];
    
    // Skip eliminated players
    if (player.isEliminated) continue;
    
    // In tie-breaker mode, only auto-draw for tied players
    if (game.isTieBreaker && !player.inTieBreaker) continue;
    
    // Skip if already drawn
    if (player.hasDrawn) continue;
    
    const card = await drawCardFromDeck(roomId);
    game.players[i].currentCard = card;
    game.players[i].hasDrawn = true;
    autoDrawn.push({
      username: player.username,
      card,
      cardDisplay: getCardEmoji(card),
      message: `Bot draws - ${player.username}: ${getCardEmoji(card)}`
    });
  }
  
  delete game.deck;
  await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
  
  return autoDrawn;
};

const tallyRound = async (roomId, isTimedOut = false) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) return null;
  
  const game = JSON.parse(gameData);
  
  // Get the current hands to tally - if tiebreaker, only check tied players
  let currentHandsPlayers;
  if (game.isTieBreaker) {
    currentHandsPlayers = game.players.filter(p => !p.isEliminated && p.inTieBreaker && p.currentCard);
  } else {
    currentHandsPlayers = game.players.filter(p => !p.isEliminated && p.currentCard);
  }
  
  if (currentHandsPlayers.length === 0) {
    return { error: true, message: 'No active players with cards.' };
  }
  
  // Find player(s) with lowest card - following Kotlin logic
  const lowestHands = [];
  
  for (const player of currentHandsPlayers) {
    if (lowestHands.length === 0) {
      lowestHands.push(player);
    } else {
      const compareResult = player.currentCard.value - lowestHands[0].currentCard.value;
      if (compareResult < 0) {
        // This player has lower card, clear and add
        lowestHands.length = 0;
        lowestHands.push(player);
      } else if (compareResult === 0) {
        // Same value, add to tied list
        lowestHands.push(player);
      }
      // If compareResult > 0, this player has higher card, skip
    }
  }
  
  if (lowestHands.length === 0) {
    // No loser found, clear tiebreaker
    game.isTieBreaker = false;
    for (let i = 0; i < game.players.length; i++) {
      game.players[i].inTieBreaker = false;
    }
  } else if (lowestHands.length === 1) {
    // One player with lowest card - they are OUT
    const loser = lowestHands[0];
    const idx = game.players.findIndex(p => p.userId == loser.userId);
    if (idx !== -1) {
      game.players[idx].isEliminated = true;
    }
    
    // Clear tiebreaker state
    game.isTieBreaker = false;
    for (let i = 0; i < game.players.length; i++) {
      game.players[i].inTieBreaker = false;
    }
    
    const remainingPlayers = game.players.filter(p => !p.isEliminated);
    
    // Check if we have a winner
    if (remainingPlayers.length < 2) {
      return await finishGame(roomId, game, remainingPlayers, loser);
    }
    
    // Continue to next round - all remaining players draw
    game.currentRound++;
    for (let i = 0; i < game.players.length; i++) {
      if (!game.players[i].isEliminated) {
        game.players[i].hasDrawn = false;
        game.players[i].currentCard = null;
      }
    }
    
    game.countdownEndsAt = Date.now() + COUNTDOWN_DELAY;
    game.roundDeadline = Date.now() + COUNTDOWN_DELAY + DRAW_TIMEOUT;
    await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
    
    const isTieBroken = game.wasTieBreaker;
    game.wasTieBreaker = false;
    
    const remainingNames = remainingPlayers.map(p => p.username).join(', ');
    return {
      eliminated: loser.username,
      eliminatedCard: loser.currentCard,
      message: isTieBroken 
        ? `Tie broken! ${loser.username}: OUT with the lowest card! ${getCardEmoji(loser.currentCard)}`
        : `${loser.username}: OUT with the lowest card! ${getCardEmoji(loser.currentCard)}`,
      nextRound: game.currentRound,
      playerList: `Players are (${remainingPlayers.length}): ${remainingNames}`,
      followUp: `All players, next round in ${COUNTDOWN_DELAY / 1000} seconds!`
    };
  } else {
    // Multiple players with lowest card - Setup tiebreaker
    const tiedPlayerNames = lowestHands.map(p => p.username).join(', ');
    const tiedPlayerIds = lowestHands.map(p => p.userId);
    
    // Mark only tied players for re-draw
    for (let i = 0; i < game.players.length; i++) {
      if (!game.players[i].isEliminated) {
        const isTied = tiedPlayerIds.includes(game.players[i].userId);
        if (isTied) {
          game.players[i].hasDrawn = false;
          game.players[i].currentCard = null;
          game.players[i].inTieBreaker = true;
        } else {
          game.players[i].inTieBreaker = false;
        }
      }
    }
    
    game.isTieBreaker = true;
    game.wasTieBreaker = true;
    game.currentRound++;
    game.countdownEndsAt = Date.now() + COUNTDOWN_DELAY;
    game.roundDeadline = Date.now() + COUNTDOWN_DELAY + DRAW_TIMEOUT;
    await redis.set(gameKey, JSON.stringify(game), 'EX', 3600);
    
    return {
      tie: true,
      tiedPlayers: tiedPlayerNames,
      tiedCount: lowestHands.length,
      message: `Tied players (${lowestHands.length}): ${tiedPlayerNames}`,
      followUp: `Tied players ONLY draw again. Next round in ${COUNTDOWN_DELAY / 1000} seconds!`,
      nextRound: game.currentRound
    };
  }

  // This shouldn't be reached, but return null as fallback
  return null;
};

const finishGame = async (roomId, game, remainingPlayers, loser) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const winner = remainingPlayers[0];
  game.status = 'finished';
  const houseFee = Math.floor(game.pot * HOUSE_FEE_PERCENT / 100);
  const winnings = game.pot - houseFee;

  // Track commission for merchant tag
  try {
    const starterId = game.startedBy;
    const tagResult = await query('SELECT merchant_id FROM merchant_tags WHERE user_id = $1 LIMIT 1', [starterId]);
    if (tagResult.rows.length > 0 && houseFee > 0) {
      const merchantId = tagResult.rows[0].merchant_id;
      const commission = Math.floor(houseFee * 0.1); // 10% of house fee
      if (commission > 0) {
        const { addMerchantIncome } = require('../utils/merchantTags');
        await addMerchantIncome(merchantId, commission);
        logger.info(`[LowCard] Commission paid to merchant ${merchantId}: ${commission} (10% of ${houseFee})`);
      }
    }
  } catch (err) {
    logger.error('LOWCARD_COMMISSION_ERROR', err);
  }

  const creditResult = await addCredits(winner.userId, winnings, winner.username, `LowCard Win - Pot ${game.pot} COINS (Fee 10%: ${houseFee})`);
  
  game.winnerId = winner.userId;
  game.winnerUsername = winner.username;
  game.winnings = winnings;
  game.houseFee = houseFee;
  game.finishedAt = new Date().toISOString();

  if (game.id) {
    await query(
      `UPDATE lowcard_games SET status = 'finished', winner_id = $1, winner_username = $2, pot_amount = $3, finished_at = NOW()
       WHERE id = $4`,
      [winner.userId, winner.username, game.pot, game.id]
    ).catch(err => logger.error('LOWCARD_DB_UPDATE_ERROR', err));
    
    await query(
      `INSERT INTO lowcard_history (game_id, winner_id, winner_username, total_pot, commission, players_count)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [game.id, winner.userId, winner.username, game.pot, houseFee, game.players.length]
    ).catch(err => logger.error('LOWCARD_HISTORY_INSERT_ERROR', err));
  }

  await redis.set(gameKey, JSON.stringify(game), 'EX', 60);

  // Rapid cleanup (2s)
  setTimeout(async () => {
    await redis.del(gameKey);
    await clearDeck(roomId);
  }, 2000);

  return {
    gameOver: true,
    eliminated: loser ? loser.username : null,
    eliminatedCard: loser ? loser.currentCard : null,
    winner: winner.username,
    winnerId: winner.userId,
    winnings,
    newBalance: creditResult.balance,
    pot: game.pot,
    houseFee: houseFee,
    message: `Game over! ${winner.username} WINS ${winnings} COINS!!`,
    followUp: `Play now: !start to enter. Cost: ${MIN_ENTRY} COINS. For custom entry, !start <entry_amount>`
  };
};

const stopGame = async (roomId) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) {
    return { success: false, message: 'No active game to stop.' };
  }
  
  const game = JSON.parse(gameData);
  
  if (game.status === 'playing') {
    return { success: false, message: 'Cannot stop game once it has started.' };
  }
  
  for (const player of game.players) {
    await addCredits(player.userId, game.entryAmount, player.username, 'LowCard Refund - Game stopped');
  }
  
  await redis.del(gameKey);
  await clearDeck(roomId);
  
  return { success: true, message: 'Game stopped. All credits have been refunded.' };
};

const getActiveGame = async (roomId) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  const gameData = await redis.get(gameKey);
  return gameData ? JSON.parse(gameData) : null;
};

const allPlayersDrawn = async (roomId) => {
  const game = await getActiveGame(roomId);
  if (!game || game.status !== 'playing') return false;
  
  // In tiebreaker mode, only check tied players
  if (game.isTieBreaker) {
    return game.players.every(p => p.isEliminated || !p.inTieBreaker || p.hasDrawn);
  }
  
  return game.players.every(p => p.isEliminated || p.hasDrawn);
};

// Redis-based timer functions
const setTimer = async (roomId, phase, expiresAt) => {
  const redis = getRedisClient();
  const timerKey = TIMER_KEY(roomId);
  await redis.set(timerKey, JSON.stringify({
    roomId,
    phase, // 'join' or 'draw'
    expiresAt,
    createdAt: Date.now()
  }), 'EX', 120); // Auto-expire after 2 minutes
  logger.info(`[LowCard] Timer set for room ${roomId}: ${phase} expires at ${new Date(expiresAt).toISOString()}`);
};

const getTimer = async (roomId) => {
  const redis = getRedisClient();
  const timerKey = TIMER_KEY(roomId);
  const data = await redis.get(timerKey);
  return data ? JSON.parse(data) : null;
};

const clearTimer = async (roomId) => {
  const redis = getRedisClient();
  const timerKey = TIMER_KEY(roomId);
  await redis.del(timerKey);
  logger.info(`[LowCard] Timer cleared for room ${roomId}`);
};

// Reset stuck game (for admin/CS only)
const resetGame = async (roomId, byUsername) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) {
    return { success: false, message: 'No active game to reset.' };
  }
  
  const game = JSON.parse(gameData);
  
  // Refund all players
  for (const player of game.players) {
    if (!player.isEliminated) {
      await addCredits(player.userId, game.entryAmount, player.username, `LowCard Refund - Game reset by ${byUsername}`);
    }
  }
  
  // Clear game state and timer
  await redis.del(gameKey);
  await clearTimer(roomId);
  await clearDeck(roomId);
  
  logger.info(`[LowCard] Game reset in room ${roomId} by ${byUsername}. Refunded ${game.players.length} players.`);
  
  return { 
    success: true, 
    message: `Game reset. ${game.players.length} player(s) refunded ${game.entryAmount} COINS each.` 
  };
};

// Check and cleanup stale games
const checkAndCleanupStaleGame = async (roomId) => {
  const redis = getRedisClient();
  const gameKey = `lowcard:game:${roomId}`;
  
  const gameData = await redis.get(gameKey);
  if (!gameData) return null;
  
  const game = JSON.parse(gameData);
  
  // Check if game is stuck (waiting status past joinDeadline + buffer)
  if (game.status === 'waiting' && game.joinDeadline) {
    const now = Date.now();
    if (now > game.joinDeadline + STALE_GAME_TIMEOUT) {
      logger.info(`[LowCard] Cleaning up stale game in room ${roomId} (expired ${Math.round((now - game.joinDeadline) / 1000)}s ago)`);
      
      // Refund players and cleanup
      for (const player of game.players) {
        await addCredits(player.userId, game.entryAmount, player.username, 'LowCard Refund - Game expired (timeout)');
      }
      
      await redis.del(gameKey);
      await clearTimer(roomId);
      await clearDeck(roomId);
      
      return { cleaned: true, message: 'Previous game expired. Credits refunded. You can start a new game with !start' };
    }
  }
  
  return null;
};

module.exports = {
  startGame,
  joinGame,
  beginGame,
  drawCardForPlayer,
  tallyRound,
  autoDrawForTimeout,
  isBotActive,
  addBotToRoom,
  removeBotFromRoom,
  getBotStatus,
  isRoomAdmin,
  isRoomManaged,
  stopGame,
  getActiveGame,
  allPlayersDrawn,
  setTimer,
  getTimer,
  clearTimer,
  resetGame,
  checkAndCleanupStaleGame,
  TIMER_KEY,
  COUNTDOWN_DELAY,
  JOIN_TIMEOUT,
  DRAW_TIMEOUT
};
