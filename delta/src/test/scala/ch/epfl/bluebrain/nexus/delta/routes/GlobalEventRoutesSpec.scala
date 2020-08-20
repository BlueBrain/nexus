package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent.OrganizationCreated
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent.AclReplaced
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Group
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.iam.{acls => aclp}
import ch.epfl.bluebrain.nexus.kg.routes.EventsSpecBase
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import monix.eval.Task

class GlobalEventRoutesSpec extends EventsSpecBase {

  private val aclsApi = mock[Acls[Task]]
  private val realms  = mock[Realms[Task]]

  private val path = Path.Empty / "myorg" / "myproj"
  private val acl  = AccessControlList(Group("mygroup", "myrealm") -> Set(aclp.write), subject -> Set(aclp.write))

  private val orgEvent = OrganizationCreated(orgUuid, "thelabel", Some("the description"), instant, subject)

  private val aclEvent = AclReplaced(path, acl, 1L, instant, subject)

  private val routes                     = new TestableEventRoutes(events :+ orgEvent :+ aclEvent, aclsApi, realms).routes
  private val token: Option[AccessToken] = Some(AccessToken("valid"))
  private val oauthToken                 = OAuth2BearerToken("valid")
  private val prefix                     = appConfig.http.prefix
  aclsApi.hasPermission(Path./, read)(caller) shouldReturn Task.pure(true)
  aclsApi.hasPermission(Path./, Permission.unsafe("resources/read"))(caller) shouldReturn Task.pure(true)
  realms.caller(token.value) shouldReturn Task(caller)

  "GlobalEventRoutes" should {

    "return all events" in {
      Get(s"/$prefix/events") ~> addCredentials(oauthToken) ~> routes ~> check {
        val expected = jsonContentOf("/events/global_events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events from the last seen" in {
      Get(s"/$prefix/events").addHeader(`Last-Event-ID`(0.toString)) ~> addCredentials(oauthToken) ~> routes ~> check {
        val expected = jsonContentOf("/events/global_events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }

    "return all resource events" in {
      Get(s"/$prefix/resources/events") ~> addCredentials(oauthToken) ~> routes ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }
  }
}
