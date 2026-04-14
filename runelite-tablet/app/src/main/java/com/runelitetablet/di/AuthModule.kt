package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.auth.CredentialManager
import com.runelitetablet.auth.JagexOAuth2Manager
import com.runelitetablet.domain.auth.CredentialStore
import okhttp3.OkHttpClient

class AuthModule(context: Context, httpClient: OkHttpClient) {
    val credentialStore: CredentialStore = CredentialManager(context)
    val oauthManager: JagexOAuth2Manager by lazy { JagexOAuth2Manager(httpClient) }
}
