package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, Label, ResourceF, ResourceRef}

/**
  * Enumeration of Resolver state types
  */
sealed trait ResolverState extends Product with Serializable {

  /**
    * @return the schema reference that resolvers conforms to
    */
  final def schema: ResourceRef = Latest(schemas.resolvers)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings): Option[ResolverResource]

}

object ResolverState {

  /**
    * Initial resolver state.
    */
  final case object Initial extends ResolverState {
    override def toResource(mappings: ApiMappings): Option[ResolverResource] = None
  }

  /**
    * State for an existing in project resolver
    * @param id                the id of the resolver
    * @param project           the project it belongs to
    * @param `type`            type of the resolver (can't be updated)
    * @param priority          resolution priority when attempting to find a resource
    * @param crossProjectSetup additional setup for a cross-project resolver
    * @param tags              the collection of tag aliases
    * @param rev               the current state revision
    * @param deprecated        the current state deprecation status
    * @param createdAt         the instant when the resource was created
    * @param createdBy         the subject that created the resource
    * @param updatedAt         the instant when the resource was last updated
    * @param updatedBy         the subject that last updated the resource
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      `type`: ResolverType,
      priority: Priority,
      crossProjectSetup: Option[CrossProjectSetup],
      tags: Map[Label, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends ResolverState {

    @SuppressWarnings(Array("OptionGet"))
    def resolver: Resolver =
      `type` match {
        case InProject    =>
          InProjectResolver(
            id = id,
            project = project,
            priority = priority,
            tags = tags
          )
        case CrossProject =>
          val setup = crossProjectSetup.get
          CrossProjectResolver(
            id = id,
            project = project,
            resourceTypes = setup.resourceTypes,
            projects = setup.projects,
            identities = setup.identities,
            priority = priority,
            tags = tags
          )
      }

    override def toResource(mappings: ApiMappings): Option[ResolverResource] =
      Some(
        ResourceF(
          id = AccessUrl.resolver(project, id)(_).iri,
          accessUrl = AccessUrl.resolver(project, id)(_).shortForm(mappings),
          rev = rev,
          types = `type` match {
            case InProject    => Set(nxv.Resolver, nxv.InProject)
            case CrossProject => Set(nxv.Resolver, nxv.CrossProject)
          },
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = resolver
        )
      )
  }

}
