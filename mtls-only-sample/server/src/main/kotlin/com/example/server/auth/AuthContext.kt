package com.example.server.auth

import io.grpc.Context

object AuthContext {
    val CLIENT_CN_KEY: Context.Key<String> = Context.key("clientCN")
}
