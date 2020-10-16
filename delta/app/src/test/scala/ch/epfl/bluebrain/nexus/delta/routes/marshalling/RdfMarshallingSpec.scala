package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.SimpleResource
import ch.epfl.bluebrain.nexus.delta.SimpleResource.{context, contextIri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.syntax._
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestMatchers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext

class RdfMarshallingSpec
    extends TestKit(ActorSystem("DeltaMarshallingSpec"))
    with AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with RdfMarshalling
    with IOValues
    with RouteHelpers
    with TestMatchers {

  implicit private val ec: ExecutionContext              = system.dispatcher
  implicit private val rcr: RemoteContextResolutionDummy = RemoteContextResolutionDummy(contextIri -> context)
  implicit private val ordering: JsonKeyOrdering         = JsonKeyOrdering(List("@context", "@id"), List("_rev", "_createdAt"))

  private val id       = nxv + "myresource"
  private val resource = SimpleResource(id, 1L, Instant.EPOCH, "Maria", 20)

  "Converting JsonLd into an HttpResponse" should {
    val compacted = resource.toCompactedJsonLd.accepted
    val expanded  = resource.toExpandedJsonLd.accepted

    "succeed as compacted form" in {
      val response = Marshal(StatusCodes.OK -> compacted).to[HttpResponse].futureValue
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual compacted.json
      response.entity.contentType shouldEqual `application/ld+json`.toContentType
    }

    "succeed as expanded form" in {
      val response = Marshal(StatusCodes.OK -> expanded).to[HttpResponse].futureValue
      response.status shouldEqual StatusCodes.OK
      response.asJson shouldEqual expanded.json
      response.entity.contentType shouldEqual `application/ld+json`.toContentType
    }
  }

  "Converting Dot into an HttpResponse" should {
    val dot = resource.toDot.accepted

    "succeed" in {
      val response = Marshal(StatusCodes.OK -> dot).to[HttpResponse].futureValue
      response.status shouldEqual StatusCodes.OK
      response.asString should equalLinesUnordered(dot.value)
      response.entity.contentType shouldEqual `application/vnd.graphviz`.toContentType
    }
  }

  "Converting NTriples into an HttpResponse" should {
    val ntriples = resource.toNTriples.accepted

    "succeed" in {
      val response = Marshal(StatusCodes.OK -> ntriples).to[HttpResponse].futureValue
      response.status shouldEqual StatusCodes.OK
      response.asString should equalLinesUnordered(ntriples.value)
      response.entity.contentType shouldEqual `application/n-triples`.toContentType
    }
  }

}
