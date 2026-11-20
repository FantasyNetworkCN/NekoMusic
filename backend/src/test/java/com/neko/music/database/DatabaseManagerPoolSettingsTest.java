package com.neko.music.database;

import com.neko.music.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerPoolSettingsTest {
    @Test
    void settingsLoadedAfterBootstrapReachTheExistingLivePool() throws Exception {
        ConfigManager config = new ConfigManager();
        HikariConfig bootstrap = new HikariConfig();
        bootstrap.setJdbcUrl("jdbc:sqlite::memory:");
        bootstrap.setMaximumPoolSize(1);
        bootstrap.setMinimumIdle(0);
        try (HikariDataSource pool = new HikariDataSource(bootstrap)) {
            DatabaseManager manager = new DatabaseManager(config);
            var field = DatabaseManager.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            field.set(manager, pool);

            config.applyOverrides(Map.of(
                    "performance.hikari_maximum_pool_size", "6",
                    "performance.hikari_minimum_idle", "2"));
            manager.applyRuntimePoolSettings();
            assertEquals(6, pool.getMaximumPoolSize());
            assertEquals(2, pool.getMinimumIdle());

            config.applyOverrides(Map.of(
                    "performance.hikari_maximum_pool_size", "3",
                    "performance.hikari_minimum_idle", "1"));
            manager.applyRuntimePoolSettings();
            assertEquals(3, pool.getMaximumPoolSize());
            assertEquals(1, pool.getMinimumIdle());
            try (var connection = manager.getConnection();
                 var statement = connection.prepareStatement("SELECT 1");
                 var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }
}
