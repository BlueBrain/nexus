package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.MediaRange._
import akka.http.scaladsl.model.MediaRanges.{`*/*`, `application/*`, `audio/*`, `text/*`}
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/plain`}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Allow}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{InvalidRequiredValueForQueryParamRejection, Route, UnacceptedResponseContentTypeRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.SimpleRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers, TestMatchers}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class DeltaDirectivesSpec
    extends RouteHelpers
    with Matchers
    with OptionValues
    with CirceLiteral
    with IOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val ordering: JsonKeyOrdering = JsonKeyOrdering(List("@context", "@id"), List("_rev", "_createdAt"))
  implicit private val s: Scheduler              = Scheduler.global

  private val id       = nxv + "myresource"
  private val resource = SimpleResource(id, 1L, Instant.EPOCH, "Maria", 20)

  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context,
      contexts.error             -> jsonContentOf("/contexts/error.json")
    )

  private val route: Route =
    get {
      concat(
        path("uio") {
          emit(Accepted, UIO.pure(resource))
        },
        path("io") {
          emit(Accepted, IO.fromEither[SimpleRejection, SimpleResource](Right(resource)))
        },
        path("bad-request") {
          emit(Accepted, IO.fromEither[SimpleRejection, SimpleResource](Left(badRequestRejection)))
        },
        path("conflict") {
          emit(Accepted, IO.fromEither[SimpleRejection, SimpleResource](Left(conflictRejection)))
        }
      )
    }

  "A route" should {

    "return the appropriate content type for Accept header that matches supported" in {
      val endpoints     = List("/uio", "/io", "/bad-request")
      val acceptMapping = Map[Accept, MediaType](
        Accept(`*/*`)                                          -> `application/ld+json`,
        Accept(`application/ld+json`)                          -> `application/ld+json`,
        Accept(`application/json`)                             -> `application/json`,
        Accept(`application/json`, `application/ld+json`)      -> `application/json`,
        Accept(`application/ld+json`, `application/json`)      -> `application/ld+json`,
        Accept(`application/json`, `text/plain`)               -> `application/json`,
        Accept(`text/vnd.graphviz`, `application/json`)        -> `text/vnd.graphviz`,
        Accept(`application/*`)                                -> `application/ld+json`,
        Accept(`application/*`, `text/plain`)                  -> `application/ld+json`,
        Accept(`text/*`)                                       -> `text/vnd.graphviz`,
        Accept(`application/n-triples`, `application/ld+json`) -> `application/n-triples`,
        Accept(`text/plain`, `application/n-triples`)          -> `application/n-triples`
      )
      forAll(endpoints) { endpoint =>
        forAll(acceptMapping) { case (accept, mt) =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.header[`Content-Type`].value.contentType.mediaType shouldEqual mt
          }
        }
      }
    }

    "return the application/ld+json for missing Accept header" in {
      val endpoints = List("/uio", "/io", "/bad-request")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> route ~> check {
          response.header[`Content-Type`].value.contentType.mediaType shouldEqual `application/ld+json`
        }
      }
    }

    "reject the request for unsupported Accept header value" in {
      val endpoints = List("/uio", "/io", "/bad-request")
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> Accept(`text/plain`) ~> route ~> check {
          rejection shouldEqual UnacceptedResponseContentTypeRejection(
            Set(`application/ld+json`, `application/json`, `application/n-triples`, `text/vnd.graphviz`)
          )
        }
      }
    }

    "return payload in compacted JSON-LD format" in {
      val compacted = resource.toCompactedJsonLd.accepted

      forAll(List("/uio?format=compacted", "/uio")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual compacted.json
            response.status shouldEqual Accepted
          }
        }
      }

      forAll(List("/io?format=compacted", "/io")) { endpoint =>
        forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
          Get(endpoint) ~> accept ~> route ~> check {
            response.asJson shouldEqual compacted.json
            response.status shouldEqual Accepted
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
        }
      }

      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/io?format=expanded") ~> accept ~> route ~> check {
          response.asJson shouldEqual expanded.json
          response.status shouldEqual Accepted
        }
      }
    }

    "return payload in Dot format" in {
      val dot = resource.toDot.accepted

      Get("/uio") ~> Accept(`text/vnd.graphviz`) ~> route ~> check {
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual Accepted
      }

      Get("/io") ~> Accept(`text/vnd.graphviz`) ~> route ~> check {
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual Accepted
      }
    }

    "return payload in NTriples format" in {
      val ntriples = resource.toNTriples.accepted

      Get("/uio") ~> Accept(`application/n-triples`) ~> route ~> check {
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual Accepted
      }

      Get("/io") ~> Accept(`application/n-triples`) ~> route ~> check {
        response.asString should equalLinesUnordered(ntriples.value)
        response.status shouldEqual Accepted
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

    "return bad request rejection in Dot format" in {
      Get("/bad-request") ~> Accept(`text/vnd.graphviz`) ~> route ~> check {
        val dot = badRequestRejection.toDot.accepted
        response.asString should equalLinesUnordered(dot.value)
        response.status shouldEqual BadRequest
        response.headers shouldEqual Seq(Allow(GET))
      }
    }

    "return conflict rejection in Dot format" in {
      Get("/conflict") ~> Accept(`text/vnd.graphviz`) ~> route ~> check {
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
