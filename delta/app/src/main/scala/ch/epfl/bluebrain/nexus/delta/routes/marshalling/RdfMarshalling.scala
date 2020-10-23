package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.{ContentType, HttpEntity}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.delta.syntax._
import io.circe.Printer

/**
  * Marshallings that allow Akka Http to convert a type ''A'' to an [[HttpEntity]].
  */
trait RdfMarshalling {

  private val defaultPrinter: Printer = Printer(dropNullValues = true, indent = "")

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
    * NTriples -> HttpEntity
    */
  implicit val ntriplesMarshaller: ToEntityMarshaller[NTriples] =
    Marshaller.withFixedContentType(ContentType(`application/n-triples`)) { case NTriples(value, _) =>
      HttpEntity(`application/n-triples`, value)
    }

  /**
    * Dot -> HttpEntity
    */
  implicit val dotMarshaller: ToEntityMarshaller[Dot] =
    Marshaller.withFixedContentType(ContentType(`application/vnd.graphviz`)) { case Dot(value, _) =>
      HttpEntity(`application/vnd.graphviz`, value)
    }
}

object RdfMarshalling extends RdfMarshalling
