package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}

import java.time.Instant
import scala.util.Try

sealed trait SparqlLink {

  /**
    * @return the @id value of the resource
    */
  def id: Iri

  /**
    * @return the collection of types of this resource
    */
  def types: Set[Iri]

  /**
    * @return the paths from where the link has been found
    */
  def paths: List[Iri]
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
      id: Iri,
      project: Iri,
      self: Iri,
      rev: Long,
      types: Set[Iri],
      deprecated: Boolean,
      created: Instant,
      updated: Instant,
      createdBy: Iri,
      updatedBy: Iri,
      constrainedBy: Iri,
      paths: List[Iri]
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
        consBy     <- bindings.get(nxv.constrainedBy.prefix).map(_.value).flatMap(Iri.absolute(_).toOption)
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
  final case class SparqlExternalLink(id: Iri, paths: List[Iri], types: Set[Iri] = Set.empty) extends SparqlLink

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

  private def toIris(string: String): Array[Iri] =
    string.split(" ").flatMap(Iri.absolute(_).toOption)

  implicit val linkEncoder: Encoder.AsObject[SparqlLink] = Encoder.AsObject.instance {
    case SparqlExternalLink(id, paths, types) =>
      JsonObject("@id" -> id.asJson, "@type" -> types.asJson, "paths" -> paths.asJson)
    case SparqlResourceLink(id, project, self, rev, types, dep, c, u, cBy, uBy, schema, paths) =>
      JsonObject(
        "@id" -> id.asJson,
        "@type" -> types.asJson,
        nxv.deprecated.prefix -> dep.asJson,
        nxv.project.prefix -> project.asJson,
        nxv.self.prefix -> self.asJson,
        nxv.rev.prefix -> rev.asJson,
        nxv.createdAt.prefix -> c.toString.asJson,
        nxv.updatedAt.prefix -> u.toString.asJson,
        nxv.createdBy.prefix -> cBy.asJson,
        nxv.updatedBy.prefix -> uBy.asJson,
        nxv.constrainedBy.prefix -> schema.asJson,
        "paths" -> paths.asJson
      )
  }

  implicit private val encoderSetIris: Encoder[Set[Iri]]   = Encoder.instance(set => toJson(set.toList))
  implicit private val encoderListIris: Encoder[List[Iri]] = Encoder.instance(toJson)

  private def toJson(list: List[Iri]): Json =
    list match {
      case head :: Nil => head.asJson
      case Nil         => Json.Null
      case other       => Json.arr(other.map(_.asJson): _*)
    }
}
