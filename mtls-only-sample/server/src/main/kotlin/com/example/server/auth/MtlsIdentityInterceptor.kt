package com.example.server.auth

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

/**
 * mTLS Identity Interceptor.
 *
 * In this mTLS-only architecture:
 * 1. Mutual TLS authentication is enforced at the transport layer (Netty SSLContext with ClientAuth.REQUIRE).
 * 2. This interceptor extracts the verified client identity (Common Name) from the client's X.509 certificate.
 * 3. The extracted identity is attached to gRPC Context for service logging/auditing.
 * 4. NO policy or RBAC evaluation is performed - any client with a certificate trusted by the CA has full access.
 */
class MtlsIdentityInterceptor : ServerInterceptor {

    companion object {
        private val logger = LoggerFactory.getLogger(MtlsIdentityInterceptor::class.java)
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
            logger.warn("Rejecting call to {}: SSL session not found (mTLS required)", fullMethodName)
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

        // 3. Log authenticated client (pure mTLS: no policy check)
        logger.info("mTLS Client Authenticated: CN='{}' -> Access GRANTED for '{}' (Pure mTLS)", clientCn, fullMethodName)

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
