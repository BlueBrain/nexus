package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError.{AccessTokenExpected, NoTokenError, RequestTokenExpected, UnknownError}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.model.{AuthenticationRequest, JiraResponse, Verifier}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import com.google.api.client.auth.oauth.{OAuthAuthorizeTemporaryTokenUrl, OAuthGetAccessToken, OAuthGetTemporaryToken, OAuthRsaSigner}
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.http.{ByteArrayContent, GenericUrl}
import com.typesafe.scalalogging.Logger
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.bio.{IO, Task}
import org.apache.commons.codec.binary.Base64

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

trait JiraClient {

  def requestToken()(implicit caller: User): IO[JiraError, AuthenticationRequest]

  def accessToken(verifier: Verifier)(implicit caller: User): IO[JiraError, Unit]

  def createIssue(payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse]

  def editIssue(issueId: String, payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse]

  def getIssue(issueId: String)(implicit caller: User): IO[JiraError, JiraResponse]

  def listProjects(recent: Option[Int])(implicit caller: User): IO[JiraError, JiraResponse]

  def search(payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse]

}

object JiraClient {

  private val logger: Logger   = Logger[JiraClient]
  private val accessTokenUrl   = Uri.Path("/plugins/servlet/oauth/access-token")
  private val authorizationUrl = Uri.Path("/plugins/servlet/oauth/authorize")
  private val requestTokenUrl  = Uri.Path("/plugins/servlet/oauth/request-token")

  private val issueUrl   = Uri.Path("/rest/api/2/issue")
  private val projectUrl = Uri.Path("/rest/api/2/project")
  private val searchUrl  = Uri.Path("/rest/api/2/search")

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

          override def requestToken()(implicit caller: User): IO[JiraError, AuthenticationRequest] =
            Task
              .delay {
                val tempToken = new JiraOAuthGetTemporaryToken(jiraConfig.base)
                tempToken.consumerKey = jiraConfig.consumerKey
                tempToken.signer = signer
                tempToken.transport = netHttpTransport
                tempToken.callback = "oob"
                val response  = tempToken.execute()
                logger.debug(s"Request Token value: ${response.token}")
                response.token
              }
              .flatMap { token =>
                cache.put(caller, RequestToken(token)).as {
                  val authorizationURL =
                    new OAuthAuthorizeTemporaryTokenUrl((jiraConfig.base / authorizationUrl).toString())
                  authorizationURL.temporaryToken = token
                  AuthenticationRequest(Uri(authorizationURL.toString))
                }
              }
              .mapError { e => UnknownError(e.getMessage) }

          override def accessToken(verifier: Verifier)(implicit caller: User): IO[JiraError, Unit] =
            cache.get(caller).flatMap {
              case None                      => IO.raiseError(NoTokenError)
              case Some(_: AccessToken)      => IO.raiseError(RequestTokenExpected)
              case Some(RequestToken(value)) =>
                Task
                  .delay {
                    val accessToken = new JiraOAuthGetAccessToken(jiraConfig.base)
                    accessToken.consumerKey = jiraConfig.consumerKey
                    accessToken.signer = signer
                    accessToken.transport = netHttpTransport
                    accessToken.verifier = verifier.value
                    accessToken.temporaryToken = value
                    accessToken.execute().token
                  }
                  .flatMap { token =>
                    logger.debug("Access Token:" + token)
                    cache.put(caller, AccessToken(token))
                  }
                  .mapError { e => UnknownError(e.getMessage) }
            }

          override def createIssue(payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse] =
            requestFactory(caller).flatMap { factory =>
              val url = jiraConfig.base / issueUrl
              JiraResponse(
                factory.buildPostRequest(
                  new GenericUrl(url.toString()),
                  jsonContent(payload)
                )
              )
            }

          override def editIssue(issueId: String, payload: JsonObject)(implicit
              caller: User
          ): IO[JiraError, JiraResponse] =
            requestFactory(caller).flatMap { factory =>
              val url = jiraConfig.base / issueUrl / issueId
              JiraResponse(
                factory.buildPutRequest(
                  new GenericUrl(url.toString()),
                  jsonContent(payload)
                )
              )
            }

          override def getIssue(issueId: String)(implicit caller: User): IO[JiraError, JiraResponse] =
            requestFactory(caller).flatMap { factory =>
              val url = jiraConfig.base / issueUrl / issueId
              JiraResponse(
                factory.buildGetRequest(
                  new GenericUrl(url.toString())
                )
              )
            }

          override def listProjects(recent: Option[Int])(implicit caller: User): IO[JiraError, JiraResponse] =
            requestFactory(caller).flatMap { factory =>
              val url = recent.fold(jiraConfig.base / projectUrl) { r =>
                (jiraConfig.base / projectUrl).withQuery(Uri.Query("recent" -> r.toString))
              }
              JiraResponse(
                factory.buildGetRequest(
                  new GenericUrl(url.toString())
                )
              )
            }

          def search(payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse] =
            requestFactory(caller).flatMap { factory =>
              JiraResponse(
                factory.buildPostRequest(
                  new GenericUrl((jiraConfig.base / searchUrl).toString()),
                  jsonContent(payload)
                )
              )
            }

          private def requestFactory(caller: User) = cache.get(caller).flatMap {
            case None                     => IO.raiseError(NoTokenError)
            case Some(_: RequestToken)    => IO.raiseError(AccessTokenExpected)
            case Some(AccessToken(token)) =>
              IO.pure {
                val accessToken = new JiraOAuthGetAccessToken(jiraConfig.base)
                accessToken.consumerKey = jiraConfig.consumerKey
                accessToken.signer = signer
                accessToken.transport = netHttpTransport
                accessToken.verifier = jiraConfig.secret.value
                accessToken.temporaryToken = token
                netHttpTransport.createRequestFactory(accessToken.createParameters())
              }
          }

          private def jsonContent(payload: JsonObject) =
            new ByteArrayContent("application/json", payload.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
        }
      }
  }

}
