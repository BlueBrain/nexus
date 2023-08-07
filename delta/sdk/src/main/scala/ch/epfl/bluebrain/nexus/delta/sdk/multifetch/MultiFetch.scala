package ch.epfl.bluebrain.nexus.delta.sdk.multifetch

import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.MultiFetchResponse.Result._
import ch.epfl.bluebrain.nexus.delta.sdk.multifetch.model.{MultiFetchRequest, MultiFetchResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import monix.bio.UIO

/**
  * Allows to fetch multiple resources of different types in one request.
  *
  * The response includes a resources array that contains the resources in the order specified in the request. If there
  * is a failure getting a particular resource, the error is included in place of the resource.
  */
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
            aclCheck.authorizeFor(input.project, resources.read, fetchAllCached).flatMap {
              case true  =>
                fetchResource(input).map {
                  _.map(Success(input.id, input.project, _))
                    .getOrElse(NotFound(input.id, input.project))
                }
              case false =>
                UIO.pure(AuthorizationFailed(input.id, input.project))
            }
          }
          .map { resources =>
            MultiFetchResponse(request.format, resources)
          }
      }

    }
}
