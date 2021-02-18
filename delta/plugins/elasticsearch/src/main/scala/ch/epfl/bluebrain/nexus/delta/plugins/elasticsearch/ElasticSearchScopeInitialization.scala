package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ViewAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{MigrationState, ScopeInitialization}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.{IO, UIO}

/**
  * The default creation of the default ElasticSearchView as part of the project initialization. It performs a noop if
  * executed during a migration.
  *
  * @param views          the ElasticSearchViews module
  * @param serviceAccount the subject that will be recorded when performing the initialization
  */
class ElasticSearchScopeInitialization(views: ElasticSearchViews, serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger          = Logger[ElasticSearchScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private def loadDefault(resourcePath: String): IO[ScopeInitializationFailed, Json] =
    ClasspathResourceUtils
      .ioJsonContentOf(resourcePath)(getClass.getClassLoader)
      .mapError(e => ScopeInitializationFailed(e.toString))
      .memoize

  private val defaultMapping: IO[ScopeInitializationFailed, Json]  = loadDefault("/defaults/default-mapping.json")
  private val defaultSettings: IO[ScopeInitializationFailed, Json] = loadDefault("/defaults/default-settings.json")

  private val defaultValue: IO[ScopeInitializationFailed, IndexingElasticSearchViewValue] =
    for {
      mapping  <- defaultMapping
      settings <- defaultSettings
    } yield IndexingElasticSearchViewValue(
      mapping = mapping,
      settings = Some(settings)
    )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) UIO.unit
    else
      defaultValue
        .flatMap { value =>
          views
            .create(IriSegment(defaultViewId), project.ref, value)
            .void
            .onErrorHandleWith {
              case _: ViewAlreadyExists => UIO.unit // nothing to do, view already exits
              case rej                  =>
                val str =
                  s"Failed to create the default ElasticSearchView for project '${project.ref}' due to '${rej.reason}'."
                UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
            }
        }
        .named("createDefaultElasticSearchView", ElasticSearchViews.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
