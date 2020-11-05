package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, BaseUri, ResourceF}

object AclGen {

  def currentState(
      acl: Acl,
      rev: Long,
      createdBy: Subject = Identity.Anonymous,
      updatedBy: Subject = Identity.Anonymous
  ): Current =
    Current(acl, rev, Instant.EPOCH, createdBy, Instant.EPOCH, updatedBy)

  def resourceFor(
      acl: Acl,
      rev: Long = 1L,
      subject: Subject = Identity.Anonymous,
      deprecated: Boolean = false
  )(implicit base: BaseUri): AclResource = {
    val accessUrl = AccessUrl.acl(acl.address)
    ResourceF(
      id = accessUrl.iri,
      accessUrl = accessUrl,
      rev = rev,
      types = Set(nxv.AccessControlList),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.acls),
      value = acl
    )
  }

}
