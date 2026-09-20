package com.fkurevija.pingring.coordination

/** Thrown when an event id does not exist, or has already expired and been purged. */
class EventNotFoundException(message: String = "Event not found") : NoSuchElementException(message)

/** Thrown when a peer id is not a member of the referenced event. */
class MemberNotFoundException(message: String = "Member not found") : NoSuchElementException(message)

/** Thrown when a peer id already belongs to a different, still-active event. */
class PeerAlreadyActiveException(message: String) : IllegalStateException(message)

/** Thrown when a non-host peer id attempts a host-only action (e.g. ending an event). */
class ForbiddenHostActionException(
    message: String = "Only the host peer may perform this action"
) : IllegalStateException(message)

/** Thrown for malformed or invalid request payloads. */
class ValidationException(message: String) : IllegalArgumentException(message)
