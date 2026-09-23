package dev.dsmirnov.photoframe.ui

import dev.dsmirnov.photoframe.device.FrameUnreachableException
import dev.dsmirnov.photoframe.device.NotPairedException
import dev.dsmirnov.photoframe.protocol.ProtocolException
import dev.dsmirnov.photoframe.protocol.client.FrameErrorCode
import dev.dsmirnov.photoframe.protocol.client.FrameException
import dev.dsmirnov.photoframe.protocol.pairing.PairingException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Turns a failure into a sentence the user can act on.
 *
 * The rules, from `Spec/00 - Initial/03 - UX.md` §7: name the frame rather than "the
 * device", say what to do next, and never surface a code, a hex string, an exception class,
 * or the bare word "error".
 *
 * @param onWifi lets the most common cause be reported as itself. Without Wi-Fi every
 *   failure looks like an unreachable frame, and telling someone their frame is asleep when
 *   their phone is on mobile data sends them to the wrong room.
 */
fun describeFailure(failure: Throwable, frameName: String, onWifi: Boolean): String = when {
    !onWifi && failure.isConnectivity() ->
        "You're not on Wi-Fi. Photo Frame sends photos over your home network."

    failure is NotPairedException ->
        "No frame is connected yet."

    failure is FrameUnreachableException ->
        "Can't reach $frameName. It may be off, asleep, or on a different network."

    failure is PairingException -> when (failure.reason) {
        PairingException.Reason.CODE_LENGTH -> "Friend codes are 7 to 20 digits."
        PairingException.Reason.CODE_DIGITS -> "Friend codes contain digits only."
        PairingException.Reason.REJECTED ->
            "That code didn't work. Codes expire — open Add friend on the frame again and " +
                "use the new code."
        PairingException.Reason.MALFORMED_CHALLENGE ->
            "The frame answered in a way Photo Frame didn't understand. Try pairing again."
        PairingException.Reason.UNSUPPORTED_PACE_VERSION ->
            "This frame uses a pairing method Photo Frame doesn't support yet."
    }

    failure is FrameException -> when (failure.errorCode) {
        FrameErrorCode.LIMIT_REACHED ->
            "The frame is full. Delete some photos on the frame, then try again."
        FrameErrorCode.UNAUTHORIZED, FrameErrorCode.PERMISSION_REQUIRED ->
            "The frame declined the request. You may need to pair again — open Add friend " +
                "on the frame."
        FrameErrorCode.DECLINED -> "The request was declined on the frame."
        FrameErrorCode.PHOTO_GONE -> "That photo is no longer on the frame."
        FrameErrorCode.NOT_FOUND -> "The frame couldn't find that photo."
        else -> "The frame couldn't complete that. Try again in a moment."
    }

    failure is ProtocolException -> describeProtocolFailure(failure, frameName)

    failure is SocketTimeoutException ->
        "$frameName didn't respond. It may be asleep."

    failure is IOException ->
        "The connection to $frameName was interrupted."

    else -> "Something went wrong reaching $frameName. Try again."
}

private fun describeProtocolFailure(failure: ProtocolException, frameName: String): String {
    val message = failure.message.orEmpty()
    return when {
        message.contains("issuer changed") ->
            "This frame's certificate changed. For safety Photo Frame won't connect. If you " +
                "reset the frame, remove it here and pair again."

        message.contains("unrecognised frame certificate issuer") ->
            "Photo Frame doesn't recognise the key that signed this frame's certificate. This " +
                "can happen with very new frames. You can allow it in Settings → Advanced, " +
                "but only if you're sure this is your frame."

        message.contains("differs from the saved pairing") ->
            "The device at that address isn't your paired frame."

        message.contains("issued for a different device") || message.contains("signature is invalid") ->
            "The device at that address didn't prove it's a Frameo frame."

        message.contains("replayed") || message.contains("authentication failed") ->
            "The connection to $frameName was interrupted."

        message.contains("predates the media gallery") ->
            "This frame's software is too old for Photo Frame to list its photos. Sending still works."

        message.contains("photo access has not been granted") ->
            "Photo Frame doesn't have permission to see the frame's photos yet."

        else -> "The connection to $frameName was interrupted."
    }
}

/** True when the failure is the kind that a missing network would have caused. */
private fun Throwable.isConnectivity(): Boolean =
    this is IOException || this is FrameUnreachableException

/**
 * Why a queued photo failed, in the same voice as [describeFailure]. The queue stores the
 * frame's numeric code only so this mapping can happen at display time.
 */
fun describeQueueFailure(item: dev.dsmirnov.photoframe.data.QueueItem, frameName: String): String {
    val code = item.frameErrorCode
    return when {
        code != null -> {
            val known = FrameErrorCode.entries.firstOrNull { it.code == code } ?: FrameErrorCode.UNKNOWN
            describeFailure(FrameException(known, code.toLong()), frameName, onWifi = true)
        }
        item.lastError == dev.dsmirnov.photoframe.send.MISSING_FILE ->
            "Photo Frame lost its prepared copy of this photo. Add it again."
        else -> "Couldn't reach $frameName after several tries."
    }
}
