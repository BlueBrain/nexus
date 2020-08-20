package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.Instant

import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.kg.resources.Ref
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.util.Try

sealed trait SparqlLink {

  /**
    * @return the @id value of the resource
    */
  def id: AbsoluteIri

  /**
    * @return the collection of types of this resource
    */
  def types: Set[AbsoluteIri]

  /**
    * @return the paths from where the link has been found
    */
  def paths: List[AbsoluteIri]
}

object SparqlLink {

  /**
    * A link that represents a managed resource on the platform.
    *
    * @param id            the @id value of the resource
    * @param project       the @id of the project where the resource belongs
    * @param self          the access url for this resources
    * @param rev           the revision of the resource
    * @param types         the collection of types of this resource
    * @param deprecated    whether the resource is deprecated of not
    * @param created       the instant when this resource was created
    * @param updated       the last instant when this resource was updated
    * @param createdBy     the identity that created this resource
    * @param updatedBy     the last identity that updated this resource
    * @param constrainedBy the schema that this resource conforms to
    * @param paths         the paths from where the link has been found
    */
  final case class SparqlResourceLink(
      id: AbsoluteIri,
      project: AbsoluteIri,
      self: AbsoluteIri,
      rev: Long,
      types: Set[AbsoluteIri],
      deprecated: Boolean,
      created: Instant,
      updated: Instant,
      createdBy: AbsoluteIri,
      updatedBy: AbsoluteIri,
      constrainedBy: Ref,
      paths: List[AbsoluteIri]
  ) extends SparqlLink

  object SparqlResourceLink {

    /**
      * Attempts to create a [[SparqlResourceLink]] from the given bindings
      *
      * @param bindings      the sparql result bindings
      */
    def apply(bindings: Map[String, Binding]): Option[SparqlLink] =
      for {
        link       <- SparqlExternalLink(bindings)
        project    <- bindings.get(nxv.project.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        self       <- bindings.get(nxv.self.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        rev        <- bindings.get(nxv.rev.prefix).map(_.value).flatMap(v => Try(v.toLong).toOption)
        deprecated <- bindings.get(nxv.deprecated.prefix).map(_.value).flatMap(v => Try(v.toBoolean).toOption)
        created    <- bindings.get(nxv.createdAt.prefix).map(_.value).flatMap(v => Try(Instant.parse(v)).toOption)
        updated    <- bindings.get(nxv.updatedAt.prefix).map(_.value).flatMap(v => Try(Instant.parse(v)).toOption)
        createdBy  <- bindings.get(nxv.createdBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        updatedBy  <- bindings.get(nxv.updatedBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
        consBy     <- bindings.get(nxv.constrainedBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption.map(_.ref))
      } yield
      // format: off
        SparqlResourceLink(link.id, project, self, rev, link.types, deprecated, created, updated, createdBy, updatedBy, consBy, link.paths)
      // format: on

  }

  /**
    * A link that represents an external resource out of the platform.
    *
    * @param id    the @id value of the resource
    * @param paths the predicate from where the link has been found
    * @param types the collection of types of this resource
    */
  final case class SparqlExternalLink(id: AbsoluteIri, paths: List[AbsoluteIri], types: Set[AbsoluteIri] = Set.empty)
      extends SparqlLink

  object SparqlExternalLink {

    /**
      * Attempts to create a [[SparqlExternalLink]] from the given bindings
      *
      * @param bindings the sparql result bindings
      */
    def apply(bindings: Map[String, Binding]): Option[SparqlExternalLink] = {
      val types = bindings.get("types").map(binding => toIris(binding.value).toSet).getOrElse(Set.empty)
      val paths = bindings.get("paths").map(binding => toIris(binding.value).toList).getOrElse(List.empty)
      bindings.get("s").map(_.value).flatMap(Iri.absolute(_).toOption).map(SparqlExternalLink(_, paths, types))
    }
  }

  private def toIris(string: String): Array[AbsoluteIri] =
    string.split(" ").flatMap(Iri.absolute(_).toOption)

  implicit val linkEncoder: Encoder[SparqlLink] = Encoder.encodeJson.contramap {
    case SparqlExternalLink(id, paths, types)                                                  =>
      Json.obj("@id" -> id.asString.asJson, "@type" -> types.asJson, "paths" -> paths.asJson)
    case SparqlResourceLink(id, project, self, rev, types, dep, c, u, cBy, uBy, schema, paths) =>
      Json.obj(
        "@id"                    -> id.asString.asJson,
        "@type"                  -> types.asJson,
        nxv.deprecated.prefix    -> dep.asJson,
        nxv.project.prefix       -> project.asString.asJson,
        nxv.self.prefix          -> self.asString.asJson,
        nxv.rev.prefix           -> rev.asJson,
        nxv.createdAt.prefix     -> c.toString.asJson,
        nxv.updatedAt.prefix     -> u.toString.asJson,
        nxv.createdBy.prefix     -> cBy.asString.asJson,
        nxv.updatedBy.prefix     -> uBy.asString.asJson,
        nxv.constrainedBy.prefix -> schema.iri.asString.asJson,
        "paths"                  -> paths.asJson
      )
  }

  implicit private val encoderSetIris: Encoder[Set[AbsoluteIri]]   = Encoder.instance(set => toJson(set.toList))
  implicit private val encoderListIris: Encoder[List[AbsoluteIri]] = Encoder.instance(toJson)

  private def toJson(list: List[AbsoluteIri]): Json =
    list match {
      case head :: Nil => head.asString.asJson
      case Nil         => Json.Null
      case other       => Json.arr(other.map(_.asString.asJson): _*)
    }
}
