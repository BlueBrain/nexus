package ch.epfl.bluebrain.nexus.delta.sdk.multifetch

import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchResponse.Result
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchResponse.Result._
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.{MultiFetchRequest, MultiFetchResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import monix.bio.UIO

trait MultiFetch {

  def apply(request: MultiFetchRequest)(implicit caller: Caller): UIO[MultiFetchResponse]

}

object MultiFetch {
  def apply(
      aclCheck: AclCheck,
      fetchResource: MultiFetchRequest.Input => UIO[Option[JsonLdContent[_, _]]]
  ): MultiFetch =
    new MultiFetch {
      override def apply(request: MultiFetchRequest)(implicit
          caller: Caller
      ): UIO[MultiFetchResponse] = {
        val fetchAllCached = aclCheck.fetchAll.memoizeOnSuccess
        request.resources
          .traverse { input =>
            for {
              authorized <- aclCheck.authorizeFor(input.project, resources.read, fetchAllCached)
              resource   <- if (authorized) fetchResource(input) else UIO.none
            } yield {
              if (authorized) resource.fold[Result](NotFound(input.id, input.project)) { content =>
                Success(input.id, input.project, content)
              }
              else
                AuthorizationFailed(input.id, input.project)
            }
          }
          .map { resources =>
            MultiFetchResponse(request.format, resources)
          }
      }

    }
}
