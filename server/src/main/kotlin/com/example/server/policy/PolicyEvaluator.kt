package com.example.server.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

data class AccessPolicy(
    val description: String? = null,
    val allowedClients: Map<String, List<String>> = emptyMap()
)

class PolicyEvaluator(private val policy: AccessPolicy) {

    companion object {
        private val logger = LoggerFactory.getLogger(PolicyEvaluator::class.java)
        private val mapper = jacksonObjectMapper()

        fun fromFile(file: File): PolicyEvaluator {
            logger.info("Loading access policy from file: {}", file.absolutePath)
            val policy: AccessPolicy = mapper.readValue(file)
            return PolicyEvaluator(policy)
        }

        fun fromInputStream(stream: InputStream): PolicyEvaluator {
            logger.info("Loading access policy from input stream")
            val policy: AccessPolicy = mapper.readValue(stream)
            return PolicyEvaluator(policy)
        }
    }

    /**
     * Checks whether a client (identified by certificate Common Name) is authorized
     * to call a specific gRPC full method name (e.g. "com.example.signal.v1.SignalService/SendSignal").
     */
    fun isAuthorized(clientCn: String, fullMethodName: String): Boolean {
        val allowedMethods = policy.allowedClients[clientCn] ?: return false
        
        return allowedMethods.any { pattern ->
            when {
                pattern == "*" -> true
                pattern == fullMethodName -> true
                pattern.endsWith("/*") -> {
                    val servicePrefix = pattern.removeSuffix("/*")
                    val callService = fullMethodName.substringBefore("/")
                    servicePrefix == callService
                }
                else -> false
            }
        }
    }

    fun getAllowedMethods(clientCn: String): List<String> {
        return policy.allowedClients[clientCn] ?: emptyList()
    }
}
