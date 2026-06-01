package demo

import com.chronotrace.sdk.ChronoLogger
import com.chronotrace.sdk.withSpan

class OrderService {
    private val userService = UserService()

    suspend fun placeOrder(userId: Long, productId: String, quantity: Int): String {
        val userName = userService.getUser(userId)
        val orderId = "ORD-${System.currentTimeMillis()}"

        withSpan("place-order") {
            ChronoLogger.info("Order placed", mapOf(
                "orderId" to orderId,
                "userId" to userId,
                "userName" to userName,
                "productId" to productId,
                "quantity" to quantity
            ))
        }

        return orderId
    }

    suspend fun cancelOrder(orderId: String, reason: String) {
        ChronoLogger.warn("Order cancelled", mapOf("orderId" to orderId, "reason" to reason))
    }
}