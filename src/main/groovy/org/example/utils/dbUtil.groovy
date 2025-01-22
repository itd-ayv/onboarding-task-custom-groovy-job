package org.example.utils

import java.sql.Connection
import java.sql.DriverManager

class dbUtil {
    static Connection connection = null
    private static String dbUrl
    private static String dbUser
    private static String dbPassword

    static Connection connect() {
        try {
            Properties properties = new Properties()
            FileInputStream input = new FileInputStream("src/main/resources/application.properties")
            properties.load(input)
            dbUrl = properties.getProperty("db.url")
            dbUser = properties.getProperty("db.username")
            dbPassword = properties.getProperty("db.password")
            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
            println "Connected to the database successfully!"
        } catch (Exception e) {
            e.printStackTrace()
        }
        return connection
    }


    static void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close()
                println "Connection closed successfully!"
            }
        } catch (Exception e) {
            e.printStackTrace()
        }
    }
}
