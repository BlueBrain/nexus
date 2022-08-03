package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.SerializationSuite
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Name, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.GrantType._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder.SseData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Json

import java.time.Instant

class RealmSerializationSuite extends SerializationSuite {

  private val sseEncoder = RealmEvent.sseEncoder

  val rev              = 1
  val instant: Instant = Instant.EPOCH

  val realm: Label                           = Label.unsafe("myrealm")
  val name: Name                             = Name.unsafe("name")
  val subject: Subject                       = User("username", realm)
  val openIdConfig: Uri                      = Uri("http://localhost:8080/.wellknown")
  val issuer: String                         = "http://localhost:8080/issuer"
  val keys: Set[Json]                        = Set(Json.obj("k" -> Json.fromString(issuer)))
  val grantTypes: Set[GrantType]             =
    Set(AuthorizationCode, Implicit, Password, ClientCredentials, DeviceCode, RefreshToken)
  val logo: Uri                              = Uri("http://localhost:8080/logo.png")
  val acceptedAudiences: NonEmptySet[String] = NonEmptySet.of("audience")
  val authorizationEndpoint: Uri             = Uri("http://localhost:8080/authorize")
  val tokenEndpoint: Uri                     = Uri("http://localhost:8080/token")
  val userInfoEndpoint: Uri                  = Uri("http://localhost:8080/userinfo")
  val revocationEndpoint: Uri                = Uri("http://localhost:8080/revocation")
  val endSessionEndpoint: Uri                = Uri("http://localhost:8080/logout")

  private val realmMapping = Map(
    RealmCreated(
      label = realm,
      rev = rev,
      name = name,
      openIdConfig = openIdConfig,
      issuer = issuer,
      keys = keys,
      grantTypes = grantTypes,
      logo = Some(logo),
      acceptedAudiences = Some(acceptedAudiences),
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userInfoEndpoint = userInfoEndpoint,
      revocationEndpoint = Some(revocationEndpoint),
      endSessionEndpoint = Some(endSessionEndpoint),
      instant = instant,
      subject = subject
    ) -> loadEvents("realms", "realm-created.json"),
    RealmUpdated(
      label = realm,
      rev = rev,
      name = name,
      openIdConfig = openIdConfig,
      issuer = issuer,
      keys = keys,
      grantTypes = grantTypes,
      logo = Some(logo),
      acceptedAudiences = Some(acceptedAudiences),
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userInfoEndpoint = userInfoEndpoint,
      revocationEndpoint = Some(revocationEndpoint),
      endSessionEndpoint = Some(endSessionEndpoint),
      instant = instant,
      subject = subject
    ) -> loadEvents("realms", "realm-updated.json"),
    RealmDeprecated(
      label = realm,
      rev = rev,
      instant = instant,
      subject = subject
    ) -> loadEvents("realms", "realm-deprecated.json")
  )

  realmMapping.foreach { case (event, (database, sse)) =>
    test(s"Correctly serialize ${event.getClass.getName}") {
      assertEquals(RealmEvent.serializer.codec(event), database)
    }

    test(s"Correctly deserialize ${event.getClass.getName}") {
      assertEquals(RealmEvent.serializer.codec.decodeJson(database), Right(event))
    }

    test(s"Correctly serialize ${event.getClass.getName} as an SSE") {
      sseEncoder.toSse.decodeJson(database).assertRight(SseData(ClassUtils.simpleName(event), None, sse))
    }
  }

  private val state = RealmState(
    label = realm,
    rev = rev,
    name = name,
    deprecated = false,
    openIdConfig = openIdConfig,
    issuer = issuer,
    keys = keys,
    grantTypes = grantTypes,
    logo = Some(logo),
    acceptedAudiences = Some(acceptedAudiences),
    authorizationEndpoint = authorizationEndpoint,
    tokenEndpoint = tokenEndpoint,
    userInfoEndpoint = userInfoEndpoint,
    revocationEndpoint = Some(revocationEndpoint),
    endSessionEndpoint = Some(endSessionEndpoint),
    createdAt = instant,
    createdBy = subject,
    updatedAt = instant,
    updatedBy = subject
  )

  private val jsonState = jsonContentOf("/realms/realm-state.json")

  test(s"Correctly serialize an RealmState") {
    assertEquals(RealmState.serializer.codec(state), jsonState)
  }

  test(s"Correctly deserialize an RealmState") {
    assertEquals(RealmState.serializer.codec.decodeJson(jsonState), Right(state))
  }

}
