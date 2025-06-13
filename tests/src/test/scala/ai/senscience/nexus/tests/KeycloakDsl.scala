package ai.senscience.nexus.tests

import ai.senscience.nexus.tests.Identity.{ClientCredentials, UserCredentials}
import ai.senscience.nexus.tests.Optics.*
import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.HttpMethods.*
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.Json

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

class KeycloakDsl(implicit
    as: ActorSystem,
    materializer: Materializer,
    um: FromEntityUnmarshaller[Json],
    executionContext: ExecutionContext
) {

  private val loader = ClasspathResourceLoader()
  private val logger = Logger[this.type]

  private val keycloakUrl    = Uri(s"http://${sys.props.getOrElse("keycloak-url", "localhost:9090")}")
  private val keycloakClient = HttpClient(keycloakUrl)
  // Defined in docker-compose file
  private val adminRealm     = Realm("master")
  private val keycloakAdmin  = UserCredentials("admin", "admin", adminRealm)
  private val adminClient    = ClientCredentials("admin-cli", "", adminRealm)

  def importRealm(
      realm: Realm,
      clientCredentials: ClientCredentials,
      userCredentials: List[UserCredentials]
  ): IO[StatusCode] = {
    val users = userCredentials.map { u =>
      Map(
        s"username" -> u.name,
        s"password" -> u.password
      ).asJava
    }.asJava

    for {
      _          <- logger.info(s"Creating realm $realm in Keycloak...")
      json       <- loader.jsonContentOf(
                      "iam/keycloak/import.json",
                      "realm"         -> realm.name,
                      "client"        -> clientCredentials.id,
                      "client_secret" -> clientCredentials.secret,
                      "users"         -> users
                    )
      adminToken <- userToken(keycloakAdmin, adminClient)
      response   <- keycloakClient(
                      HttpRequest(
                        method = POST,
                        uri = s"$keycloakUrl/admin/realms/",
                        headers = Authorization(HttpCredentials.createOAuth2BearerToken(adminToken)) :: Nil,
                        entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)
                      )
                    ).onError { case t =>
                      logger.error(t)(s"Error while importing realm: ${realm.name}")
                    }
      _          <- logger.info(s"${realm.name} has been imported with code: ${response.status}")
    } yield response.status
  }

  private def realmEndpoint(realm: Realm) =
    Uri(s"$keycloakUrl/realms/${realm.name}/protocol/openid-connect/token")

  def userToken(user: UserCredentials, client: ClientCredentials): IO[String] = {
    val clientFields = if (client.secret == "") {
      Map("scope" -> "openid", "client_id" -> client.id)
    } else {
      Map(
        "scope"         -> "openid",
        "client_id"     -> client.id,
        "client_secret" -> client.secret
      )
    }

    val request = HttpRequest(
      method = POST,
      uri = realmEndpoint(user.realm),
      entity = akka.http.scaladsl.model
        .FormData(
          Map(
            "username"   -> user.name,
            "password"   -> user.password,
            "grant_type" -> "password"
          ) ++ clientFields
        )
        .toEntity
    )

    for {
      _        <- logger.info(s"Getting token for user ${user.name} for ${user.realm.name}")
      response <- keycloakClient(request)
      json     <- IO.fromFuture { IO(um(response.entity)) }
      token    <- IO.fromOption(keycloak.access_token.getOption(json))(
                    new IllegalArgumentException(
                      s"Couldn't get a token for user ${user.name}, we got response: $response"
                    )
                  )
    } yield token
  }.onError { case t =>
    logger.error(t)(s"Error while getting user token for realm: ${user.realm.name} and user:$user")
  }

  def serviceAccountToken(client: ClientCredentials): IO[String] = {
    logger.info(s"Getting token for client ${client.name} for ${client.realm}") >>
      keycloakClient(
        HttpRequest(
          method = POST,
          uri = realmEndpoint(client.realm),
          headers = Authorization(HttpCredentials.createBasicHttpCredentials(client.id, client.secret)) :: Nil,
          entity = akka.http.scaladsl.model
            .FormData(
              Map(
                "scope"      -> "openid",
                "grant_type" -> "client_credentials"
              )
            )
            .toEntity
        )
      ).flatMap { res =>
        IO.fromFuture { IO(um(res.entity)) }
      }.onError { case t =>
        logger.error(t)(s"Error while getting user token for realm: ${client.realm} and client: $client")
      }.map { response =>
        keycloak.access_token
          .getOption(response)
          .getOrElse(
            throw new IllegalArgumentException(
              s"Couldn't get a token for client ${client.id} for realm ${client.realm.name}, we got response: $response"
            )
          )
      }
  }
}
