package demo

import com.chronotrace.sdk.ChronoConfig
import com.chronotrace.sdk.ChronoLogger
import com.chronotrace.sdk.ChronoTrace

suspend fun main() {
    ChronoTrace.init(
        ChronoConfig(
            appId = "chrono-demo",
            environment = "demo",
            serviceName = "ChronoDemo"
        )
    )

    println("ChronoTrace Demo Application")
    println("================================")

    val userService = UserService()
    val orderService = OrderService()
    val productService = ProductService()

    // User operations
    println("\n--- User Operations ---")
    val result = userService.processUser(123L, "Alice")
    println("User processed: $result")

    val userName = userService.getUser(456L)
    println("Got user: $userName")

    // Product operations
    println("\n--- Product Operations ---")
    val product = productService.getProduct("PROD-001")
    println("Product: $product")

    val searchResults = productService.searchProducts("widget")
    println("Search results: $searchResults")

    // Order operations
    println("\n--- Order Operations ---")
    val orderId = orderService.placeOrder(789L, "PROD-002", 3)
    println("Order placed: $orderId")

    orderService.cancelOrder(orderId, "Customer request")

    // Error logging
    println("\n--- Error Logging ---")
    ChronoLogger.error("Demo error", mapOf("code" to 500, "message" to "Something went wrong"))
    ChronoLogger.fatal("Demo fatal", mapOf("errorCode" to "E001", "details" to "Critical failure"))

    ChronoTrace.shutdown()
    println("\n================================")
    println("Demo completed!")
}