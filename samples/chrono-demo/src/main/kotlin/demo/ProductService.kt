package demo

import com.chronotrace.sdk.ChronoLogger

class ProductService {
    suspend fun getProduct(productId: String): Map<String, Any?> {
        val product = mapOf(
            "id" to productId,
            "name" to "Sample Product",
            "price" to 29.99,
            "inStock" to true
        )
        ChronoLogger.debug("Product retrieved", mapOf("productId" to productId) + product)
        return product
    }

    suspend fun searchProducts(query: String): List<String> {
        val results = listOf("Product-A-$query", "Product-B-$query", "Product-C-$query")
        ChronoLogger.info("Product search", mapOf("query" to query, "resultsCount" to results.size))
        return results
    }
}