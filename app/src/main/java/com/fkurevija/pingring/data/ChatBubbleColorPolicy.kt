package com.fkurevija.pingring.data

/**
 * Which colour a message bubble should be painted, given who sent it and what
 * colours their messages have carried.
 *
 * A message stores the sender's bubble colour as it was when they sent it. That
 * makes the colour a fact about the *past*, and it is the wrong fact: the colour
 * identifies a person so their messages can be found in a transcript, so it has
 * to track the person, not the moment they happened to write.
 *
 * The failure this exists to prevent is a member who changes their colour and
 * finds their transcript split down the middle - some bubbles in the old shade,
 * some in the new - with nothing in the history saying why. Two things then look
 * true and neither is: that there are two people talking, or that they changed
 * their mind about something they said. Both send the reader looking for a
 * person who is not there.
 *
 * The rule is "the colour this sender is *now*", resolved per sender:
 *
 *  - Our own messages use the colour we have chosen right now. We are the one
 *    authority on our own colour, so there is nothing to reconcile.
 *  - Everybody else's use the most recent colour they have been seen using.
 *    We cannot ask, so the newest thing we have actually observed from them is
 *    the best available answer - and it is an observation, not a guess.
 *  - With nothing observed, the message keeps the colour it arrived with.
 *
 * Legacy messages sent before colours existed carry no colour at all. They keep
 * the themed style only while we have never seen that sender use a colour - the
 * moment we have, even their oldest bubbles take it, because a themed bubble
 * sitting directly above a coloured one from the same person is itself the
 * split this is meant to remove.
 */
object ChatBubbleColorPolicy {

    /**
     * The colour to paint [message], or null for the themed legacy style.
     *
     * [latestColorByPeer] is the most recent colour observed per sender peer id,
     * normally built from the whole visible transcript. [localPeerId] and
     * [localColorArgb] describe us; either may be null while we are still
     * resolving who we are, in which case this falls back to observation like
     * anybody else.
     */
    fun colorFor(
        message: ChatMessage,
        localPeerId: String?,
        localColorArgb: Long?,
        latestColorByPeer: Map<String, Long>,
    ): Long? {
        val sender = message.senderPeerId
        val isMine = localPeerId != null && ChatMessageOwnership.isOwnMessage(sender, localPeerId)
        if (isMine) {
            // Our own current choice wins even if it is not yet on any message:
            // the member is looking at their colour right now, and the
            // transcript has to catch up to it rather than the other way round.
            localColorArgb?.let { return it }
        }
        return latestColorByPeer[sender] ?: message.colorArgb
    }

    /**
     * The newest colour seen for each sender, across every message in view.
     *
     * Built from the transcript in order, so the last message a person sent wins.
     * A sender whose every message is legacy (no colour) contributes nothing,
     * which is what lets those bubbles keep the themed style.
     */
    fun latestColorsBySender(messages: List<ChatMessage>): Map<String, Long> {
        val latest = HashMap<String, Long>()
        messages.forEach { message ->
            message.colorArgb?.let { latest[message.senderPeerId] = it }
        }
        return latest
    }
}