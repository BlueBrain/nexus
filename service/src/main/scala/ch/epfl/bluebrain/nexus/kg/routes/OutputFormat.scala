package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes.`application/octet-stream`
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `application/x-tar`}
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes._

/**
  * Enumeration of output format types.
  */
sealed trait OutputFormat extends Product with Serializable

/**
  * Enumeration of non-binary output format types.
  */
sealed trait NonBinaryOutputFormat extends OutputFormat

/**
  * Enumeration of text output format types.
  */
sealed trait TextOutputFormat extends NonBinaryOutputFormat {
  def contentType: ContentType.NonBinary
}

/**
  * Enumeration of JSON-LD output format types.
  */
sealed trait JsonLDOutputFormat extends NonBinaryOutputFormat {

  /**
    * @return the format name
    */
  def name: String

  val contentType: Set[ContentType] = Set(`application/json`, `application/ld+json`)
}

object OutputFormat {

  /**
    * JSON-LD compacted output
    */
  final case object Compacted extends JsonLDOutputFormat {
    val name = "compacted"
  }

  /**
    * JSON-LD expanded output
    */
  final case object Expanded extends JsonLDOutputFormat {
    val name = "expanded"
  }

  /**
    * triples output
    */
  final case object Triples extends TextOutputFormat {
    val contentType = `application/n-triples`
  }

  /**
    * DOT language output
    */
  final case object DOT extends TextOutputFormat {
    val contentType = `application/vnd.graphviz`
  }

  /**
    * Binary output
    */
  final case object Binary extends OutputFormat {
    val contentType: ContentType.Binary = `application/octet-stream`
  }

  /**
    * Tarball output
    */
  final case object Tar extends OutputFormat {
    val contentType: ContentType = `application/x-tar`
  }

  /**
    * Attempts to build an output format from a name.
    *
    * @param name the output format name
    * @return Some(output) if the name matches some of the existing output formats,
    *         None otherwise
    */
  final def apply(name: String): Option[OutputFormat] =
    if (name == Compacted.name) Some(Compacted)
    else if (name == Expanded.name) Some(Expanded)
    else None
}
