package ch.epfl.bluebrain.nexus.tests

import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.tests.Identity.{ClientCredentials, UserCredentials}
import ch.epfl.bluebrain.nexus.tests.Optics._
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.Task

class KeycloakDsl(implicit as: ActorSystem, materializer: Materializer, um: FromEntityUnmarshaller[Json])
    extends TestHelpers {

  import monix.execution.Scheduler.Implicits.global

  private val logger = Logger[this.type]

  private val keycloakUrl    = Uri(s"http://${System.getProperty("keycloak:8080")}/auth")
  private val keycloakClient = HttpClient(keycloakUrl)
  // Defined in docker-compose file
  private val adminRealm     = Realm("master")
  private val keycloakAdmin  = UserCredentials("admin", "admin", adminRealm)
  private val adminClient    = ClientCredentials("admin-cli", "", adminRealm)

  def importRealm(
      realm: Realm,
      clientCredentials: ClientCredentials,
      userCredentials: List[UserCredentials]
  ): Task[StatusCode] = {
    logger.info(s"Creating realm $realm in Keycloak...")
    val users: List[Map[String, Any]] = userCredentials.map { u =>
      Map(
        s"username" -> u.name,
        s"password" -> u.password
      )
    }

    val json = jsonContentOf(
      "/iam/keycloak/import.json",
      "realm"         -> realm.name,
      "client"        -> clientCredentials.id,
      "client_secret" -> clientCredentials.secret,
      "users"         -> users
    )

    for {
      adminToken <- userToken(keycloakAdmin, adminClient)
      status     <- keycloakClient(
                      HttpRequest(
                        method = POST,
                        uri = s"$keycloakUrl/admin/realms",
                        headers = Authorization(HttpCredentials.createOAuth2BearerToken(adminToken)) :: Nil,
                        entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)
                      )
                    ).tapError { t =>
                      Task { logger.error(s"Error while importing realm: ${realm.name}", t) }
                    }.map { res =>
                      logger.info(s"${realm.name} has been imported with code: ${res.status}")
                      res.status
                    }
    } yield status
  }

  private def realmEndpoint(realm: Realm) =
    Uri(s"$keycloakUrl/realms/${realm.name}/protocol/openid-connect/token")

  def userToken(user: UserCredentials, client: ClientCredentials): Task[String] = {
    logger.info(s"Getting token for user ${user.name} for ${user.realm.name}")
    val clientFields = if (client.secret == "") {
      Map("client_id" -> client.id)
    } else {
      Map(
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

    keycloakClient(request)
      .flatMap { res =>
        Task.deferFuture { um(res.entity) }
      }
      .tapError { t =>
        Task { logger.error(s"Error while getting user token for realm: ${user.realm.name} and user:$user", t) }
      }
      .map { response =>
        keycloak.access_token
          .getOption(response)
          .getOrElse(
            throw new IllegalArgumentException(
              s"Couldn't get a token for user ${user.name}, we got response: $response"
            )
          )
      }

  }

  def serviceAccountToken(client: ClientCredentials): Task[String] = {
    logger.info(s"Getting token for client ${client.name} for ${client.realm}")
    keycloakClient(
      HttpRequest(
        method = POST,
        uri = realmEndpoint(client.realm),
        headers = Authorization(HttpCredentials.createBasicHttpCredentials(client.id, client.secret)) :: Nil,
        entity = akka.http.scaladsl.model
          .FormData(
            Map(
              "grant_type" -> "client_credentials"
            )
          )
          .toEntity
      )
    ).flatMap { res =>
      Task.deferFuture { um(res.entity) }
    }.tapError { t =>
      Task { logger.error(s"Error while getting user token for realm: ${client.realm} and client: $client", t) }
    }.map { response =>
      keycloak.access_token
        .getOption(response)
        .getOrElse(
          throw new IllegalArgumentException(
            s"Couldn't get a token for client ${client.id}, we got response: $response"
          )
        )
    }
  }
}
