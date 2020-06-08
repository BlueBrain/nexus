package ch.epfl.bluebrain.nexus.iam.routes

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.config.IamConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.iam.config.Settings
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.routes.EventRoutesSpec.TestableEventRoutes
import ch.epfl.bluebrain.nexus.iam.testsyntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.iam.types.{Caller, GrantType, Label, Permission}
import ch.epfl.bluebrain.nexus.iam.{acls => aclp}
import ch.epfl.bluebrain.nexus.rdf.Iri.{Path, Url}
import io.circe.Json
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation
class EventRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with OptionValues
    with EitherValues
    with Inspectors
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 100.milliseconds)

  private val appConfig     = Settings(system).appConfig
  implicit private val http = appConfig.http
  implicit private val pc   = appConfig.persistence

  private val realms: Realms[Task] = mock[Realms[Task]]
  private val acls: Acls[Task]     = mock[Acls[Task]]

  before {
    Mockito.reset(realms, acls)
    realms.caller(any[AccessToken]) shouldReturn Task.pure(Caller.anonymous)
    acls.hasPermission(Path./, any[Permission], ancestors = false)(any[Caller]) shouldReturn Task.pure(true)
  }

  val path    = Path.Empty / "myorg" / "myproj"
  val rev     = 1L
  val subject = User("uuid", "myrealm")
  val acl = AccessControlList(
    Group("mygroup", "myrealm") -> Set(aclp.write),
    subject                     -> Set(aclp.write)
  )
  val instant      = Instant.EPOCH
  val name         = "The Realm"
  val issuer       = "issuer"
  val openIdConfig = Url("http://localhost:8080/myrealm").rightValue
  val grantTypes   = Set[GrantType](GrantType.Implicit)
  val keys         = Set[Json](jsonContentOf("/events/realm-key.json"))
  val logo         = Some(Url("http://localhost:8080/myrealm/logo").rightValue)

  val authorizationEndpoint = Url("https://localhost/auth").rightValue
  val tokenEndpoint         = Url("https://localhost/auth/token").rightValue
  val userInfoEndpoint      = Url("https://localhost/auth/userinfo").rightValue
  val revocationEndpoint    = Some(Url("https://localhost/auth/revoke").rightValue)
  val endSessionEndpoint    = Some(Url("https://localhost/auth/logout").rightValue)

  val aclEvents = List(
    AclReplaced(path, acl, rev, instant, subject),
    AclAppended(path, acl, rev, instant, subject),
    AclSubtracted(path, acl, rev, instant, subject),
    AclDeleted(path, rev, instant, subject)
  )

  val realmEvents = List(
    RealmCreated(
      Label.unsafe("myrealm"),
      rev,
      name,
      openIdConfig,
      issuer,
      keys,
      grantTypes,
      logo,
      authorizationEndpoint,
      tokenEndpoint,
      userInfoEndpoint,
      revocationEndpoint,
      endSessionEndpoint,
      instant,
      subject
    ),
    RealmUpdated(
      Label.unsafe("myrealm"),
      rev,
      name,
      openIdConfig,
      issuer,
      keys,
      grantTypes,
      logo,
      authorizationEndpoint,
      tokenEndpoint,
      userInfoEndpoint,
      revocationEndpoint,
      endSessionEndpoint,
      instant,
      subject
    ),
    RealmDeprecated(Label.unsafe("myrealm"), rev, instant, subject)
  )

  val permissionsEvents = List(
    PermissionsAppended(rev, Set(aclp.write), instant, subject),
    PermissionsSubtracted(rev, Set(aclp.write), instant, subject),
    PermissionsReplaced(rev, Set(aclp.write), instant, subject),
    PermissionsDeleted(rev, instant, subject)
  )

  def eventStreamFor(jsons: Vector[Json], drop: Int = 0): String =
    jsons.zipWithIndex
      .drop(drop)
      .map {
        case (json, idx) =>
          val data  = json.sort.noSpaces
          val event = json.hcursor.get[String]("@type").rightValue
          val id    = idx
          s"""data:$data
           |event:$event
           |id:$id""".stripMargin
      }
      .mkString("", "\n\n", "\n\n")

  "The EventRoutes" should {
    "return the acl events in the right order" in {
      val routes = Routes.wrap(new TestableEventRoutes(aclEvents, acls, realms).routes)
      forAll(List("/acls/events", "/acls/events/")) { path =>
        Get(path) ~> routes ~> check {
          val expected = jsonContentOf("/events/acl-events.json").asArray.value
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(expected)
        }
      }
    }

    "return the realm events in the right order" in {
      val routes = Routes.wrap(new TestableEventRoutes(realmEvents, acls, realms).routes)
      forAll(List("/realms/events", "/realms/events/")) { path =>
        Get(path) ~> routes ~> check {
          val expected = jsonContentOf("/events/realm-events.json").asArray.value
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(expected)
        }
      }
    }

    "return the permissions events in the right order" in {
      val routes = Routes.wrap(new TestableEventRoutes(permissionsEvents, acls, realms).routes)
      forAll(List("/permissions/events", "/permissions/events/")) { path =>
        Get(path) ~> routes ~> check {
          val expected = jsonContentOf("/events/permissions-events.json").asArray.value
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(expected)
        }
      }
    }

    "return all the events in the right order" in {
      val routes =
        Routes.wrap(new TestableEventRoutes(aclEvents ++ realmEvents ++ permissionsEvents, acls, realms).routes)
      forAll(List("/events", "/events/")) { path =>
        Get(path) ~> routes ~> check {
          val expected =
            jsonContentOf("/events/acl-events.json").asArray.value ++
              jsonContentOf("/events/realm-events.json").asArray.value ++
              jsonContentOf("/events/permissions-events.json").asArray.value
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(expected)
        }
      }
    }

    "return events from the last seen" in {
      val routes = Routes.wrap(new TestableEventRoutes(realmEvents, acls, realms).routes)
      Get("/realms/events").addHeader(`Last-Event-ID`(0.toString)) ~> routes ~> check {
        val expected = jsonContentOf("/events/realm-events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }

    "return Forbidden when requesting the log with no permissions" in {
      acls.hasPermission(Path./, any[Permission], ancestors = false)(any[Caller]) shouldReturn Task.pure(false)
      val routes = Routes.wrap(new TestableEventRoutes(realmEvents, acls, realms).routes)
      Get("/realms/events") ~> routes ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }
  }

}

object EventRoutesSpec {
  //noinspection TypeAnnotation
  class TestableEventRoutes(
      events: List[Any],
      acls: Acls[Task],
      realms: Realms[Task]
  )(implicit as: ActorSystem, hc: HttpConfig, pc: PersistenceConfig)
      extends EventRoutes(acls, realms) {

    private val envelopes = events.zipWithIndex.map {
      case (ev, idx) => EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, idx.toLong)
    }

    override protected def source(
        tag: String,
        offset: Offset,
        toSse: EventEnvelope => Option[ServerSentEvent]
    ): Source[ServerSentEvent, NotUsed] = {
      val toDrop = offset match {
        case NoOffset    => 0
        case Sequence(v) => v + 1
      }
      Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(toSse(ee).toList))
    }
  }
}
