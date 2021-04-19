package io.dockstore.webservice.helpers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Period;

import io.dockstore.webservice.core.DeletedUsername;
import io.dockstore.webservice.jdbi.DeletedUsernameDAO;

public final class DeletedUserHelper {
    // NIH suggests a 3 year limit before allowing username reuse.
    private static final Period NO_REUSE_TIME_LIMIT = Period.ofYears(3);
    private DeletedUserHelper() {

    }

    public static boolean nonReusableUsernameFound(String username, DeletedUsernameDAO deletedUsernameDAO) {
        LocalDateTime threeYearsAgoDateTime = LocalDateTime.now().minus(NO_REUSE_TIME_LIMIT);
        Timestamp threeYearsAgoTimestamp = Timestamp.valueOf(threeYearsAgoDateTime);
        DeletedUsername deletedUsername = deletedUsernameDAO.findNonReusableUsername(username, threeYearsAgoTimestamp);
        if (deletedUsername != null) {
            return true;
        }
        return false;
    }
}
