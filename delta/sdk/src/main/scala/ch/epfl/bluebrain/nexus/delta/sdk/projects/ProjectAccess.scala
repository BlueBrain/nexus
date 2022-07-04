package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectFetchOptions.allQuotas
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{Project, ProjectFetchOptions, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

final class ProjectAccess(organizations: Organizations, projects: Projects, quotas: Quotas) {

  def fetchProject[R](ref: ProjectRef, options: Set[ProjectFetchOptions])(implicit
      subject: Subject,
      rejectionMapper: Mapper[ProjectRejection, R]
  ): IO[R, Project] =
    (IO.when(options.contains(ProjectFetchOptions.NotDeprecated))(
      organizations.fetchActiveOrganization(ref.organization).void
    ) >>
      projects.fetch(ref).flatMap {
        case resource if options.contains(ProjectFetchOptions.NotDeleted) && resource.value.markedForDeletion =>
          IO.raiseError(ProjectIsMarkedForDeletion(ref))
        case resource if options.contains(ProjectFetchOptions.NotDeprecated) && resource.deprecated           =>
          IO.raiseError(ProjectIsDeprecated(ref))
        case resource if allQuotas.subsetOf(options)                                                          =>
          (quotas.reachedForResources(ref, subject) >> quotas.reachedForEvents(ref, subject)).as(resource.value)
        case resource if options.contains(ProjectFetchOptions.VerifyQuotaResources)                           =>
          quotas.reachedForResources(ref, subject).as(resource.value)
        case resource if options.contains(ProjectFetchOptions.VerifyQuotaEvents)                              =>
          quotas.reachedForEvents(ref, subject).as(resource.value)
        case resource                                                                                         =>
          IO.pure(resource.value)
      }).mapError(rejectionMapper.to)

}
