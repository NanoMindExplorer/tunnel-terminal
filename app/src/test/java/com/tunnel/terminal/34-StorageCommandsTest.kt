package com.tunnel.terminal

import org.junit.Assert.*
import org.junit.Test

class StorageCommandsTest {
    @Test
    fun `detects storage commands`() {
        assertTrue(StorageCommands.isStorageCommand("setup-storage"))
        assertTrue(StorageCommands.isStorageCommand("storage-ls sub"))
        assertTrue(StorageCommands.isStorageCommand("storage-put a.txt"))
        assertFalse(StorageCommands.isStorageCommand("ls"))
        assertFalse(StorageCommands.isStorageCommand("cd /"))
    }
}
