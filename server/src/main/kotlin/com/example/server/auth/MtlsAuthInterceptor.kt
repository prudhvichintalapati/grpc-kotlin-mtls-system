package com.example.server.auth

import com.example.server.policy.PolicyEvaluator
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession

object AuthContext {
    val CLIENT_CN_KEY: Context.Key<String> = Context.key("clientCN")
}

class MtlsAuthInterceptor(
    private val policyEvaluator: PolicyEvaluator
) : ServerInterceptor {

    companion object {
        private val logger = LoggerFactory.getLogger(MtlsAuthInterceptor::class.java)
    }

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {

        val methodDescriptor = call.methodDescriptor
        val fullMethodName = methodDescriptor.fullMethodName

        // 1. Extract SSL Session from transport attributes
        val sslSession: SSLSession? = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)
        if (sslSession == null) {
            logger.warn("Rejecting call to {}: SSL session not found (non-TLS connection?)", fullMethodName)
            call.close(
                Status.UNAUTHENTICATED.withDescription("mTLS required: SSL session missing"),
                headers
            )
            return object : ServerCall.Listener<ReqT>() {}
        }

        // 2. Extract Client Certificate Common Name (CN)
        val clientCn = extractClientCn(sslSession)
        if (clientCn == null) {
            logger.warn("Rejecting call to {}: Unable to extract Client CN from TLS certificate", fullMethodName)
            call.close(
                Status.UNAUTHENTICATED.withDescription("Client certificate missing or unreadable"),
                headers
            )
            return object : ServerCall.Listener<ReqT>() {}
        }

        // 3. Evaluate Policy for extracted Client CN and requested RPC method
        val authorized = policyEvaluator.isAuthorized(clientCn, fullMethodName)
        if (!authorized) {
            logger.warn("Access DENIED for client '{}' trying to invoke method '{}'", clientCn, fullMethodName)
            call.close(
                Status.PERMISSION_DENIED.withDescription(
                    "Access Denied: Client '$clientCn' is not authorized to call '$fullMethodName'"
                ),
                headers
            )
            return object : ServerCall.Listener<ReqT>() {}
        }

        logger.info("Access GRANTED for client '{}' invoking method '{}'", clientCn, fullMethodName)

        // 4. Attach Client CN to gRPC Context and proceed
        val context = Context.current().withValue(AuthContext.CLIENT_CN_KEY, clientCn)
        return Contexts.interceptCall(context, call, headers, next)
    }

    private fun extractClientCn(sslSession: SSLSession): String? {
        return try {
            val peerCerts = sslSession.peerCertificates
            if (peerCerts.isEmpty()) return null
            val clientCert = peerCerts[0] as? X509Certificate ?: return null
            val principalName = clientCert.subjectX500Principal.name
            
            // Extract CN=... from X500 Principal
            val regex = Regex("CN=([^,]+)", RegexOption.IGNORE_CASE)
            val match = regex.find(principalName)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            logger.error("Error extracting client certificate CN: ${e.message}", e)
            null
        }
    }
}
