package app.platform.notification.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.Cursors;
import app.platformapi.NotificationSummaryView;
import app.platformapi.NotificationView;
import app.platformapi.Pagination;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signed-in member's own notices (Sprint 25, ADR-0154): the list, the count for the bell, and marking read. Every
 * statement names the member from the token and the host, never one the request names, so one member can never read,
 * count or mark another's notice; a notice of another member answers "not found", exactly like one that does not exist.
 * Answered only on an organization host (the platform host answers "not found").
 */
@Service
class NotificationService {

    private final NoticeStore store;
    private final NoticeTexts texts;
    private final NoticeProperties properties;
    private final TenantContexts contexts;

    NotificationService(NoticeStore store, NoticeTexts texts, NoticeProperties properties, TenantContexts contexts) {
        this.store = store;
        this.texts = texts;
        this.properties = properties;
        this.contexts = contexts;
    }

    /** The member who asks, or "not found" on the platform host or without a member. */
    UUID member() {
        TenantContext context = contexts.current().filter(current -> current.membershipId() != null).orElseThrow(
                () -> ApiException.notFound("This does not exist."));
        return context.userId();
    }

    @Transactional(readOnly = true)
    ApiPageResponse<NotificationView> list(UUID member, boolean unreadOnly, Integer limit, String cursor) {
        int size = limit == null ? properties.pageSize() : Math.max(1, Math.min(limit, properties.maxPageSize()));
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null) {
            String[] parts = Cursors.decode(cursor).map(text -> text.split("\\|")).orElseThrow(
                    () -> ApiException.validation("cursor", "This is not a cursor of this list."));
            try {
                before = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw ApiException.validation("cursor", "This is not a cursor of this list.");
            }
        }
        List<NoticeStore.Row> found = store.page(member, unreadOnly, before, beforeId, size + 1);
        boolean more = found.size() > size;
        List<NoticeStore.Row> page = more ? found.subList(0, size) : found;
        NoticeTexts.Context words = texts.context();
        List<NotificationView> views = page.stream().map(row -> view(row, words)).toList();
        NoticeStore.Row last = page.isEmpty() ? null : page.get(page.size() - 1);
        Pagination pagination = more && last != null
                ? Pagination.more(size, Cursors.encode(last.createdAt() + "|" + last.id())) : Pagination.last(size);
        return ApiPageResponse.of(views, pagination);
    }

    @Transactional(readOnly = true)
    NotificationSummaryView summary(UUID member) {
        return new NotificationSummaryView(store.unread(member));
    }

    @Transactional
    void markRead(UUID member, UUID id) {
        if (!store.owns(member, id)) {
            throw ApiException.notFound("This notice does not exist.");
        }
        store.markRead(member, id, member);
    }

    @Transactional
    int markAllRead(UUID member) {
        return store.markAllRead(member, member);
    }

    private static NotificationView view(NoticeStore.Row row, NoticeTexts.Context words) {
        NoticeTexts.Rendered text = words.render(row);
        String category = app.platform.sharedkernel.notice.NoticeKind.fromCode(row.kind())
                .map(kind -> kind.category().name()).orElse("ACCOUNT");
        return new NotificationView(row.id(), row.kind(), category, text.title(), text.body(), text.href(),
                row.readAt() != null, row.createdAt());
    }
}
