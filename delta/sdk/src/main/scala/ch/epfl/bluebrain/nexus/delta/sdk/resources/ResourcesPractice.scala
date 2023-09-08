package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.{JsonLdResult, JsonLdSourceResolvingParser}
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, ResourceF, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.expandResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{ProjectContextRejection, ResourceFetchRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

/**
  * Operations allowing to perform read-only operations on resources
  */
trait ResourcesPractice {

  /**
    * Generates the resource and validate it against the provided schema reference
    * @param project
    *   the enclosing project
    * @param schema
    *   the schema reference to validate on
    * @param source
    *   the original json payload
    * @param caller
    *   the user performing the action
    */
  def generate(project: ProjectRef, schema: IdSegment, source: NexusSource)(implicit
      caller: Caller
  ): IO[ResourceRejection, DataResource]

  /**
    * Generates the resource and validate it against the provided schema
    *
    * @param project
    *   the enclosing project
    * @param schema
    *   the schema to validate on
    * @param source
    *   the original json payload
    * @param caller
    *   the user performing the action
    */
  def generate(project: ProjectRef, schema: ResourceF[Schema], source: NexusSource)(implicit
      caller: Caller
  ): IO[ResourceRejection, DataResource]

  /**
    * Validates an existing resource.
    *
    * @param id
    *   the identifier that will be expanded to the Iri of the resource
    * @param project
    *   the project reference where the resource belongs
    * @param schemaOpt
    *   the optional identifier that will be expanded to the schema reference to validate the resource. A None value
    *   uses the currently available resource schema reference.
    */
  def validate(id: IdSegmentRef, project: ProjectRef, schemaOpt: Option[IdSegment])(implicit
      caller: Caller
  ): IO[ResourceRejection, ValidationResult]
}

object ResourcesPractice {
  def apply(
      fetchResource: (IdSegmentRef, ProjectRef) => IO[ResourceFetchRejection, DataResource],
      validateResource: ValidateResource,
      fetchContext: FetchContext[ProjectContextRejection],
      contextResolution: ResolverContextResolution
  )(implicit api: JsonLdApi, clock: Clock[UIO], uuidF: UUIDF): ResourcesPractice = new ResourcesPractice {

    private val sourceParser = JsonLdSourceResolvingParser[ResourceRejection](contextResolution, uuidF)

    override def generate(project: ProjectRef, schema: IdSegment, source: NexusSource)(implicit
        caller: Caller
    ): IO[ResourceRejection, DataResource] =
      for {
        projectContext <- fetchContext.onRead(project)
        schemaRef      <- Resources.expandResourceRef(schema, projectContext)
        jsonld         <- sourceParser(project, projectContext, source.value)
        validation     <- validateResource(jsonld.iri, jsonld.expanded, schemaRef, project, caller)
        res            <- toResourceF(project, jsonld, source, validation)
      } yield res

    override def generate(project: ProjectRef, schema: ResourceF[Schema], source: NexusSource)(implicit
        caller: Caller
    ): IO[ResourceRejection, DataResource] =
      for {
        projectContext <- fetchContext.onRead(project)
        jsonld         <- sourceParser(project, projectContext, source.value)
        validation     <- validateResource(jsonld.iri, jsonld.expanded, schema)
        res            <- toResourceF(project, jsonld, source, validation)
      } yield res

    def validate(id: IdSegmentRef, project: ProjectRef, schemaOpt: Option[IdSegment])(implicit
        caller: Caller
    ): IO[ResourceRejection, ValidationResult] = {
      for {
        projectContext <- fetchContext.onRead(project)
        schemaRefOpt   <- expandResourceRef(schemaOpt, projectContext)
        resource       <- fetchResource(id, project)
        report         <- validateResource(
                            resource.id,
                            resource.value.expanded,
                            schemaRefOpt.getOrElse(resource.schema),
                            project,
                            caller
                          )
      } yield report
    }

    private def toResourceF(
        project: ProjectRef,
        jsonld: JsonLdResult,
        source: NexusSource,
        validation: ValidationResult
    )(implicit caller: Caller): UIO[DataResource] = {
      IOUtils.instant.map { now =>
        ResourceState(
          id = jsonld.iri,
          project = project,
          schemaProject = validation.project,
          source = source.value,
          compacted = jsonld.compacted,
          expanded = jsonld.expanded,
          remoteContexts = RemoteContextRef(jsonld.remoteContexts),
          rev = 1,
          deprecated = false,
          schema = validation.schema,
          types = jsonld.types,
          tags = Tags.empty,
          createdAt = now,
          createdBy = caller.subject,
          updatedAt = now,
          updatedBy = caller.subject
        ).toResource
      }

    }

  }

}
