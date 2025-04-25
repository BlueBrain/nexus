package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.{ContentTypeRange, MediaType}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.RdfMediaTypes
import org.http4s.EntityDecoder

import scala.xml.{Elem, NodeSeq}

trait XmlSupport {
  private val xmlMediaTypes: Seq[MediaType.NonBinary] =
    List(RdfMediaTypes.`application/rdf+xml`, RdfMediaTypes.`application/sparql-results+xml`)

  private val xmlContentTypeRanges: Seq[ContentTypeRange] =
    xmlMediaTypes.map(ContentTypeRange(_))

  implicit val nodeSeqUnmarshaller: FromEntityUnmarshaller[NodeSeq] =
    ScalaXmlSupport.nodeSeqUnmarshaller(xmlContentTypeRanges*)

  implicit val nodeSeqMarshaller: ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(xmlMediaTypes.map(ScalaXmlSupport.nodeSeqMarshaller)*)

  implicit val xmlEntityDecoder: EntityDecoder[IO, Elem] = org.http4s.scalaxml.xmlDecoder[IO]

}
object XmlSupport extends XmlSupport {}
