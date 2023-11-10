package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.MediaRange._
import akka.http.scaladsl.model.MediaRanges.{`*/*`, `application/*`, `audio/*`, `text/*`}
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/html`, `text/plain`}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Content-Type`, Accept, Allow, Location}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.SimpleRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.SimpleResource.rawHeader
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling
import DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectivesSpec.SimpleResource2
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.scalatest.TestMatchers
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class DeltaDirectivesSpec
    extends RouteHelpers
    with Matchers
    with OptionValues
    with CirceMarshalling
    with CirceLiteral
    with CatsIOValues
    with TestMatchers
    with TestHelpers
    with Inspectors {

  implicit private val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit private val f: FusionConfig =
    FusionConfig(Uri("https://bbp.epfl.ch/nexus/web/"), enableRedirects = true, Uri("https://bbp.epfl.ch"))

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val id                = nxv + "myresource"
  private val resource          = SimpleResource(id, 1, Instant.EPOCH, "Maria", 20)
  private val resourceFusionUri = Uri(
    "https://bbp.epfl.ch/nexus/web/org/proj/resources/https:%2F%2Fbluebrain.github.io%2Fnexus%2Fvocabulary%2Fid"
  )

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context,
      contexts.error             -> jsonContentOf("/contexts/error.json").topContextValueOrEmpty
    )

  private val compacted = resource.toCompactedJsonLd.accepted

  val ioResource: IO[Either[SimpleRejection, SimpleResource]]   = IO.pure(Right(resource))
  val ioBadRequest: IO[Either[SimpleRejection, SimpleResource]] = IO.pure(Left(badRequestRejection))
  val ioConflict: IO[Either[SimpleRejection, SimpleResource]]   = IO.pure(Left(conflictRejection))

  val redirectTarget: Uri                                   = s"http://localhost/${genString()}"
  val ioRedirect: IO[Uri]                                   = IO.pure(redirectTarget)
  val ioRedirectRejection: IO[Either[SimpleRejection, Uri]] = IO.pure(Left(badRequestRejection))

  private val ref: ProjectRef  = ProjectRef.unsafe("org", "proj")
  private val ioProject        = IO.pure(ref.asJson)
  private val projectFusionUri = Uri("https://bbp.epfl.ch/nexus/web/admin/org/proj")

  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  private val routeUnsealed: Route =
    get {
      concat(
        path("uio") {
          emit(Accepted, IO.pure(resource))
        },
        path("io") {
          emit(Accepted, ioResource)
        },
        path("value") {
          emit(resource)
        },
        path("bad-request") {
          emit(Accepted, ioBadRequest)
        },
        path("conflict") {
          emit(Accepted, ioConflict)
        },
        path("fail") {
          emit(SimpleResource2(nxv + "myid", 1))
        },
        path("throw") {
          throw new IllegalArgumentException("")
        },
        (path("reject") & parameter("failFast" ? false)) { failFast =>
          val io =
            if (!failFast) ioBadRequest.rejectOn[BadRequestRejection] else ioBadRequest.rejectOn[ConflictRejection]
          emit(io)
        },
        path("redirectIO") {
          emitRedirect(StatusCodes.SeeOther, ioRedirect)
        },
        pathPrefix("resources") {
          concat(
            path("redirectFusionDisabled") {
              emitOrFusionRedirect(ref, Latest(nxv + "id"), emit(resource))(f.copy(enableRedirects = false))
            },
            path("redirectFusionLatest") {
              emitOrFusionRedirect(ref, Latest(nxv + "id"), emit(resource))
            },
            path("redirectFusionRev") {
              emitOrFusionRedirect(ref, Revision(nxv + "id", 7), emit(resource))
            },
            path("redirectFusionTag") {
              emitOrFusionRedirect(ref, Tag(nxv + "id", UserTag.unsafe("my-tag")), emit(resource))
            },
            path("redirectFusionDisabled") {
              emitOrFusionRedirect(ref, Latest(nxv + "id"), emit(resource))(f.copy(enableRedirects = false))
            }
          )
        },
        pathPrefix("projects") {
          concat(
            path("redirectFusionDisabled") {
              emitOrFusionRedirect(ref, emit(ioProject))(f.copy(enableRedirects = false))
            },
            path("redirectFusion") {
              emitOrFusionRedirect(ref, emit(ioProject))
            }
          )
        },
        path("redirectFail") {
          emitRedirect(StatusCodes.SeeOther, ioRedirectRejection)
        }
      )
    }

  private val route2Unsealed: Route =
    get {
      path("reject") {
        emit(ioResource)
      }
    }

  private val route: Route = Route.seal(routeUnsealed)

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
        Accept(`text/plain`, `application/n-triples`)          -> `application/n-triples`,
        Accept(`application/n-quads`, `application/ld+json`)   -> `application/n-quads`,
        Accept(`text/plain`, `application/n-quads`)            -> `application/n-quads`
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

    "return payload in compacted JSON-LD format" in {

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

      forAll(List(Accept(`*/*`), Accept(`application/*`, `application/ld+json`))) { accept =>
        Get("/value?format=compacted") ~> accept ~> route ~> check {
          response.asJson shouldEqual compacted.json
          response.status shouldEqual Accepted
          response.headers.toList should contain(rawHeader)
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

    "return payload in NQuads format" in {
      val nQuads = resource.toNQuads.accepted

      Get("/uio") ~> Accept(`application/n-quads`) ~> route ~> check {
        response.asString should equalLinesUnordered(nQuads.value)
        response.status shouldEqual Accepted
      }

      Get("/io") ~> Accept(`application/n-quads`) ~> route ~> check {
        response.asString should equalLinesUnordered(nQuads.value)
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

    "handle RdfError using RdfExceptionHandler" in {
      Get("/fail") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("directives/invalid-remote-context-error.json")
        response.status shouldEqual InternalServerError
      }
    }

    "handle unexpected exception using RdfExceptionHandler" in {
      Get("/throw") ~> Accept(`*/*`) ~> route ~> check {
        response.asJson shouldEqual jsonContentOf("directives/unexpected-error.json")
        response.status shouldEqual InternalServerError
      }
    }

    "reject when unaccepted Accept Header provided" in {
      forAll(List("/uio", "/io", "/bad-request")) { endpoint =>
        Get(endpoint) ~> Accept(`audio/*`) ~> route ~> check {
          response.asJson shouldEqual jsonContentOf("directives/invalid-response-ct-rejection.json")
          response.status shouldEqual NotAcceptable
        }
      }
    }

    "reject when invalid query parameter provided" in {
      forAll(List("/uio?format=fake", "/io?format=fake")) { endpoint =>
        Get(endpoint) ~> Accept(`*/*`) ~> route ~> check {
          response.asJson shouldEqual jsonContentOf("directives/invalid-format-rejection.json")
          response.status shouldEqual NotFound
        }
      }
    }

    "reject the first route and return the second" in {
      val compacted = resource.toCompactedJsonLd.accepted

      Get("/reject?failFast=false") ~> Accept(`*/*`) ~> Route.seal(routeUnsealed ~ route2Unsealed) ~> check {
        response.asJson shouldEqual compacted.json
        response.status shouldEqual OK
      }
    }

    "fail fast on the first route without attempting the second" in {
      val badRequestCompacted = badRequestRejection.toCompactedJsonLd.accepted
      forAll(List(Route.seal(routeUnsealed ~ route2Unsealed), Route.seal(routeUnsealed))) { r =>
        Get("/reject?failFast=true") ~> Accept(`*/*`) ~> r ~> check {
          response.asJson shouldEqual badRequestCompacted.json
          response.status shouldEqual BadRequest
        }
      }
    }

    "redirect a successful io" in {
      Get("/redirectIO") ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual redirectTarget
      }
    }

    "provide a correctly encoded error" in {
      val badRequestCompacted = badRequestRejection.toCompactedJsonLd.accepted
      Get("/redirectFail") ~> route ~> check {
        response.status shouldEqual BadRequest
        response.asJson shouldEqual badRequestCompacted.json
      }
    }

    "not redirect to the resource fusion page if the feature is disabled" in {
      Get("/resources/redirectFusionDisabled") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.NotAcceptable
      }
    }

    "not redirect to the resource fusion page if the Accept header is not set to text/html" in {
      Get("/resources/redirectFusionLatest") ~> Accept(`application/json`) ~> route ~> check {
        response.asJson shouldEqual compacted.json
        response.status shouldEqual Accepted
      }
    }

    "redirect to the resource fusion page with the latest version if the Accept header is set to text/html" in {
      Get("/resources/redirectFusionLatest") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual resourceFusionUri
      }
    }

    "redirect to the resource fusion page with a fixed rev if the Accept header is set to text/html" in {
      Get("/resources/redirectFusionRev") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual resourceFusionUri.withQuery(Uri.Query("rev" -> "7"))
      }
    }

    "redirect to the resource fusion page with a given tag if the Accept header is set to text/html" in {
      Get("/resources/redirectFusionTag") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual resourceFusionUri.withQuery(Uri.Query("tag" -> "my-tag"))
      }
    }

    "not redirect to the project fusion page if the feature is disabled" in
      Get("/projects/redirectFusionDisabled") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.NotAcceptable
      }

    "not redirect to the project fusion page if the Accept header is not set to text/html" in
      Get("/projects/redirectFusion") ~> Accept(`application/json`) ~> route ~> check {
        response.asJson shouldEqual ref.asJson
        response.status shouldEqual OK
      }

    "redirect to the project fusion page with the latest version if the Accept header is set to text/html" in
      Get("/projects/redirectFusion") ~> Accept(`text/html`) ~> route ~> check {
        response.status shouldEqual StatusCodes.SeeOther
        response.header[Location].value.uri shouldEqual projectFusionUri
      }

  }

}

object DeltaDirectivesSpec {
  final case class SimpleResource2(id: Iri, rev: Int)

  object SimpleResource2 extends CirceLiteral {

    val contextIri: Iri = iri"http://example.com/contexts/simple-resource-2.json"

    implicit private val simpleResource2Encoder: Encoder.AsObject[SimpleResource2] =
      Encoder.AsObject.instance(v => JsonObject.empty.add("@id", v.id.asJson).add("_rev", v.rev.asJson))

    implicit val simpleResource2JsonLdEncoder: JsonLdEncoder[SimpleResource2] =
      JsonLdEncoder.computeFromCirce(_.id, ContextValue(contextIri))

  }
}
