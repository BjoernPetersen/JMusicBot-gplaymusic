package com.github.felixgail.musicbot.gplaymusic

import com.github.felixgail.gplaymusic.util.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.PasswordBox
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.config.serialization
import net.bjoernpetersen.musicbot.api.config.serialized
import net.bjoernpetersen.musicbot.api.config.string
import net.bjoernpetersen.musicbot.api.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.TokenRefreshException
import net.bjoernpetersen.musicbot.spi.plugin.predefined.gplaymusic.GPlayMusicAuthenticator
import svarzee.gps.gpsoauth.AuthToken
import svarzee.gps.gpsoauth.Gpsoauth
import java.io.IOException
import java.time.Instant

class GPlayMusicAuthenticatorImpl : GPlayMusicAuthenticator {
    override val name: String = "gpsoauth"
    override val description: String = "Uses Google Play Services OAuth"

    private lateinit var username: Config.StringEntry
    private lateinit var password: Config.StringEntry
    private lateinit var androidID: Config.StringEntry
    private lateinit var tokenEntry: Config.SerializedEntry<AuthToken>
    private var token: AuthToken? = null
        set(value) {
            field = value
            tokenEntry.set(value)
        }

    override fun createStateEntries(state: Config) {
        tokenEntry = state.serialized("Token") {
            description = "Authtoken"
            check { null }
            serialization {
                serialize { "${it.token};${it.expiry}" }
                deserialize {
                    val split = it.split(';')
                    val token = split[0]
                    val expiry = if (split.size < 2) -1 else split[1].toLong()
                    AuthToken(token, expiry)
                }
            }
        }
    }

    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        username = config.string("Username") {
            description = "Username or Email of your Google account with AllAccess subscription"
            check(NonnullConfigChecker)
            uiNode = TextBox
        }

        return listOf(username)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        password = secrets.string("Password") {
            description = "Password/App password of your Google account"
            check(NonnullConfigChecker)
            uiNode = PasswordBox
        }

        androidID = secrets.string("Android ID") {
            description = "IMEI or GoogleID of your smartphone with GooglePlayMusic installed"
            check(NonnullConfigChecker)
            uiNode = TextBox
        }

        return listOf(password, androidID)
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Logging into GPlayMusic")
        token = tokenEntry.get()
        withContext(Dispatchers.IO) {
            try {
                refreshToken(initStateWriter)
            } catch (e: IOException) {
                initStateWriter.warning("Logging into GPlayMusic failed!")
                throw InitializationException(e)
            } catch (e: Gpsoauth.TokenRequestFailed) {
                initStateWriter.warning("Logging into GPlayMusic failed!")
                throw InitializationException(e)
            }
        }
    }

    override suspend fun getToken(): String {
        try {
            return refreshToken().token
        } catch (e: IOException) {
            throw TokenRefreshException(e)
        } catch (e: Gpsoauth.TokenRequestFailed) {
            throw TokenRefreshException(e)
        }
    }

    @Throws(IOException::class, Gpsoauth.TokenRequestFailed::class)
    private suspend fun refreshToken(initStateWriter: InitStateWriter? = null): AuthToken {
        var authToken = token
        if (authToken != null && authToken.isValid()) {
            initStateWriter?.state("Found existing token.")
        } else {
            initStateWriter?.state("Fetching new token.")
            val diffLastRequest = System.currentTimeMillis() - TokenProvider.getLastTokenFetched()
            val remainingMillis = tokenCooldownMillis - diffLastRequest
            if (remainingMillis > 0) delay(remainingMillis)
            authToken = withContext(Dispatchers.IO) {
                TokenProvider.provideToken(username.get(), password.get(), androidID.get())!!
            }
            token = authToken
        }
        return authToken
    }

    override fun invalidateToken() {
        token = null
    }

    override suspend fun close() = Unit

    private companion object {
        const val tokenCooldownMillis: Long = 60000
    }
}

private fun AuthToken.isValid() = if (expiry == -1L) true
else Instant.now().isBefore(Instant.ofEpochMilli(expiry))
