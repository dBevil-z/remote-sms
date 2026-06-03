package dev.dbevil.remotesms;

import java.util.UUID;

final class SmsPayload {
    final String id;
    final String sender;
    final String body;
    final long receivedAt;
    final int simSlot;

    SmsPayload(String sender, String body, long receivedAt, int simSlot) {
        this.id = UUID.nameUUIDFromBytes((sender + "|" + receivedAt + "|" + body).getBytes()).toString();
        this.sender = sender == null ? "" : sender;
        this.body = body == null ? "" : body;
        this.receivedAt = receivedAt;
        this.simSlot = simSlot;
    }
}

