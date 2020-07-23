package ch.epfl.bluebrain.nexus.kg.directives

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.directives.PathDirectives.pathPrefix
import akka.http.scaladsl.server.{Directive0, PathMatcher, PathMatcher1}
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.{Curie, Iri}

object PathDirectives {

  /**
    * Either a catch-all [[Underscore]] or a specific [[SchemaId]].
    */
  sealed trait IdOrUnderscore extends Product with Serializable

  /**
    * Represents a catch-all '_' passed instead of an explicit schema URI.
    */
  case object Underscore extends IdOrUnderscore

  /**
    * Holds a schema URI decoded from the segment.
    */
  final case class SchemaId(value: AbsoluteIri) extends IdOrUnderscore

  /**
    * Attempts to match a segment and build an [[IdOrUnderscore]] by:
    *
    * Mapping the segment to a catch-all (any schema) if the segment is '_'
    * Mapping the segment to an alias on the prefixMappings or
    * Converting the segment to an [[AbsoluteIri]] or
    * Converting the segment to a [[Curie]] and afterwards to an [[AbsoluteIri]] or
    * Joining the ''base'' with the segment and create an [[AbsoluteIri]] from it.
    *
    * @param project the project with its prefixMappings used to expand the alias or curie into an [[AbsoluteIri]]
    */
  @SuppressWarnings(Array("MethodNames"))
  def IdSegmentOrUnderscore(implicit project: ProjectResource): PathMatcher1[IdOrUnderscore] =
    Segment flatMap {
      case "_"   => Some(Underscore)
      case other => toIriOrElseBase(other).map(SchemaId)
    }

  /**
    * Attempts to match a segment and build an [[AbsoluteIri]] by:
    *
    * Mapping the segment to an alias on the prefixMappings or
    * Converting the segment to an [[AbsoluteIri]] or
    * Converting the segment to a [[Curie]] and afterwards to an [[AbsoluteIri]] or
    * Joining the ''base'' with the segment and create an [[AbsoluteIri]] from it.
    *
    * @param project the project with its prefixMappings used to expand the alias or curie into an [[AbsoluteIri]]
    */
  @SuppressWarnings(Array("MethodNames"))
  def IdSegment(implicit project: ProjectResource): PathMatcher1[AbsoluteIri] =
    Segment flatMap toIriOrElseBase

  def toIri(s: String)(implicit project: ProjectResource): Option[AbsoluteIri] =
    project.value.apiMappings.get(s) orElse
      Curie(s).flatMap(_.toIriUnsafePrefix(project.value.apiMappings)).toOption orElse
      Iri.url(s).toOption

  /**
    * Attempts to match a segment and build an [[AbsoluteIri]], as in the method ''IdSegment''.
    * It then attempts to match the resulting absolute iri to the provided ''iri''
    *
    * @param iri     the iri to match against the segment
    * @param project the project with its prefixMappings used to expand the alias or curie into an [[AbsoluteIri]]
    */
  def isIdSegment(iri: AbsoluteIri)(implicit project: ProjectResource): Directive0 = {
    val matcher = new PathMatcher[Unit] {
      def apply(path: Path) =
        path match {
          case Path.Segment(segment, tail) =>
            toIri(segment) match {
              case Some(`iri`) => Matched(tail, ())
              case _           => Unmatched
            }
          case _                           => Unmatched
        }
    }
    pathPrefix(matcher)
  }
}
