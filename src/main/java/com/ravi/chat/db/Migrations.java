package com.ravi.chat.db;

import com.ravi.chat.config.AppConfig;
import org.flywaydb.core.Flyway;

/**
 * Runs Flyway schema migrations. Executed once at startup, before the HTTP
 * server begins accepting traffic. Flyway uses a plain JDBC connection; the
 * application itself uses the Vert.x reactive client.
 */
public final class Migrations {

    private Migrations() {
    }

    public static void run(AppConfig config) {
        Flyway.configure()
                .dataSource(config.jdbcUrl(), config.dbUser(), config.dbPassword())
                .loadDefaultConfigurationFiles()
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}
