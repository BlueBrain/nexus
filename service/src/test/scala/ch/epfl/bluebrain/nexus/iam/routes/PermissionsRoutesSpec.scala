package ch.epfl.bluebrain.nexus.iam.routes

import java.time.Instant
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.config.{IamConfig, Settings}
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.permissions._
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.testsyntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF}
import ch.epfl.bluebrain.nexus.util.{Randomness, Resources}
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

//noinspection TypeAnnotation,RedundantDefaultArgument
class PermissionsRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with IdiomaticMockito
    with Randomness {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  private val appConfig: IamConfig = Settings(system).appConfig
  implicit private val http        = appConfig.http

  private val perms: Permissions[Task] = mock[Permissions[Task]]
  private val realms: Realms[Task]     = mock[Realms[Task]]

  before {
    Mockito.reset(perms, realms)
    realms.caller(any[AccessToken]) shouldReturn Task.pure(Caller.anonymous)
  }

  def response(rev: Long): Json =
    jsonContentOf(
      "/permissions/permissions-template.json",
      Map(quote("{createdBy}") -> Anonymous.id.asString, quote("{updatedBy}") -> Anonymous.id.asString)
    ) deepMerge Json.obj("_rev" -> Json.fromLong(rev))

  def resource(rev: Long, set: Set[Permission]): Resource =
    ResourceF(id, rev, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, set)

  def metaResponse(rev: Long): Json =
    jsonContentOf(
      "/permissions/permissions-meta-template.json",
      Map(quote("{createdBy}") -> Anonymous.id.asString, quote("{updatedBy}") -> Anonymous.id.asString)
    ) deepMerge Json.obj("_rev" -> Json.fromLong(rev))

  def meta(rev: Long): ResourceF[Unit] =
    ResourceF.unit(id, rev, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  def missingParams: Json =
    jsonContentOf("/permissions/missing-rev.json")

  "A PermissionsRoute" should {
    val routes = Routes.wrap(new PermissionsRoutes(perms, realms).routes)
    "return the default minimum permissions" in {
      perms.fetch(any[Caller]) shouldReturn Task.pure(resource(0L, appConfig.permissions.minimum))
      Get("/permissions") ~> routes ~> check {
        responseAs[Json].sort shouldEqual response(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "return missing rev params" when {
      "attempting to delete" in {
        Delete("/permissions") ~> routes ~> check {
          responseAs[Json].sort shouldEqual missingParams.sort
          status shouldEqual StatusCodes.BadRequest
        }
      }
    }
    "replace permissions" in {
      perms.replace(any[Set[Permission]], 2L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      val json = Json.obj("permissions" -> Json.arr(Json.fromString("random/a")))
      Put("/permissions?rev=2", json) ~> routes ~> check {
        responseAs[Json].sort shouldEqual metaResponse(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "default rev to 0L for replace permissions" in {
      perms.replace(any[Set[Permission]], 0L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      val json = Json.obj("permissions" -> Json.arr(Json.fromString("random/a")))
      Put("/permissions", json) ~> routes ~> check {
        responseAs[Json].sort shouldEqual metaResponse(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "append new permissions" in {
      perms.append(any[Set[Permission]], 2L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      val json = Json.obj("@type" -> Json.fromString("Append"), "permissions" -> Json.arr(Json.fromString("random/a")))
      Patch("/permissions?rev=2", json) ~> routes ~> check {
        responseAs[Json].sort shouldEqual metaResponse(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "subtract permissions" in {
      perms.subtract(any[Set[Permission]], 2L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      val json =
        Json.obj("@type" -> Json.fromString("Subtract"), "permissions" -> Json.arr(Json.fromString("random/a")))
      Patch("/permissions?rev=2", json) ~> routes ~> check {
        responseAs[Json].sort shouldEqual metaResponse(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "delete permissions" in {
      perms.delete(2L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      Delete("/permissions?rev=2") ~> routes ~> check {
        responseAs[Json].sort shouldEqual metaResponse(0L).sort
        status shouldEqual StatusCodes.OK
      }
    }
    "return 404 for wrong revision" in {
      perms.fetchAt(any[Long])(any[Caller]) shouldReturn Task.pure(None)
      Get("/permissions?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        responseAs[Json] shouldEqual jsonContentOf("/resources/not-found.json")
      }
    }
    "return 200 for correct revision" in {
      perms.fetchAt(any[Long])(any[Caller]) shouldReturn Task.pure(Some(resource(3L, appConfig.permissions.minimum)))
      Get("/permissions?rev=2") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual response(3L).sort
      }
    }

    "return 400 when trying to create permission which is too long" in {
      perms.append(any[Set[Permission]], 2L)(any[Caller]) shouldReturn Task.pure(Right(meta(0L)))
      val json =
        Json.obj(
          "@type"       -> Json.fromString("Append"),
          "permissions" -> Json.arr(Json.fromString(s"${genString()}/${genString()}"))
        )
      Patch("/permissions?rev=2", json) ~> routes ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }

}
