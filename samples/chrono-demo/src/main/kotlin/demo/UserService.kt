package demo

import com.chronotrace.sdk.ChronoLogger

class UserService {
    suspend fun processUser(userId: Long, name: String): String {
        val result = "processed:$userId"
        val timestamp = System.currentTimeMillis()
        ChronoLogger.info("User processed", mapOf(
            "userId" to userId,
            "name" to name,
            "result" to result,
            "timestamp" to timestamp
        ))
        return result
    }

    suspend fun getUser(userId: Long): String {
        val name = "User-$userId"
        ChronoLogger.debug("Getting user", mapOf("userId" to userId, "name" to name))
        return name
    }
}