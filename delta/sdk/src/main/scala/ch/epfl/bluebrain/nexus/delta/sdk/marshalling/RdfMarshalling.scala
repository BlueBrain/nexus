package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NQuads, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Json, Printer}

/**
  * Marshallings that allow Akka Http to convert a type ''A'' to an [[HttpEntity]].
  */
trait RdfMarshalling {

  val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

  private val ntriplesMediaTypes = List(`application/n-triples`, `text/plain`)
  private val jsonMediaTypes     = List(`application/json`, `application/ld+json`.toContentType)

  /**
    * JsonLd -> HttpEntity
    */
  implicit def jsonLdMarshaller[A <: JsonLd](implicit
      ordering: JsonKeyOrdering,
      printer: Printer = defaultPrinter
  ): ToEntityMarshaller[A] =
    Marshaller.withFixedContentType(ContentType(`application/ld+json`)) { jsonLd =>
      HttpEntity(
        `application/ld+json`,
        ByteString(printer.printToByteBuffer(jsonLd.json.sort, `application/ld+json`.charset.nioCharset()))
      )
    }

  /**
    * Json -> HttpEntity
    */
  def customContentTypeJsonMarshaller(
      contentType: ContentType
  )(implicit ordering: JsonKeyOrdering, printer: Printer = defaultPrinter): ToEntityMarshaller[Json] =
    Marshaller.withFixedContentType(contentType) { json =>
      HttpEntity(
        contentType,
        ByteString(
          printer.printToByteBuffer(json.sort, contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).nioCharset())
        )
      )
    }

  /**
    * Json -> HttpEntity
    */
  implicit def jsonMarshaller(implicit
      ordering: JsonKeyOrdering,
      printer: Printer = defaultPrinter
  ): ToEntityMarshaller[Json] = {
    def inner(mediaType: ContentType.WithFixedCharset): ToEntityMarshaller[Json] =
      Marshaller.withFixedContentType(mediaType) { json =>
        HttpEntity(
          mediaType,
          ByteString(printer.printToByteBuffer(json.sort, mediaType.charset.nioCharset()))
        )
      }

    Marshaller.oneOf(jsonMediaTypes.map(inner): _*)

  }

  /**
    * NTriples -> HttpEntity
    */
  implicit val nTriplesMarshaller: ToEntityMarshaller[NTriples] = {
    def inner(mediaType: MediaType.NonBinary): ToEntityMarshaller[NTriples] =
      Marshaller.StringMarshaller.wrap(mediaType)(_.value)

    Marshaller.oneOf(ntriplesMediaTypes.map(inner): _*)
  }

  /**
    * NQuads -> HttpEntity
    */
  implicit val nQuadsMarshaller: ToEntityMarshaller[NQuads] =
    Marshaller.withFixedContentType(ContentType(`application/n-quads`)) { case NQuads(value, _) =>
      HttpEntity(`application/n-quads`, ByteString(value))
    }

  /**
    * Dot -> HttpEntity
    */
  implicit val dotMarshaller: ToEntityMarshaller[Dot] =
    Marshaller.withFixedContentType(ContentType(`text/vnd.graphviz`)) { case Dot(value, _) =>
      HttpEntity(`text/vnd.graphviz`, ByteString(value))
    }
}

object RdfMarshalling extends RdfMarshalling
