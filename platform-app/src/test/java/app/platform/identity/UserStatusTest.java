package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The user lifecycle. The database trigger must agree with this table for every pair of states ({@code UsersIT}). */
class UserStatusTest {

    @Test
    void theLegalMovesAreExactlyTheDocumentedOnes() {
        assertThat(UserStatus.INVITED.allowedTargets()).containsExactlyInAnyOrder(UserStatus.ACTIVE,
                UserStatus.DEACTIVATED);
        assertThat(UserStatus.ACTIVE.allowedTargets()).containsExactlyInAnyOrder(UserStatus.SUSPENDED,
                UserStatus.DEACTIVATED);
        assertThat(UserStatus.SUSPENDED.allowedTargets()).containsExactlyInAnyOrder(UserStatus.ACTIVE,
                UserStatus.DEACTIVATED);
        assertThat(UserStatus.DEACTIVATED.allowedTargets()).isEmpty();
    }

    @Test
    void onlyAnActiveUserCanSignIn() {
        for (UserStatus status : UserStatus.values()) {
            assertThat(status.canSignIn()).as(status.name()).isEqualTo(status == UserStatus.ACTIVE);
        }
    }

    @Test
    void stayingInTheSameStateIsNotAMove() {
        for (UserStatus status : UserStatus.values()) {
            assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    void aDeactivatedUserNeverComesBack() {
        Set<UserStatus> reachable = UserStatus.DEACTIVATED.allowedTargets();

        assertThat(reachable).isEmpty();
    }
}
