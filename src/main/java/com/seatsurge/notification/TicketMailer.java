package com.seatsurge.notification;

/** Outgoing email port (SMTP in the app, a recorder in tests). */
public interface TicketMailer {

    void send(String to, String subject, String body);
}
