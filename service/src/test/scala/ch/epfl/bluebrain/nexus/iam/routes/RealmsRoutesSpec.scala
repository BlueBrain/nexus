package ch.epfl.bluebrain.nexus.iam.routes

import java.time.Instant
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms._
import ch.epfl.bluebrain.nexus.iam.testsyntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.{Caller, GrantType, Label, ResourceF}
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import ch.epfl.bluebrain.nexus.service.routes.Routes
import ch.epfl.bluebrain.nexus.util.{EitherValues, Resources}
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation,NameBooleanParameters
class RealmsRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with EitherValues
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.second, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  private val config        = Settings(system).appConfig
  implicit private val http = config.http

  private val realms: Realms[Task] = mock[Realms[Task]]
  private val acls: Acls[Task]     = mock[Acls[Task]]

  before {
    Mockito.reset(acls, realms)
    realms.caller(any[AccessToken]) shouldReturn Task.pure(Caller.anonymous)
  }

  val authorizationEndpoint = Url("https://localhost/auth").rightValue
  val tokenEndpoint         = Url("https://localhost/auth/token").rightValue
  val userInfoEndpoint      = Url("https://localhost/auth/userinfo").rightValue
  val revocationEndpoint    = Some(Url("https://localhost/auth/revoke").rightValue)
  val endSessionEndpoint    = Some(Url("https://localhost/auth/logout").rightValue)

  def response(label: Label, rev: Long, deprecated: Boolean): Json =
    jsonContentOf(
      "/realms/realm-template.json",
      Map(
        quote("{label}")      -> label.value,
        quote("{createdBy}")  -> Anonymous.id.asString,
        quote("{updatedBy}")  -> Anonymous.id.asString,
        quote("{deprecated}") -> deprecated.toString
      )
    ) deepMerge Json.obj("_rev" -> Json.fromLong(rev))

  def listResponse(label: Label, deprecated: Boolean): Json        =
    jsonContentOf(
      "/realms/list-realms-template.json",
      Map(
        quote("{label}")      -> label.value,
        quote("{createdBy}")  -> Anonymous.id.asString,
        quote("{updatedBy}")  -> Anonymous.id.asString,
        quote("{deprecated}") -> deprecated.toString
      )
    )

  def resource(label: Label, rev: Long, realm: ActiveRealm): Resource =
    ResourceF(label.toIri(http.realmsIri), rev, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Right(realm))

  def metaResponse(label: Label, rev: Long, deprecated: Boolean): Json     =
    jsonContentOf(
      "/realms/realm-meta-template.json",
      Map(
        quote("{label}")      -> label.value,
        quote("{createdBy}")  -> Anonymous.id.asString,
        quote("{updatedBy}")  -> Anonymous.id.asString,
        quote("{deprecated}") -> deprecated.toString
      )
    ) deepMerge Json.obj("_rev" -> Json.fromLong(rev))

  def meta(label: Label, rev: Long, deprecated: Boolean): ResourceMetadata =
    ResourceF(
      label.toIri(http.realmsIri),
      rev,
      types,
      Instant.EPOCH,
      Anonymous,
      Instant.EPOCH,
      Anonymous,
      label -> deprecated
    )

  "A RealmsRoute" should {
    val routes       = Routes.wrap(new RealmsRoutes(acls, realms).routes)
    val label        = Label.unsafe("therealm")
    val name         = "The Realm"
    val openIdConfig = Url("http://localhost:8080/realm").rightValue
    val logo         = Url("http://localhost:8080/realm/logo").rightValue
    "create a new realm" in {
      realms.create(any[Label], any[String], any[Url], any[Option[Url]])(any[Caller]) shouldReturn Task.pure(
        Right(meta(label, 1L, false))
      )
      Put("/realms/therealm", jsonContentOf("/realms/create-realm.json")) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        responseAs[Json].sort shouldEqual metaResponse(label, 1L, false).sort
      }
    }
    "update an existing realm" in {
      realms.update(any[Label], any[Long], any[String], any[Url], any[Option[Url]])(any[Caller]) shouldReturn Task
        .pure(Right(meta(label, 1L, false)))
      Put("/realms/therealm?rev=1", jsonContentOf("/realms/create-realm.json")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual metaResponse(label, 1L, false).sort
      }
    }
    val realm        = ActiveRealm(
      label,
      name,
      openIdConfig,
      "issuer",
      Set(GrantType.Implicit),
      Some(logo),
      authorizationEndpoint,
      tokenEndpoint,
      userInfoEndpoint,
      revocationEndpoint,
      endSessionEndpoint,
      Set.empty
    )
    "fetch a realm by id" in {
      realms.fetch(any[Label])(any[Caller]) shouldReturn Task.pure(Some(resource(label, 1L, realm)))
      Get("/realms/therealm") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual response(label, 1L, false).sort
      }
    }
    "fetch a realm by id and rev" in {
      realms.fetch(any[Label], any[Long])(any[Caller]) shouldReturn Task.pure(Some(resource(label, 1L, realm)))
      Get("/realms/therealm?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual response(label, 1L, false).sort
      }
    }
    "list realms" in {
      realms.list(SearchParams(deprecated = Some(true), rev = Some(2L)))(any[Caller]) shouldReturn Task.pure(
        List(resource(label, 1L, realm))
      )
      Get("/realms?deprecated=true&rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual listResponse(label, false).sort
      }
    }
    "deprecate a realm" in {
      realms.deprecate(any[Label], any[Long])(any[Caller]) shouldReturn Task.pure(Right(meta(label, 1L, true)))
      Delete("/realms/therealm?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual metaResponse(label, 1L, true).sort
      }
    }
    "return 404 for wrong revision" in {
      realms.fetch(any[Label], any[Long])(any[Caller]) shouldReturn Task.pure(None)
      Get("/realms/therealm?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json] shouldEqual jsonContentOf("/resources/not-found.json")
      }
    }
    "access an endpoint that does not exists" in {
      Get("/other/therealm?rev=5") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json] shouldEqual jsonContentOf("/resources/not-found.json")
      }
    }
  }

}
