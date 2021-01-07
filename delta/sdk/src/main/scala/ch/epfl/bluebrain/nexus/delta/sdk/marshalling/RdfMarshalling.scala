package ch.epfl.bluebrain.nexus.delta.sdk.marshalling

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.{ContentType, HttpEntity}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Json, Printer}

/**
  * Marshallings that allow Akka Http to convert a type ''A'' to an [[HttpEntity]].
  */
trait RdfMarshalling {

  val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

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
  implicit def jsonMarshaller(implicit
      ordering: JsonKeyOrdering,
      printer: Printer = defaultPrinter
  ): ToEntityMarshaller[Json] =
    Marshaller.withFixedContentType(`application/json`) { json =>
      HttpEntity(
        `application/json`,
        ByteString(printer.printToByteBuffer(json.sort, `application/json`.charset.nioCharset()))
      )
    }

  /**
    * NTriples -> HttpEntity
    */
  implicit val ntriplesMarshaller: ToEntityMarshaller[NTriples] =
    Marshaller.withFixedContentType(ContentType(`application/n-triples`)) { case NTriples(value, _) =>
      HttpEntity(`application/n-triples`, ByteString(value))
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
