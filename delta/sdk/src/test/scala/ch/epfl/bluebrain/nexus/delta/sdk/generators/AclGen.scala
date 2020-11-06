package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import org.scalatest.OptionValues

object AclGen extends OptionValues {

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
      subject: Subject = Identity.Anonymous
  ): AclResource =
    currentState(acl, rev, subject, subject).toResource.value

}
