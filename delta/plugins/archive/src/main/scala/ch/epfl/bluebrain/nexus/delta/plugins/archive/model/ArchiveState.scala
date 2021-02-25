package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris.RootResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
  * Enumeration of archive states.
  */
sealed trait ArchiveState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource]
}

object ArchiveState {

  /**
    * Initial state of an archive.
    */
  final case object Initial extends ArchiveState {
    override def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource] =
      None
  }

  /**
    * State of an existing archive.
    *
    * @param id        the archive identifier
    * @param project   the archive parent project
    * @param value     the archive value
    * @param createdAt the instant when the archive was created
    * @param createdBy the subject that created the archive
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      value: ArchiveValue,
      createdAt: Instant,
      createdBy: Subject
  ) extends ArchiveState {
    override def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource] = {
      val allMappings = mappings + ApiMappings.default
      val ctx         = JsonLdContext(
        ContextValue.empty,
        base = Some(base.iri),
        prefixMappings = allMappings.prefixMappings,
        aliases = allMappings.aliases
      )

      val relative          = Uri("archives") / project.organization.value / project.project.value
      val relativeAccess    = relative / id.toString
      val relativeShortForm = relative / ctx.compact(id, useVocab = false)
      val resourceUris      = RootResourceUris(relativeAccess, relativeShortForm)

      Some(
        ResourceF(
          id = id,
          uris = resourceUris,
          rev = 1L,
          types = Set(tpe),
          deprecated = false,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = createdAt,
          updatedBy = createdBy,
          schema = schema,
          value = Archive(value.resources, ttl.toSeconds)
        )
      )
    }
  }
}
