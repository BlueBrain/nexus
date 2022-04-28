package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError.{AccessTokenExpected, NoTokenError, RequestTokenExpected}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.OAuthToken.{AccessToken, RequestToken}
import ch.epfl.bluebrain.nexus.delta.plugins.jira.config.JiraConfig
import ch.epfl.bluebrain.nexus.delta.plugins.jira.model.{AuthenticationRequest, JiraResponse, Verifier}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
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

/**
  * Client that allows to authorize Delta to query Jira on behalf on the users by handling the Oauth authorization flow
  * and passing by the queries to Jira
  */
trait JiraClient {

  /**
    * Creates an authorization request for the current user
    */
  def requestToken()(implicit caller: User): IO[JiraError, AuthenticationRequest]

  /**
    * Generates an access token for the current user by providing the verifier code provided by the user
    */
  def accessToken(verifier: Verifier)(implicit caller: User): IO[JiraError, Unit]

  /**
    * Create an issue on behalf of the user in Jira
    * @param payload
    *   the issue payload
    */
  def createIssue(payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse]

  /**
    * Edits an issue on behalf of the user in Jira
    * @param payload
    *   the issue payload
    */
  def editIssue(issueId: String, payload: JsonObject)(implicit caller: User): IO[JiraError, JiraResponse]

  /**
    * Get the issue matching the provided identifier
    * @param issueId
    *   the identifier
    */
  def getIssue(issueId: String)(implicit caller: User): IO[JiraError, JiraResponse]

  /**
    * List the projects the current user has access to
    * @param recent
    *   when provided, return the n most recent projects the user was active in
    */
  def listProjects(recent: Option[Int])(implicit caller: User): IO[JiraError, JiraResponse]

  /**
    * Search issues in Jira the user has access to according to the provided search payload
    * @param payload
    *   the search payload
    */
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

  /**
    * Creates the Jira client
    * @param store
    *   the token store
    * @param jiraConfig
    *   the jira configuration
    */
  def apply(store: TokenStore, jiraConfig: JiraConfig): Task[JiraClient] = {
    Task
      .delay {
        // Create the RSA signer according to the PKCS8 key provided by the configuration
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
                store.save(caller, RequestToken(token)).as {
                  val authorizationURL =
                    new OAuthAuthorizeTemporaryTokenUrl((jiraConfig.base / authorizationUrl).toString())
                  authorizationURL.temporaryToken = token
                  AuthenticationRequest(Uri(authorizationURL.toString))
                }
              }
              .mapError { JiraError.from }

          override def accessToken(verifier: Verifier)(implicit caller: User): IO[JiraError, Unit] =
            store
              .get(caller)
              .flatMap {
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
                      store.save(caller, AccessToken(token))
                    }
              }
              .mapError { JiraError.from }

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

          private def requestFactory(caller: User) = store.get(caller).hideErrors.flatMap {
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
