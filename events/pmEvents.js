const logger = require('../utils/logger');
const messageService = require('../services/messageService');
const userService = require('../services/userService');
const { getUserSocket, getPresence, getSession } = require('../utils/presence');
const { generateMessageId } = require('../utils/idGenerator');
const { checkGlobalRateLimit } = require('../utils/floodControl');
const { XP_REWARDS, addXp } = require('../utils/xpLeveling');
const { MIG33_CMD } = require('../utils/cmdMapping');

module.exports = (io, socket) => {
  const sendPrivateMessage = async (data) => {
    try {
      let { fromUserId, fromUsername, toUserId, toUsername, message, clientMsgId } = data;

      logger.info('📩 PM:SEND received:', { fromUserId, fromUsername, toUserId, toUsername, message: message?.substring(0, 50) });

      // Allow sending by username if toUserId not provided
      if (!toUserId && toUsername) {
        const recipient = await userService.getUserByUsername(toUsername);
        if (recipient) {
          toUserId = recipient.id;
        } else {
          socket.emit('error', { message: 'User not found' });
          return;
        }
      }

      if (!fromUserId || !toUserId || !message) {
        socket.emit('error', { message: 'Missing required fields' });
        return;
      }

      if (message.length > 2000) {
        socket.emit('error', { message: 'Message too long (max 2000 characters)' });
        return;
      }

      const rateCheck = await checkGlobalRateLimit(fromUserId);
      if (!rateCheck.allowed) {
        socket.emit('system:message', {
          message: rateCheck.message,
          type: 'warning'
        });
        return;
      }

      // Check recipient's presence status - block messages to busy/away users
      const targetUser = await userService.getUserById(toUserId);
      const targetName = targetUser?.username || toUsername;
      const targetPresence = await getPresence(targetName);
      
      logger.info(`🔍 PM presence check: ${targetName} = ${targetPresence}`);
      
      if (targetPresence === 'busy') {
        logger.info(`❌ PM blocked: ${targetName} is busy`);
        socket.emit('pm:error', {
          toUserId: String(toUserId),
          toUsername: targetName,
          message: `Error: ${targetName} is busy`,
          type: 'busy'
        });
        return;
      }
      
      if (targetPresence === 'away') {
        logger.info(`❌ PM blocked: ${targetName} is away`);
        socket.emit('pm:error', {
          toUserId: String(toUserId),
          toUsername: targetName,
          message: `Error: ${targetName} is away`,
          type: 'away'
        });
        return;
      }
      
      if (targetPresence === 'offline') {
        logger.info(`❌ PM blocked: ${targetName} is offline`);
        socket.emit('pm:error', {
          toUserId: String(toUserId),
          toUsername: targetName,
          message: `Error: ${targetName} is offline`,
          type: 'offline'
        });
        return;
      }

      // Handle commands in PM
      if (message.startsWith('/')) {
        const parts = message.slice(1).split(' ');
        const cmdKey = parts[0].toLowerCase();

        // Handle /me command
        if (cmdKey === 'me') {
          const actionText = parts.slice(1).join(' ');
          const formatted = actionText ? `** ${fromUsername} ${actionText} **` : `** ${fromUsername} **`;
          
          const cmdMessage = {
            id: clientMsgId || generateMessageId(),
            fromUserId,
            toUserId,
            fromUsername,
            toUsername,
            message: formatted,
            messageType: 'cmdMe',
            type: 'cmdMe',
            timestamp: new Date().toISOString(),
            isRead: false
          };

          io.to(`user:${toUserId}`).emit('pm:receive', cmdMessage);
          io.to(`user:${fromUserId}`).emit('pm:sent', cmdMessage);
          return;
        }

        // Handle /gift command - Send actual gift with credit deduction
        if (cmdKey === 'gift') {
          // Supports:
          // /gift <giftname> (sends to the current PM recipient)
          // /gift <giftname> <username> (explicit recipient)
          
          let giftName;
          let explicitRecipientUsername = null;
          
          // Check if there are at least two parts after /gift
          // parts[0] is 'gift'
          if (parts.length >= 3) {
            // Check if last part is the toUsername
            const lastPart = parts[parts.length - 1].toLowerCase();
            if (lastPart === toUsername.toLowerCase()) {
              giftName = parts.slice(1, -1).join(' ').trim();
              explicitRecipientUsername = toUsername;
            } else {
              giftName = parts.slice(1).join(' ').trim();
            }
          } else {
            giftName = parts.slice(1).join(' ').trim();
          }

          if (!giftName) {
            socket.emit('system:message', {
              message: `Usage: /gift <giftname> [username]`,
              type: 'warning'
            });
            return;
          }

          try {
            const { query } = require('../db/db');
            const creditService = require('../services/creditService');
            const profileService = require('../services/profileService');
            const notificationService = require('../services/notificationService');
            
            // Find gift by name (case-insensitive)
            const giftResult = await query(
              `SELECT id, name, price, image_url FROM gifts WHERE LOWER(name) = LOWER($1) LIMIT 1`,
              [giftName]
            );
            
            if (giftResult.rows.length === 0) {
              socket.emit('system:message', {
                message: `Gift "${giftName}" not found`,
                type: 'warning'
              });
              return;
            }
            
            const gift = giftResult.rows[0];
            
            // Check sender's credits
            const senderCredits = await creditService.getBalance(fromUserId);
            if (senderCredits < gift.price) {
              socket.emit('system:message', {
                message: `Not enough credits. You need ${gift.price} COINS (you have ${senderCredits})`,
                type: 'warning'
              });
              return;
            }
            
            // Deduct credits from sender
            await creditService.deductCredits(fromUserId, gift.price, 'gift', `Gift sent: ${gift.name} to ${toUsername}`);
            
            // Record the gift in database
            await profileService.sendGift(fromUserId, toUserId, gift.name, gift.image_url, gift.price);
            
            // Recipient info
            const recipient = await userService.getUserById(toUserId);
            const recipientUsername = recipient?.username || toUsername;
            
            // Get sender and receiver levels
            const sender = await userService.getUserById(fromUserId);
            const senderLevel = sender?.level || 1;
            const receiverLevel = recipient?.level || 1;
            
            // Send notification to recipient
            try {
              const giftNotification = {
                id: generateMessageId(),
                type: 'gift',
                fromUserId,
                fromUsername,
                message: `${fromUsername} sent you a gift [${gift.name}]`,
                giftName: gift.name,
                giftImage: gift.image_url
              };
              await notificationService.addNotification(recipientUsername, giftNotification);
              
              // Real-time notification
              io.to(`user:${toUserId}`).emit('notif:gift', giftNotification);
            } catch (notifErr) {
              console.error('Error sending gift notification:', notifErr.message);
            }
            
            // Send message to both users - same format as room gift
            const giftMessage = `<< ${fromUsername} [${senderLevel}] gives a ${gift.name} [GIFT_IMAGE:${gift.image_url || '🎁'}] to ${recipientUsername} [${receiverLevel}]`;
            
            const cmdMessage = {
              id: clientMsgId || generateMessageId(),
              fromUserId,
              toUserId,
              fromUsername,
              toUsername: recipientUsername,
              message: giftMessage,
              messageType: 'cmdGift', // IMPORTANT: Front-end listens for this
              type: 'cmdGift',
              giftName: gift.name,
              giftImage: gift.image_url,
              giftCost: gift.price,
              messageColor: '#FF69B4', // MIG33 style pink for gifts
              timestamp: new Date().toISOString(),
              isRead: false
            };

            io.to(`user:${toUserId}`).emit('pm:receive', cmdMessage);
            io.to(`user:${fromUserId}`).emit('pm:sent', cmdMessage);
            
            logger.info(`🎁 Gift sent via PM: ${fromUsername} -> ${recipientUsername} [${gift.name}] (-${gift.price} COINS)`);
          } catch (giftError) {
            console.error('Error processing /gift command in PM:', giftError);
            socket.emit('system:message', {
              message: `Failed to send gift`,
              type: 'warning'
            });
          }
          return;
        }

        // Handle /roll command
        if (cmdKey === 'roll') {
          const rollResult = Math.floor(Math.random() * 100) + 1;
          const formatted = `** ${fromUsername} rolls ${rollResult} **`;

          const cmdMessage = {
            id: clientMsgId || generateMessageId(),
            fromUserId,
            toUserId,
            fromUsername,
            toUsername,
            message: formatted,
            messageType: 'cmdRoll',
            type: 'cmdRoll',
            timestamp: new Date().toISOString(),
            isRead: false
          };

          io.to(`user:${toUserId}`).emit('pm:receive', cmdMessage);
          io.to(`user:${fromUserId}`).emit('pm:sent', cmdMessage);
          return;
        }

        // Handle other MIG33 commands (without target - in PM, target is always the other user)
        const cmd = MIG33_CMD[cmdKey];
        if (cmd) {
          const text = cmd.requiresTarget ? cmd.message(fromUsername, toUsername) : cmd.message(fromUsername);
          
          const cmdMessage = {
            id: clientMsgId || generateMessageId(),
            fromUserId,
            toUserId,
            fromUsername,
            toUsername,
            message: `** ${text} **`,
            messageType: 'cmd',
            type: 'cmd',
            timestamp: new Date().toISOString(),
            isRead: false
          };

          io.to(`user:${toUserId}`).emit('pm:receive', cmdMessage);
          io.to(`user:${fromUserId}`).emit('pm:sent', cmdMessage);
          return;
        }
      }

      let recipientUsername = toUsername;
      if (!recipientUsername) {
        const recipient = await userService.getUserById(toUserId);
        if (!recipient) {
          socket.emit('error', { message: 'User not found' });
          return;
        }
        recipientUsername = recipient.username;
      }

      // Check privacy settings - does recipient allow PM from this sender?
      const profileService = require('../services/profileService');
      const canSendPM = await profileService.canSendPrivateMessage(fromUserId, toUserId);
      if (!canSendPM.allowed) {
        socket.emit('pm:blocked', {
          message: canSendPM.reason || 'User does not accept private messages from you',
          toUsername: recipientUsername || toUsername
        });
        return;
      }

      // Check if sender blocked by recipient using Redis cache (efficient)
      const { getRedisClient } = require('../redis');
      const redis = getRedisClient();
      let isBlocked = false;
      
      try {
        const cachedBlocked = await redis.get(`user:blocks:${toUserId}`);
        if (cachedBlocked) {
          const blockedIds = JSON.parse(cachedBlocked);
          isBlocked = blockedIds.includes(fromUserId);
        } else {
          // Fallback to database query if cache miss
          const profileService = require('../services/profileService');
          const blockedUsers = await profileService.getBlockedUsers(toUserId);
          const blockedIds = blockedUsers.map(u => u.id);
          isBlocked = blockedIds.includes(fromUserId);
          
          // Cache for 5 minutes (node-redis v4 uses setEx)
          await redis.set(`user:blocks:${toUserId}`, JSON.stringify(blockedIds), { EX: 300 });
        }
      } catch (err) {
        console.warn('Redis cache error, defaulting to allow PM:', err.message);
        isBlocked = false;
      }
      
      if (isBlocked) {
        socket.emit('pm:blocked', {
          message: 'You has blocked',
          toUsername: recipientUsername
        });
        return;
      }

      // Presence check is done earlier in the function (lines 49-76)

      // Save private message to database for queue/history
      const savedMessage = await messageService.savePrivateMessage(
        fromUserId, toUserId, fromUsername, recipientUsername, message
      );
      
      await addXp(fromUserId, XP_REWARDS.SEND_MESSAGE, 'send_pm', io);

      // Get sender's role and avatar for username color and display
      const senderUser = await userService.getUserById(fromUserId);
      const fromRole = senderUser?.role || 'user';
      const fromAvatar = senderUser?.avatar || null;

      const messageData = {
        id: savedMessage?.id || clientMsgId || generateMessageId(),
        fromUserId,
        toUserId,
        fromUsername,
        toUsername: recipientUsername,
        message,
        messageType: 'pm',
        fromRole,
        fromAvatar,
        timestamp: savedMessage?.created_at || new Date().toISOString(),
        isRead: false
      };

      const { setPMLastMessage, addUserPM } = require('../utils/redisUtils');
      await setPMLastMessage(fromUsername, recipientUsername, messageData);
      await addUserPM(fromUsername, recipientUsername);
      await addUserPM(recipientUsername, fromUsername);

      // 🔑 EMIT TO USER CHANNEL - ALL TABS receive PM
      io.to(`user:${toUserId}`).emit('pm:receive', messageData);
      io.to(`user:${toUserId}`).emit('dm:receive', messageData); // Added for backward compatibility
      logger.info(`📩 PM delivered to ALL tabs of user:${toUserId} (${recipientUsername})`);

      // Echo back to sender's all tabs
      io.to(`user:${fromUserId}`).emit('pm:sent', messageData);
      io.to(`user:${fromUserId}`).emit('dm:sent', messageData); // Added for backward compatibility

      // 🔑 Emit chatlist update using USER ID (not username) to match socket room
      const chatlistPayload = {
        type: 'pm',
        username: fromUsername,
        userId: fromUserId,
        avatar: fromAvatar,
        lastMessage: {
          message: messageData.message,
          fromUsername: messageData.fromUsername,
          toUsername: messageData.toUsername,
          timestamp: messageData.timestamp
        }
      };

      io.to(`user:${toUserId}`).emit('chatlist:update', chatlistPayload);
      // Backward compatibility for 'dm' type
      io.to(`user:${toUserId}`).emit('chatlist:update', { ...chatlistPayload, type: 'dm' });

      const senderChatlistPayload = {
        type: 'pm',
        username: recipientUsername,
        userId: toUserId,
        lastMessage: {
          message: messageData.message,
          fromUsername: messageData.fromUsername,
          toUsername: messageData.toUsername,
          timestamp: messageData.timestamp
        }
      };

      io.to(`user:${fromUserId}`).emit('chatlist:update', senderChatlistPayload);
      // Backward compatibility
      io.to(`user:${fromUserId}`).emit('chatlist:update', { ...senderChatlistPayload, type: 'dm' });

    } catch (error) {
      console.error('Error sending private message:', error);
      socket.emit('error', { message: 'Failed to send private message' });
    }
  };

  const getPrivateMessages = async (data) => {
    try {
      const { userId, otherUserId, limit = 50, offset = 0 } = data;

      if (!userId || !otherUserId) {
        socket.emit('error', { message: 'Missing required fields' });
        return;
      }

      const messages = await messageService.getPrivateMessages(userId, otherUserId, limit, offset);

      await messageService.markMessagesAsRead(userId, otherUserId);

      socket.emit('pm:messages', {
        otherUserId,
        messages,
        hasMore: messages.length === limit
      });

    } catch (error) {
      console.error('Error getting private messages:', error);
      socket.emit('error', { message: 'Failed to get private messages' });
    }
  };

  const getUnreadMessages = async (data) => {
    try {
      const { userId } = data;

      if (!userId) {
        socket.emit('error', { message: 'User ID required' });
        return;
      }

      const unread = await messageService.getUnreadMessages(userId);

      socket.emit('pm:unread', {
        messages: unread,
        count: unread.length
      });

    } catch (error) {
      console.error('Error getting unread messages:', error);
      socket.emit('error', { message: 'Failed to get unread messages' });
    }
  };

  const markAsRead = async (data) => {
    try {
      const { userId, fromUserId } = data;

      await messageService.markMessagesAsRead(userId, fromUserId);

      socket.emit('pm:marked:read', {
        fromUserId
      });

    } catch (error) {
      console.error('Error marking messages as read:', error);
      socket.emit('error', { message: 'Failed to mark messages as read' });
    }
  };

  const getConversations = async (data) => {
    try {
      const { userId, limit = 20 } = data;

      if (!userId) {
        socket.emit('error', { message: 'User ID required' });
        return;
      }

      const conversations = await messageService.getRecentConversations(userId, limit);

      socket.emit('pm:conversations', {
        conversations
      });

    } catch (error) {
      console.error('Error getting conversations:', error);
      socket.emit('error', { message: 'Failed to get conversations' });
    }
  };

  socket.on('pm:send', sendPrivateMessage);
  socket.on('pm:messages:get', getPrivateMessages);
  socket.on('pm:unread:get', getUnreadMessages);
  socket.on('pm:mark:read', markAsRead);
  socket.on('pm:conversations:get', getConversations);
};