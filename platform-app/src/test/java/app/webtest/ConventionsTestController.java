package app.webtest;

import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiResponse;
import app.platformapi.Cursors;
import app.platformapi.ErrorCode;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that exist only in tests. They exercise the API conventions (envelope, paging, validation, every kind
 * of failure) without waiting for a real feature. Deliberately outside {@code app.platform}, so the application's
 * component scan, the architecture rules and the OpenAPI document never see it.
 *
 * <p>The failing endpoints throw messages stuffed with things that must never reach a client.
 */
@RestController
@RequestMapping("/api/v1/test")
public class ConventionsTestController {

    /** Text that stands for internal detail. Tests assert that it never appears in a response. */
    public static final String LEAKY_DETAIL =
            "SECRET-INTERNAL jdbc:postgresql://db.internal:5432/platform password=hunter2 user-a@example.test";

    private static final int TOTAL_ITEMS = 5;

    /** A trivial resource. */
    public record Item(String id, String name) {
    }

    /** Request body with constraints. */
    public record CreateItem(@NotBlank String name, @Min(1) Integer quantity) {
    }

    @GetMapping("/item")
    public ApiResponse<Item> item() {
        return ApiResponse.of(new Item("item-1", "first"));
    }

    @GetMapping("/items")
    public ApiPageResponse<Item> items(PageRequest page) {
        int start = Cursors.decode(page.cursor()).map(Integer::parseInt).orElse(0);
        int end = Math.min(start + page.limit(), TOTAL_ITEMS);
        List<Item> items = IntStream.range(start, end).mapToObj(i -> new Item("item-" + i, "name-" + i)).toList();
        Pagination pagination = end < TOTAL_ITEMS
                ? Pagination.more(page.limit(), Cursors.encode(Integer.toString(end)))
                : Pagination.last(page.limit());
        return ApiPageResponse.of(items, pagination);
    }

    @PostMapping("/items")
    public ApiResponse<Item> create(@Valid @RequestBody CreateItem body) {
        return ApiResponse.of(new Item("item-new", body.name()));
    }

    @GetMapping("/param")
    public ApiResponse<String> param(@RequestParam @Min(1) int n) {
        return ApiResponse.of("n=" + n);
    }

    @GetMapping("/not-found")
    public ApiResponse<Item> notFound() {
        throw ApiException.notFound("The item was not found.");
    }

    @GetMapping("/unavailable")
    public ApiResponse<Item> unavailable() {
        throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @GetMapping("/concurrent")
    public ApiResponse<Item> concurrent() {
        throw new OptimisticLockingFailureException(LEAKY_DETAIL);
    }

    @GetMapping("/boom")
    public ApiResponse<Item> boom() {
        throw new IllegalStateException(LEAKY_DETAIL);
    }

    @GetMapping("/sql")
    public ApiResponse<Item> sql() {
        SQLException cause = new SQLException(LEAKY_DETAIL + " Key (email)=(user-a@example.test) exists", "23505");
        throw new DataIntegrityViolationException(LEAKY_DETAIL, cause);
    }

    @GetMapping("/database-down")
    public ApiResponse<Item> databaseDown() {
        throw new CannotCreateTransactionException(LEAKY_DETAIL);
    }
}
