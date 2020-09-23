package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, MediaRanges, MediaType, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextNotFound
import ch.epfl.bluebrain.nexus.delta.rdf.{RdfMediaTypes, Vocabulary}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.Seq

class PermissionsRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with FailFastCirceSupport
    with IOValues
    with TestHelpers {

  private def responseAsString(entity: HttpEntity): String =
    IO.fromFuture(entity.dataBytes.runFold("")(_ ++ _.utf8String)).accepted

  override def mediaTypes: Seq[MediaType.WithFixedCharset] =
    List(`application/json`, `application/ld+json`)

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost/v1")

  private val contextMap = Map(
    Vocabulary.contexts.resource    -> jsonContentOf("/contexts/resource.json"),
    Vocabulary.contexts.permissions -> jsonContentOf("/contexts/permissions.json")
  )

  implicit val contextResolution: RemoteContextResolution =
    RemoteContextResolution { iri =>
      IO.fromOption(contextMap.get(iri), RemoteContextNotFound(iri))
    }

  "The Permissions routes" should {
    val dummy = PermissionsDummy(Set(Permission.unsafe("some/minimum"))).accepted
    val route = new PermissionsRoutes(dummy).routes
    "return the minimum permissions" in {

      Get("/v1/permissions") ~> Accept(MediaRanges.`*/*`) ~> route ~> check {
        println(responseAs[Json].spaces2)
      }

      Get("/v1/permissions?format=expanded") ~> Accept(MediaRanges.`*/*`) ~> route ~> check {
        println(responseAs[Json].spaces2)
      }

      Get("/v1/permissions") ~> Accept(RdfMediaTypes.`application/vnd.graphviz`) ~> route ~> check {
        println(responseAsString(responseEntity))
      }

      Get("/v1/permissions") ~> Accept(RdfMediaTypes.`application/n-triples`) ~> route ~> check {
        println(responseAsString(responseEntity))
      }
    }
    "reject replacing permissions" when {
      "the rev is incorrect" in {
        val value =
          json"""
            {
              "permissions": ["some/newperm"]
            }
            """
        Put("/v1/permissions?rev=1", value) ~> Accept(MediaRanges.`*/*`) ~> route ~> check {
          status shouldEqual StatusCodes.Conflict
          println(responseAs[Json].spaces2)
        }
      }
    }
  }

}
