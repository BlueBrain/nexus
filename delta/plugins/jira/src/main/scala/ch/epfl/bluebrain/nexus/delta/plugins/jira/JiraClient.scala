package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.OauthError.{AccessTokenExpected, NoTokenError, RequestTokenExpected}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import com.google.api.client.auth.oauth.{OAuthAuthorizeTemporaryTokenUrl, OAuthGetAccessToken, OAuthGetTemporaryToken, OAuthRsaSigner}
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.{ByteArrayContent, GenericUrl}
import com.typesafe.scalalogging.Logger
import io.circe.parser._
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import org.apache.commons.codec.binary.Base64

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

trait JiraClient {

  def requestToken()(implicit caller: User): Task[Uri]

  def accessToken(verifier: String)(implicit caller: User): Task[Unit]

  def search(payload: JsonObject)(implicit caller: User): Task[Json]

}

object JiraClient {

  private val logger: Logger   = Logger[JiraClient]
  private val accessTokenUrl   = Uri.Path("/plugins/servlet/oauth/access-token")
  private val authorizationUrl = Uri.Path("/plugins/servlet/oauth/authorize")
  private val requestTokenUrl  = Uri.Path("/plugins/servlet/oauth/request-token")
  private val searchUrl        = Uri.Path("/rest/api/2/search")

  private class JiraOAuthGetTemporaryToken(jiraBase: Uri)
      extends OAuthGetTemporaryToken((jiraBase / requestTokenUrl).toString()) {
    this.usePost = true
  }

  private class JiraOAuthGetAccessToken(jiraBase: Uri)
      extends OAuthGetAccessToken((jiraBase / accessTokenUrl).toString()) {
    this.usePost = true
  }

  def apply(cache: KeyValueStore[User, OAuthToken], jiraConfig: JiraConfig): Task[JiraClient] = {
    Task
      .delay {
        val privateBytes = Base64.decodeBase64(jiraConfig.privateKey.value)
        val keySpec      = new PKCS8EncodedKeySpec(privateBytes)
        val kf           = KeyFactory.getInstance("RSA")
        val signer       = new OAuthRsaSigner()
        signer.privateKey = kf.generatePrivate(keySpec)
        signer
      }
      .map { signer =>
        new JiraClient {

          private val netHttpTransport = new NetHttpTransport()

          override def requestToken()(implicit caller: User): Task[Uri] =
            Task
              .delay {
                val tempToken = new JiraOAuthGetTemporaryToken(jiraConfig.base)
                tempToken.consumerKey = jiraConfig.consumerKey
                tempToken.signer = signer
                tempToken.transport = netHttpTransport
                tempToken.callback = "oob"
                val response  = tempToken.execute()
                logger.debug(s"Request Token value: ${response.token}")
                println(s"Request Token value: ${response.token}")
                response.token
              }
              .flatMap { token =>
                cache.put(caller, RequestToken(token)).as {
                  val authorizationURL =
                    new OAuthAuthorizeTemporaryTokenUrl((jiraConfig.base / authorizationUrl).toString())
                  authorizationURL.temporaryToken = token
                  Uri(authorizationURL.toString)
                }
              }

          override def accessToken(verifier: String)(implicit caller: User): Task[Unit] =
            cache.get(caller).flatMap {
              case None                      => Task.raiseError(NoTokenError)
              case Some(_: AccessToken)      => Task.raiseError(RequestTokenExpected)
              case Some(RequestToken(value)) =>
                Task
                  .delay {
                    val accessToken = new JiraOAuthGetAccessToken(jiraConfig.base)
                    accessToken.consumerKey = jiraConfig.consumerKey
                    accessToken.signer = signer
                    accessToken.transport = netHttpTransport
                    accessToken.verifier = verifier
                    accessToken.temporaryToken = value
                    accessToken.execute().token
                  }
                  .flatMap { token =>
                    logger.debug("Access Token:" + token)
                    println("Access Token:" + token)
                    cache.put(caller, AccessToken(token))
                  }
            }

          def search(payload: JsonObject)(implicit caller: User): Task[Json] =
            cache.get(caller).flatMap {
              case None                     => Task.raiseError(NoTokenError)
              case Some(_: RequestToken)    => Task.raiseError(AccessTokenExpected)
              case Some(AccessToken(token)) =>
                Task
                  .delay {
                    val accessToken    = new JiraOAuthGetAccessToken(jiraConfig.base)
                    accessToken.consumerKey = jiraConfig.consumerKey
                    accessToken.signer = signer
                    accessToken.transport = netHttpTransport
                    accessToken.verifier = jiraConfig.secret.value
                    accessToken.temporaryToken = token
                    val parameters     = accessToken.createParameters()
                    val requestFactory = netHttpTransport.createRequestFactory(parameters)
                    val request        = requestFactory.buildPostRequest(
                      new GenericUrl((jiraConfig.base / searchUrl).toString()),
                      new ByteArrayContent("application/json", payload.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
                    )
                    request.execute().parseAsString()
                  }
                  .flatMap { response =>
                    println("Search:" + response)
                    Task.fromEither(parse(response))
                  }

            }
        }
      }
  }

}
