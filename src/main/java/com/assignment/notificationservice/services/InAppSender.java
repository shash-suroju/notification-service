package com.assignment.notificationservice.services;

import com.assignment.notificationservice.dtos.OutboundMessage;
import com.assignment.notificationservice.dtos.SendResult;
import com.assignment.notificationservice.models.enums.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.UUID;

/**
 * IN_APP "delivery" is a row in our own inbox table, so this sender is real in every profile.
 *
 * <p>Idempotent via {@code INSERT ... ON CONFLICT (notification_id) DO NOTHING}: a second
 * delivery of the same notification is a no-op acknowledged as success. Doing it in one
 * statement avoids catching a unique violation, which would abort any enclosing transaction.
 */
@Component
@RequiredArgsConstructor
public class InAppSender implements ChannelSender {

    private static final String INSERT_SQL = """
            INSERT INTO in_app_message (id, tenant_id, recipient, notification_id, title, body, created_at)
            SELECT :id, n.tenant_id, :recipient, n.id, :title, :body, :now
            FROM notification n
            WHERE n.id = :notificationId
            ON CONFLICT (notification_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public Channel channel() {
        return Channel.IN_APP;
    }

    @Override
    public SendResult send(OutboundMessage msg) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("recipient", msg.recipient())
                .addValue("notificationId", msg.notificationId())
                .addValue("title", msg.subject(), Types.VARCHAR)
                .addValue("body", msg.body())
                .addValue("now", Timestamp.from(clock.instant()), Types.TIMESTAMP));

        if (inserted == 1) {
            return new SendResult.Success("inapp-" + id);
        }
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM in_app_message WHERE notification_id = :notificationId",
                new MapSqlParameterSource("notificationId", msg.notificationId()), Integer.class);
        if (existing != null && existing > 0) {
            return new SendResult.Success("inapp-duplicate");
        }
        return new SendResult.PermanentFailure("BAD_PAYLOAD",
                "Notification " + msg.notificationId() + " no longer exists");
    }
}
