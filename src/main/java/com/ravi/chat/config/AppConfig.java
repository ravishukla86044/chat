package com.ravi.chat.config;

/**
 * Application configuration sourced from environment variables, with defaults
 * suitable for local docker-compose. Kept tiny and explicit on purpose.
 */
public record AppConfig(
        String dbHost,
        int dbPort,
        String dbName,
        String dbUser,
        String dbPassword,
        int serverPort) {

    public static AppConfig fromEnv() {
        return new AppConfig(
                env("DB_HOST", "localhost"),
                Integer.parseInt(env("DB_PORT", "3306")),
                env("DB_NAME", "chat"),
                env("DB_USER", "chat"),
                env("DB_PASSWORD", "chatpass"),
                Integer.parseInt(env("SERVER_PORT", "8080")));
    }

    /** JDBC URL used by Flyway to run migrations. */
    public String jdbcUrl() {
        return "jdbc:mysql://" + dbHost + ":" + dbPort + "/" + dbName;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
