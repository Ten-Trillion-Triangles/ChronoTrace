package com.chronotrace.sdk

import org.chronotrace.contract.LogLevel

object ChronoLogger {
    suspend fun trace(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.TRACE, message, fields)
    suspend fun debug(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.DEBUG, message, fields)
    suspend fun info(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.INFO, message, fields)
    suspend fun warn(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.WARN, message, fields)
    suspend fun error(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.ERROR, message, fields)
    suspend fun fatal(message: String, fields: Map<String, Any?> = emptyMap()) = ChronoTrace.runtime().log(LogLevel.FATAL, message, fields)
}
