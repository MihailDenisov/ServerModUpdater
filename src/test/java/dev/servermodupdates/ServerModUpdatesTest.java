package dev.servermodupdates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerModUpdatesTest {
    @Test
    void commandsRequireOperatorPermissionLevelTwo() {
        assertEquals(2, CommandPolicy.REQUIRED_PERMISSION_LEVEL);
    }
}
