package io.github.lukepopwell.tracker

/**
 * How one delivery ended, as far as whoever holds the payload is concerned: gone out, never going
 * to go out, or worth another try. [Client]'s public functions still answer with a plain Boolean;
 * [Client.outcome] is what tells the last two apart.
 */
internal enum class DeliveryResult {
    /** Any 2xx. */
    DELIVERED,

    /**
     * A 4xx other than 408 and 429: the server will never take this payload (a bad key, a plan
     * without the feature, a body it can't parse or that is too large), so sending it again only
     * repeats the answer.
     */
    REJECTED,

    /** A network error, a timeout, a 5xx, a 408 or a 429: the same payload may well go through later. */
    FAILED,
    ;

    /** Why a batch that ended this way is let go, for the debug log. */
    fun dropReason(): String =
        if (this == REJECTED) "the server rejected it" else "delivery failed $MAX_FAILED_FLUSHES times in a row"

    companion object {
        /**
         * How many flushes in a row may fail before a batch is dropped anyway, so an endpoint that
         * stays down can't make every later flush carry (and a full buffer keep refusing) more and
         * more.
         */
        const val MAX_FAILED_FLUSHES = 5

        fun ofStatus(status: Int): DeliveryResult = when {
            status in 200..299 -> DELIVERED
            status in 400..499 && status != 408 && status != 429 -> REJECTED
            else -> FAILED
        }
    }
}
