package ch.epfl.bluebrain.nexus.delta.routes

import java.time.Instant

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.MediaRanges.{`*/*`, `application/*`, `audio/*`}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, Allow, Cookie}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{InvalidRequiredValueForQueryParamRejection, Route, UnacceptedResponseContentTypeRejection}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.SimpleRejection.{badRequestRejection, conflictRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RemoteContextResolutionDummy
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.{SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestMatchers}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import akka.http.scaladsl.model.StatusCodes._

class DeltaDirectivesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceLiteral
    with DeltaDirectives
    with IOValues
    with RouteHelpers
    with TestMatchers
    with Inspectors {

  implicit private val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context
    )

  implicit private val ordering: JsonKeyOrdering = JsonKeyOrdering(List("@context", "@id"), List("_rev", "_createdAt"))
  implicit private val s: Scheduler              = Scheduler.global

  private val id       = nxv + "myresource"
  private val resource = SimpleResource(id, 1L, Instant.EPOCH, "Maria", 20)

  private val route: Route =
    get {
      concat(
        path("uio") {
          completeUIO(Accepted, UIO.pure(resource))
        },
        path("io") {
          completeIO(Accepted, Seq(Cookie("k", "v")), IO.fromEither[SimpleRejection, SimpleResource](Right(resource)))
        },
        path("bad-request") {
          completeIO(Accepted, IO.fromEither[SimpleRejection, SimpleResource](Left(badRequestRejection)))
        },
        path("conflict") {
          completeIO(Accepted, IO.fromEither[SimpleRejection, SimpleResource](Left(conflictRejection)))
        }
      )
    }

  "A route" should {

    "return payload in compacted JSON-LD format" in {
      val compacted = resource.toCompactedJsonLd.accepted

      forAll(List("/uio?format=compacted", "/uio")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual compacted.json
            response.status shouldEqual Accepted
            response.headers shouldEqual Seq.empty[HttpHeader]
          }
        }
      }

      forAll(List("/io?format=compacted", "/io")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual compacted.json
            response.status shouldEqual Accepted
            response.headers shouldEqual Seq(Cookie("k", "v"))
          }
        }
      }
    }

    "return payload in expanded JSON-LD format" in {
      val expanded = resource.toExpandedJsonLd.accepted

      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/uio?format=expanded") ~> accept ~> route ~> check {
          response.asJson shouldEqual expanded.json
          response.status shouldEqual Accepted
          response.headers shouldEqual Seq.empty[HttpHeader]
        }
      }

      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/io?format=expanded") ~> accept ~> route ~> check {
          response.asJson shouldEqual expanded.json
          response.status shouldEqual Accepted
          response.headers shouldEqual Seq(Cookie("k", "v"))
        }
      }
    }

    "return payload in Dot format" in {
      val dot = resource.toDot.accepted

      Get("/uio") ~> Accept(`application/vnd.graphviz`) ~> route ~> check {
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual Accepted
        response.headers shouldEqual Seq.empty[HttpHeader]
      }

      Get("/io") ~> Accept(`application/vnd.graphviz`) ~> route ~> check {
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual Accepted
        response.headers shouldEqual Seq(Cookie("k", "v"))
      }
    }

    "return payload in NTriples format" in {
      val ntriples = resource.toNTriples.accepted

      Get("/uio") ~> Accept(`application/n-triples`) ~> route ~> check {
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual Accepted
        response.headers shouldEqual Seq.empty[HttpHeader]
      }

      Get("/io") ~> Accept(`application/n-triples`) ~> route ~> check {
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual Accepted
        response.headers shouldEqual Seq(Cookie("k", "v"))
      }
    }

    "return bad request rejection in compacted JSON-LD format" in {
      val badRequestCompacted = badRequestRejection.toCompactedJsonLd.accepted

      forAll(List("/bad-request?format=compacted", "/bad-request")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual badRequestCompacted.json
            response.status shouldEqual BadRequest
            response.headers shouldEqual Seq(Allow(GET))
          }
        }
      }
    }

    "return conflict rejection in compacted JSON-LD format" in {
      val conflictCompacted = conflictRejection.toCompactedJsonLd.accepted
      forAll(List("/conflict?format=compacted", "/conflict")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual conflictCompacted.json
            response.status shouldEqual Conflict
            response.headers shouldEqual Seq.empty[HttpHeader]
          }
        }
      }
    }

    "return bad request rejection in expanded JSON-LD format" in {
      val badRequestExpanded = badRequestRejection.toExpandedJsonLd.accepted

      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/bad-request?format=expanded") ~> accept ~> route ~> check {
          response.asJson shouldEqual badRequestExpanded.json
          response.status shouldEqual BadRequest
          response.headers shouldEqual Seq(Allow(GET))
        }
      }
    }

    "return conflict rejection in expanded JSON-LD format" in {
      val conflictExpanded = conflictRejection.toExpandedJsonLd.accepted
      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/conflict?format=expanded") ~> accept ~> route ~> check {
          response.asJson shouldEqual conflictExpanded.json
          response.status shouldEqual Conflict
          response.headers shouldEqual Seq.empty[HttpHeader]
        }
      }
    }

    "return bad request rejection  in Dot format" in {
      Get("/bad-request") ~> Accept(`application/vnd.graphviz`) ~> route ~> check {
        val dot = badRequestRejection.toDot.accepted
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual BadRequest
        response.headers shouldEqual Seq(Allow(GET))
      }
    }

    "return conflict rejection  in Dot format" in {
      Get("/conflict") ~> Accept(`application/vnd.graphviz`) ~> route ~> check {
        val dot = conflictRejection.toDot.accepted
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual Conflict
        response.headers shouldEqual Seq.empty[HttpHeader]
      }
    }

    "return bad request rejection in NTriples format" in {
      Get("/bad-request") ~> Accept(`application/n-triples`) ~> route ~> check {
        val ntriples = badRequestRejection.toNTriples.accepted
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual BadRequest
        response.headers shouldEqual Seq(Allow(GET))
      }
    }

    "return conflict rejection in NTriples format" in {
      Get("/conflict") ~> Accept(`application/n-triples`) ~> route ~> check {
        val ntriples = conflictRejection.toNTriples.accepted
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual Conflict
        response.headers shouldEqual Seq.empty[HttpHeader]
      }
    }

    "reject when unaccepted Accept Header provided" in {
      forAll(List("/uio", "/io")) { endpoint =>
        Get(endpoint) ~> Accept(`audio/*`) ~> route ~> check {
          rejection shouldBe a[UnacceptedResponseContentTypeRejection]
        }
      }
    }

    "reject when invalid query parameter provided" in {
      forAll(List("/uio?format=fake", "/io?format=fake")) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          rejection shouldBe a[InvalidRequiredValueForQueryParamRejection]
        }
      }
    }
  }

}
