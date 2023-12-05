package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO

import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{ProjectContextRejection, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.views.PipeStep
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DefaultLabelPredicates, SourceAsText}

/**
  * The default creation of the default ElasticSearchView as part of the project initialization.
  *
  * @param views
  *   the ElasticSearchViews module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class ElasticSearchScopeInitialization(
    views: ElasticSearchViews,
    serviceAccount: ServiceAccount,
    defaults: Defaults
) extends ScopeInitialization {

  private val logger                                        = Logger[ElasticSearchScopeInitialization]
  implicit private val serviceAccountSubject: Subject       = serviceAccount.subject
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  lazy val defaultValue: IndexingElasticSearchViewValue =
    IndexingElasticSearchViewValue(
      name = Some(defaults.name),
      description = Some(defaults.description),
      resourceTag = None,
      List(PipeStep.noConfig(DefaultLabelPredicates.ref), PipeStep.noConfig(SourceAsText.ref)),
      mapping = None,
      settings = None,
      context = None,
      permission = permissions.query
    )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[Unit] =
    views
      .create(defaultViewId, project.ref, defaultValue)
      .void
      .handleErrorWith {
        case _: ResourceAlreadyExists   => IO.unit // nothing to do, view already exits
        case _: ProjectContextRejection => IO.unit // project or org are likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the default ElasticSearchView for project '${project.ref}' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultElasticSearchView")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[Unit] = IO.unit

}
